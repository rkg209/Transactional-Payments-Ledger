# database-design.md — Transactional Payments Ledger

Version: 1.0 | Derived from: architecture.md v1.0, system-design.md v1.0 | Status: Authoritative

---

## Table of Contents

1. [Design Philosophy](#1-design-philosophy)
2. [Entity Catalogue](#2-entity-catalogue)
3. [Entity Relationship Diagram](#3-entity-relationship-diagram)
4. [Table Specifications](#4-table-specifications)
5. [Relationships and Referential Integrity](#5-relationships-and-referential-integrity)
6. [Data Ownership by Module](#6-data-ownership-by-module)
7. [Invariant Enforcement at the Schema Level](#7-invariant-enforcement-at-the-schema-level)
8. [Indexing Strategy](#8-indexing-strategy)
9. [Multi-Tenancy](#9-multi-tenancy)
10. [Schema Evolution Policy](#10-schema-evolution-policy)
11. [Design Decisions and Rationale](#11-design-decisions-and-rationale)

---

## 1. Design Philosophy

The schema is designed around one non-negotiable constraint:

```
Σ(amount_minor × direction_sign for ALL ledger_entries) = 0   at all times
```

Every structural decision either enforces this invariant directly or is justified by not threatening it. Five principles follow from this constraint and govern every table, column, and index decision:

**Principle 1 — Append-only ledger.** `ledger_entries` is the authoritative record of all value movement. No row in this table is ever updated or deleted. The schema makes this structurally difficult (no soft-delete column, no `updated_at`, no status column that would invite mutation) and the application enforces it by never issuing `UPDATE` or `DELETE` against this table. Reversals are new entries, not modifications.

**Principle 2 — Amounts in minor units as integers.** All monetary amounts are stored as `BIGINT` representing the smallest indivisible unit of the currency (cents for USD, pence for GBP, etc.). Floating-point types (`FLOAT`, `DOUBLE`, `NUMERIC` with scale) are excluded. Integer arithmetic is exact; there is no rounding error to accumulate across millions of entries.

**Principle 3 — Materialized balance as a cache, not a source of truth.** The `accounts.balance` column is a performance optimization. The authoritative balance for any account is always `Σ(ledger_entries WHERE account_id = ?)` with sign applied. The `ReconciliationService` continuously verifies that the materialized balance matches the entry sum. If they diverge, the entry sum wins.

**Principle 4 — Explicit over implicit.** Every constraint that can be expressed in SQL is expressed in SQL: `CHECK` constraints on enumerations, `CHECK` constraints on sign, `NOT NULL` on every column that must be present, `UNIQUE` on every natural key. The application layer is a second line of defense, not the first.

**Principle 5 — Schema is the contract.** The Flyway migration files are the single source of truth for the schema. The jOOQ code generator reads the live schema and produces type-safe query classes. If the schema and the application disagree, the build fails. There is no ORM that silently tolerates schema drift.

---

## 2. Entity Catalogue

Eight entities exist in the system. They are grouped by their functional domain.

### 2.1 Core Financial Entities

| Entity | Table | Purpose |
|---|---|---|
| Account | `accounts` | Holds a named balance in a single currency. The subject of all value movement. |
| Transfer | `transfers` | A record of intent and outcome for a single atomic value movement between two accounts. |
| Ledger Entry | `ledger_entries` | An immutable, append-only record of one side of one transfer. The source of truth for all balances. |

### 2.2 Infrastructure Entities

| Entity | Table | Purpose |
|---|---|---|
| Idempotency Key | `idempotency_keys` | Deduplicates HTTP requests. Stores the request fingerprint and the serialized response for replay. |
| Saga | `sagas` | Tracks the lifecycle of a multi-step transfer orchestration. |
| Saga Step | `saga_steps` | Tracks the lifecycle of one step within a saga, including its forward result for use during compensation. |

### 2.3 Observability Entities

| Entity | Table | Purpose |
|---|---|---|
| Reconciliation Report | `reconciliation_reports` | Persists the output of each scheduled invariant check run. |

---

## 3. Entity Relationship Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         CORE FINANCIAL DOMAIN                               │
│                                                                             │
│   ┌──────────────────┐          ┌──────────────────┐                        │
│   │    accounts      │          │    transfers     │                        │
│   │──────────────────│          │──────────────────│                        │
│   │ PK id (UUID)     │          │ PK id (UUID)     │                        │
│   │    name          │          │    idempotency_  │                        │
│   │    currency      │          │      key (UQ)    │                        │
│   │    min_balance   │          │    status        │                        │
│   │    balance       │          │    created_at    │                        │
│   │    version       │          │    updated_at    │                        │
│   │    created_at    │          └────────┬─────────┘                        │
│   │    updated_at    │                   │                                  │
│   └────────┬─────────┘                   │ 1                                │
│            │                             │                                  │
│            │ 1                           │ has many                         │
│            │                             │                                  │
│            │ has many                    │ N                                │
│            │                    ┌────────▼──────────────────┐               │
│            │                    │      ledger_entries       │               │
│            │                    │───────────────────────────│               │
│            └───────────────────►│ PK id (UUID)              │               │
│                          N      │ FK transfer_id → transfers│               │
│                                 │ FK account_id  → accounts │               │
│                                 │    direction (DEBIT|CREDIT)│              │
│                                 │    amount_minor (BIGINT>0) │              │
│                                 │    created_at              │              │
│                                 └───────────────────────────┘               │
│                                                                             │
│  Relationship summary:                                                      │
│    accounts      1 ──── N   ledger_entries   (one account, many entries)   │
│    transfers     1 ──── N   ledger_entries   (one transfer, many entries)  │
│    transfers     1 ──── 2   ledger_entries   (exactly 2 per simple xfer)   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                         INFRASTRUCTURE DOMAIN                               │
│                                                                             │
│   ┌──────────────────────────┐                                              │
│   │     idempotency_keys     │   (no FK to other tables — intentional;      │
│   │──────────────────────────│    see §11.3)                                │
│   │ PK key (VARCHAR)         │                                              │
│   │    request_fingerprint   │                                              │
│   │    response_snapshot     │                                              │
│   │    status                │                                              │
│   │    created_at            │                                              │
│   │    updated_at            │                                              │
│   └──────────────────────────┘                                              │
│                                                                             │
│   ┌──────────────────┐   1        N   ┌──────────────────────┐             │
│   │      sagas       │◄───────────────│     saga_steps       │             │
│   │──────────────────│               │──────────────────────│             │
│   │ PK id (UUID)     │               │ PK id (UUID)         │             │
│   │    type          │               │ FK saga_id → sagas   │             │
│   │    state         │               │    step_index        │             │
│   │    current_step  │               │    step_type         │             │
│   │    payload       │               │    state             │             │
│   │    created_at    │               │    forward_result    │             │
│   │    updated_at    │               │    created_at        │             │
│   └──────────────────┘               │    updated_at        │             │
│                                      └──────────────────────┘             │
│                                                                             │
│  Relationship summary:                                                      │
│    sagas   1 ──── N   saga_steps   (one saga, ordered steps)               │
│    UNIQUE (saga_id, step_index)     (each step position is unique)         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                         OBSERVABILITY DOMAIN                                │
│                                                                             │
│   ┌──────────────────────────────┐                                          │
│   │    reconciliation_reports    │   (no FK to other tables — intentional;  │
│   │──────────────────────────────│    see §11.4)                            │
│   │ PK id (UUID)                 │                                          │
│   │    run_at                    │                                          │
│   │    global_sum                │                                          │
│   │    drift_detected            │                                          │
│   │    drift_details (JSONB)     │                                          │
│   │    accounts_checked          │                                          │
│   │    accounts_drifted          │                                          │
│   └──────────────────────────────┘                                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

Cross-domain relationships:
  sagas.payload (JSONB)        references account IDs and amounts by value,
                               not by FK — intentional (see §11.5)
  idempotency_keys.key         referenced by value in transfers.idempotency_key,
                               not by FK — intentional (see §11.3)
```

---

## 4. Table Specifications

Each table is documented with its full column set, constraints, nullability contract, and the rationale for every non-obvious decision.

### 4.1 `accounts`

**Purpose:** Represents a named financial account holding a balance in a single currency. The subject of all value movement in the system.

**Owner module:** `org.ledger.account` (writes), `org.ledger.concurrency` (reads with locking), `org.ledger.transfer` (balance updates), `org.ledger.reconciliation` (reads only).

```sql
CREATE TABLE accounts (
    id          UUID         NOT NULL PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    currency    CHAR(3)      NOT NULL DEFAULT 'USD',
    min_balance BIGINT       NOT NULL DEFAULT 0,
    balance     BIGINT       NOT NULL DEFAULT 0,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT accounts_balance_gte_min
        CHECK (balance >= min_balance),
    CONSTRAINT accounts_min_balance_non_negative
        CHECK (min_balance >= 0),
    CONSTRAINT accounts_currency_length
        CHECK (char_length(currency) = 3),
    CONSTRAINT accounts_name_not_blank
        CHECK (char_length(trim(name)) > 0)
);
```

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `UUID` | NOT NULL | `gen_random_uuid()` | Surrogate PK. UUID v4 generated by PostgreSQL, not the application, to avoid clock-skew issues in distributed scenarios and to keep generation logic in one place. |
| `name` | `VARCHAR(255)` | NOT NULL | — | Human-readable label. Not a unique key; two accounts may share a name. The `CHECK` constraint prevents blank strings that pass `NOT NULL`. |
| `currency` | `CHAR(3)` | NOT NULL | `'USD'` | ISO 4217 alphabetic code. `CHAR(3)` is used rather than `VARCHAR(3)` to make the fixed-width expectation explicit and to allow the database to store it efficiently. Application-layer validation (`CurrencyCodeValidator`) checks against the full ISO 4217 list; the `CHECK` constraint is a backstop for direct DB writes. |
| `min_balance` | `BIGINT` | NOT NULL | `0` | Minimum allowable balance in minor units. The `CHECK (balance >= min_balance)` constraint is the database-level enforcement of the overdraft rule. The application checks this before writing entries, but the constraint is the authoritative guard. |
| `balance` | `BIGINT` | NOT NULL | `0` | Materialized balance in minor units. This is a denormalized cache of `Σ(ledger_entries WHERE account_id = id)`. It is updated atomically within the same transaction that inserts ledger entries. It is verified against the entry sum by `ReconciliationService`. |
| `version` | `BIGINT` | NOT NULL | `0` | Optimistic concurrency version counter. Incremented by 1 on every `UPDATE` to `balance`. Used exclusively by `OptimisticStrategy`; ignored by `PessimisticStrategy` but kept in the schema so the table structure is identical under both strategies. |
| `created_at` | `TIMESTAMPTZ` | NOT NULL | `now()` | Immutable creation timestamp. Never updated after insert. |
| `updated_at` | `TIMESTAMPTZ` | NOT NULL | `now()` | Updated on every write. Useful for debugging and audit; not used in any query predicate. |

**Constraints rationale:**

- `accounts_balance_gte_min`: This is the overdraft guard at the database level. The application enforces this before writing, but the `CHECK` constraint is the last line of defense against a bug in the application's pre-check logic. If a transaction somehow bypasses the application check and attempts to commit a balance below `min_balance`, PostgreSQL rejects it.
- `accounts_min_balance_non_negative`: A negative `min_balance` would be semantically incoherent (it would mean the account is allowed to go into debt by an unbounded amount). This constraint prevents that.
- No `UNIQUE` on `name`: Account names are display labels, not identifiers. Enforcing uniqueness would create operational friction (renaming, duplicate customer names) with no correctness benefit.
- No `status` or `closed_at` column: Account lifecycle management (closing, suspending) is out of scope for this version. Adding a `status` column later is a non-breaking migration.

---

### 4.2 `transfers`

**Purpose:** Records the intent and outcome of a single atomic value movement. Acts as the parent record for a balanced pair of ledger entries. Provides a stable identifier for idempotency and audit.

**Owner module:** `org.ledger.transfer` (all writes and reads).

```sql
CREATE TABLE transfers (
    id              UUID        NOT NULL PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(255),
    status          VARCHAR(50) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT transfers_status_valid
        CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    CONSTRAINT transfers_idempotency_key_unique
        UNIQUE (idempotency_key)
);
```

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `UUID` | NOT NULL | `gen_random_uuid()` | Surrogate PK. Returned to the client as the transfer identifier. |
| `idempotency_key` | `VARCHAR(255)` | NULL | — | The client-supplied idempotency key, copied here for audit linkage. Nullable because saga-internal transfers may not carry a client-facing idempotency key. The `UNIQUE` constraint prevents two transfer records from claiming the same key. |
| `status` | `VARCHAR(50)` | NOT NULL | — | State machine value. `PENDING` on insert; `COMPLETED` on successful commit; `FAILED` if the transfer is rolled back after the record was inserted. The `CHECK` constraint enforces the closed set. |
| `created_at` | `TIMESTAMPTZ` | NOT NULL | `now()` | Immutable creation timestamp. |
| `updated_at` | `TIMESTAMPTZ` | NOT NULL | `now()` | Updated when status changes. |

**Status state machine:**

```
PENDING ──► COMPLETED
   │
   └──────► FAILED
```

`PENDING` is the only initial state. A transfer record is inserted as `PENDING` before any ledger entries are written. This means that if the process crashes between the `transfers` insert and the `ledger_entries` inserts, the transfer record exists in `PENDING` state with no associated entries. The `ReconciliationService` detects this as a `PENDING` transfer with zero entries and flags it. There is no automated correction; a human operator investigates.

**Why `transfers` exists as a separate table from `ledger_entries`:**

A transfer is a semantic unit (a business event) that groups two or more ledger entries. Without the `transfers` table, there would be no stable identifier to return to the client, no place to record the idempotency key linkage, and no way to query "what happened in this transfer" without aggregating entries. The `transfers` table is the business-level record; `ledger_entries` is the accounting-level record.

---

### 4.3 `ledger_entries`

**Purpose:** The immutable, append-only accounting record. Every credit and debit in the system is a row in this table. The sum of all rows, with sign applied by direction, must equal zero at all times. This table is the source of truth for all balances.

**Owner module:** `org.ledger.transfer` (inserts only, via `LedgerEntryRepository`). No module may issue `UPDATE` or `DELETE` against this table.

```sql
CREATE TABLE ledger_entries (
    id          UUID        NOT NULL PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id UUID        NOT NULL REFERENCES transfers(id)
                            ON DELETE RESTRICT ON UPDATE RESTRICT,
    account_id  UUID        NOT NULL REFERENCES accounts(id)
                            ON DELETE RESTRICT ON UPDATE RESTRICT,
    direction   VARCHAR(6)  NOT NULL,
    amount_minor BIGINT     NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ledger_entries_direction_valid
        CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ledger_entries_amount_positive
        CHECK (amount_minor > 0)
);
```

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `UUID` | NOT NULL | `gen_random_uuid()` | Surrogate PK. |
| `transfer_id` | `UUID` | NOT NULL | — | FK to `transfers.id`. Every entry belongs to exactly one transfer. `ON DELETE RESTRICT` prevents deleting a transfer that has entries — an additional guard against accidental data loss. |
| `account_id` | `UUID` | NOT NULL | — | FK to `accounts.id`. Every entry belongs to exactly one account. `ON DELETE RESTRICT` prevents deleting an account that has entries. |
| `direction` | `VARCHAR(6)` | NOT NULL | — | `'DEBIT'` or `'CREDIT'`. The sign convention: DEBIT decreases the account balance; CREDIT increases it. The `CHECK` constraint enforces the closed set. |
| `amount_minor` | `BIGINT` | NOT NULL | — | The absolute value of the movement in minor currency units. Always positive. The sign is carried by `direction`, not by the sign of this column. This separation prevents ambiguity (a negative CREDIT is not the same as a DEBIT). The `CHECK (amount_minor > 0)` constraint prevents zero-value entries, which would be semantically meaningless and could mask bugs. |
| `created_at` | `TIMESTAMPTZ` | NOT NULL | `now()` | Immutable creation timestamp. The only timestamp column; there is no `updated_at` because this table is never updated. |

**Intentional omissions:**

- No `updated_at`: This column's presence would imply the row can be updated. Its absence is a design signal.
- No `status` or `reversed` column: Reversals are new entries with the opposite direction. A `reversed` flag would invite mutation of the original entry, which violates the append-only principle.
- No `currency` column: Currency is a property of the account, not the entry. All entries for an account are in the account's currency. Cross-currency transfers are out of scope; if they were in scope, a `currency` column would be added here.
- No `description` or `memo` column: Out of scope for this version. Adding one later is a non-breaking migration.

**The zero-sum invariant expressed as SQL:**

```sql
-- This query must always return a single row with value 0:
SELECT SUM(
    CASE WHEN direction = 'DEBIT'  THEN -amount_minor
         WHEN direction = 'CREDIT' THEN  amount_minor
    END
) AS global_sum
FROM ledger_entries;
```

This query is the heart of `ReconciliationService.runCheck()`.

---

### 4.4 `idempotency_keys`

**Purpose:** Deduplicates HTTP requests. Stores the SHA-256 fingerprint of the request body and the serialized HTTP response body, enabling exact replay of any previously completed request.

**Owner module:** `org.ledger.idempotency` (all reads and writes, exclusively).

```sql
CREATE TABLE idempotency_keys (
    key                 VARCHAR(255) NOT NULL PRIMARY KEY,
    request_fingerprint VARCHAR(64)  NOT NULL,
    response_snapshot   TEXT,
    status              VARCHAR(20)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT idempotency_keys_status_valid
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED'))
);
```

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `key` | `VARCHAR(255)` | NOT NULL | — | The client-supplied idempotency key. Primary key; the `UNIQUE` constraint is implicit. The uniqueness constraint is the mechanism that serializes concurrent duplicate requests: only one `INSERT` can succeed; the second gets a unique constraint violation and falls into the polling path. |
| `request_fingerprint` | `VARCHAR(64)` | NOT NULL | — | SHA-256 hex digest of the raw request body bytes. 64 hex characters = 32 bytes = 256 bits. Used to detect key reuse with a different request body (HTTP 422). |
| `response_snapshot` | `TEXT` | NULL | — | The serialized JSON response body of the completed request. Null while `status = 'IN_PROGRESS'` or `'FAILED'`. Populated when `status` transitions to `'COMPLETED'`. Returned verbatim to the client on replay. |
| `status` | `VARCHAR(20)` | NOT NULL | — | `IN_PROGRESS`: request is executing. `COMPLETED`: request succeeded; `response_snapshot` is populated. `FAILED`: request failed; a retry with the same key is allowed. |
| `created_at` | `TIMESTAMPTZ` | NOT NULL | `now()` | Immutable creation timestamp. |
| `updated_at` | `TIMESTAMPTZ` | NOT NULL | `now()` | Updated when status changes. |

**Status state machine:**

```
IN_PROGRESS ──► COMPLETED
     │
     └────────► FAILED
```

`FAILED` is a terminal state that permits retry. When a client retries a `FAILED` key, the `IdempotencyService` treats it as `NOT FOUND` and allows the request to proceed, overwriting the `FAILED` record. This is the correct behavior: a failed request should be retryable.

**Why `response_snapshot` is `TEXT` and not `JSONB`:**

The snapshot is stored as an opaque string and written back to the HTTP response verbatim. Storing it as `JSONB` would cause PostgreSQL to parse and re-serialize it, potentially changing whitespace or key ordering. `TEXT` preserves the exact bytes that were sent to the client originally, ensuring bit-for-bit replay.

---

### 4.5 `sagas`

**Purpose:** Tracks the lifecycle of a multi-step transfer orchestration. Provides the durable state that allows `SagaRecoveryRunner` to resume or compensate in-progress sagas after a crash.

**Owner module:** `org.ledger.saga` (all reads and writes, exclusively).

```sql
CREATE TABLE sagas (
    id           UUID         NOT NULL PRIMARY KEY DEFAULT gen_random_uuid(),
    type         VARCHAR(100) NOT NULL,
    state        VARCHAR(20)  NOT NULL,
    current_step INT          NOT NULL DEFAULT 0,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT sagas_state_valid
        CHECK (state IN ('STARTED', 'COMPLETED', 'COMPENSATING',
                         'COMPENSATED', 'FAILED')),
    CONSTRAINT sagas_current_step_non_negative
        CHECK (current_step >= 0)
);
```

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `UUID` | NOT NULL | `gen_random_uuid()` | Surrogate PK. Returned to the client as the saga identifier. |
| `type` | `VARCHAR(100)` | NOT NULL | — | Discriminator string identifying the saga definition class (e.g., `'MULTI_ACCOUNT_TRANSFER'`). Used by `SagaRecoveryRunner` to reconstruct the `SagaDefinition` from the payload. |
| `state` | `VARCHAR(20)` | NOT NULL | — | Current lifecycle state. See state machine below. |
| `current_step` | `INT` | NOT NULL | `0` | Zero-based index of the step currently executing or last completed. Updated atomically with the step's `saga_steps` record. Used by recovery to determine which steps need compensation. |
| `payload` | `JSONB` | NOT NULL | — | The original request payload (account IDs, amounts, etc.) serialized as JSON. Stored here so that recovery can reconstruct the saga's inputs without relying on the original HTTP request, which is gone after the crash. |
| `created_at` | `TIMESTAMPTZ` | NOT NULL | `now()` | Immutable creation timestamp. |
| `updated_at` | `TIMESTAMPTZ` | NOT NULL | `now()` | Updated on every state transition. |

**State machine:**

```
STARTED ──► (step execution) ──► COMPLETED
   │
   └──► COMPENSATING ──► COMPENSATED
              │
              └──► FAILED   (compensation itself failed; requires manual intervention)
```

`FAILED` is a terminal state indicating that both the forward execution and the compensation failed. This is the worst case: the system is in an inconsistent state and requires human intervention. The `ReconciliationService` will detect the resulting balance drift and alert.

---

### 4.6 `saga_steps`

**Purpose:** Tracks the lifecycle of one step within a saga. Stores the forward result so that the compensation function has the information it needs to reverse the step's effects.

**Owner module:** `org.ledger.saga` (all reads and writes, exclusively).

```sql
CREATE TABLE saga_steps (
    id             UUID         NOT NULL PRIMARY KEY DEFAULT gen_random_uuid(),
    saga_id        UUID         NOT NULL REFERENCES sagas(id)
                                ON DELETE RESTRICT ON UPDATE RESTRICT,
    step_index     INT          NOT NULL,
    step_type      VARCHAR(100) NOT NULL,
    state          VARCHAR(20)  NOT NULL,
    forward_result JSONB,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT saga_steps_state_valid
        CHECK (state IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'COMPENSATED')),
    CONSTRAINT saga_steps_step_index_non_negative
        CHECK (step_index >= 0),
    CONSTRAINT saga_steps_unique_position
        UNIQUE (saga_id, step_index)
);
```

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `UUID` | NOT NULL | `gen_random_uuid()` | Surrogate PK. |
| `saga_id` | `UUID` | NOT NULL | — | FK to `sagas.id`. Every step belongs to exactly one saga. |
| `step_index` | `INT` | NOT NULL | — | Zero-based position of this step in the saga's ordered step list. The `UNIQUE (saga_id, step_index)` constraint prevents duplicate step records for the same position, which would make recovery ambiguous. |
| `step_type` | `VARCHAR(100)` | NOT NULL | — | Discriminator string identifying the `SagaStep` implementation (e.g., `'DEBIT_ACCOUNT'`, `'CREDIT_ACCOUNT'`). Used by recovery to instantiate the correct compensation function. |
| `state` | `VARCHAR(20)` | NOT NULL | — | Current lifecycle state of this step. |
| `forward_result` | `JSONB` | NULL | — | The return value of `SagaStep.forward()`, serialized as JSON. Null until the step completes. Passed to `SagaStep.compensate()` during rollback so the compensation has full context (e.g., the transfer ID that was created, so it can be marked `FAILED`). |
| `created_at` | `TIMESTAMPTZ` | NOT NULL | `now()` | Immutable creation timestamp. |
| `updated_at` | `TIMESTAMPTZ` | NOT NULL | `now()` | Updated on every state transition. |

**Step state machine:**

```
PENDING ──► IN_PROGRESS ──► COMPLETED
                                │
                                └──► COMPENSATED
```

`PENDING` is the initial state when the step record is first inserted. `IN_PROGRESS` is set immediately before the step's `forward()` method is called, in its own committed transaction. This means that if the process crashes while `forward()` is executing, the step record shows `IN_PROGRESS` and recovery knows to treat it as not completed (because the `forward()` transaction may not have committed).

---

### 4.7 `reconciliation_reports`

**Purpose:** Persists the output of each scheduled invariant check. Provides a queryable history of system health and a durable record of any detected drift.

**Owner module:** `org.ledger.reconciliation` (all reads and writes, exclusively).

```sql
CREATE TABLE reconciliation_reports (
    id               UUID        NOT NULL PRIMARY KEY DEFAULT gen_random_uuid(),
    run_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    global_sum       BIGINT      NOT NULL,
    drift_detected   BOOLEAN     NOT NULL,
    drift_details    JSONB,
    accounts_checked INT         NOT NULL,