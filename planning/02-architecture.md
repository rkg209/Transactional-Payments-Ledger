# architecture.md — Transactional Payments Ledger

Version: 1.0 | Status: Approved | Feeds: All SPEC 0000–0009 implementation phases

---

## 1. System Overview

The Transactional Payments Ledger is a single-process, stateless REST API service backed by a single PostgreSQL database. It implements double-entry bookkeeping: every transfer produces a balanced set of immutable ledger entries that sum to zero, and all account balances are derived from that append-only entry log. Correctness under concurrency and crashes is the primary design constraint; every architectural decision below is made in service of that constraint.

The system is intentionally narrow in scope. There is no message broker, no distributed transaction coordinator, no external cache, and no read replica. Complexity that does not serve the correctness proof is excluded. The result is a system whose behavior is fully analyzable, whose invariants are continuously checked, and whose headline guarantee is reproducible by running a single command.

**System boundaries:**

```
┌─────────────────────────────────────────────────────────────────┐
│                        System Boundary                          │
│                                                                 │
│   HTTP clients ──► Spring Boot REST API ──► PostgreSQL 16       │
│                          │                                      │
│                    In-process only:                             │
│                    • Saga orchestrator                          │
│                    • Reconciliation job                         │
│                    • Concurrency strategy (opt/pess)            │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

External to system boundary:
  • HTTP clients (curl, test harness, demo.sh)
  • Prometheus scraper (reads /actuator/prometheus)
  • Docker host / CI runner
```

**The single correctness invariant that governs everything:**

```
Σ(amount_minor × direction_sign for ALL ledger_entries) = 0   at all times
```

Every design decision either enforces this invariant or is justified by not threatening it.

---

## 2. Component Architecture

The application is organized into six logical layers, each with a single, non-overlapping responsibility. Package structure follows package-by-feature within each layer.

```
┌──────────────────────────────────────────────────────────────────────┐
│                         Spring Boot Process                          │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │  Layer 1: API (org.ledger.api)                                 │  │
│  │  • REST controllers (TransferController, AccountController,    │  │
│  │    ReconciliationController, HealthController)                 │  │
│  │  • Request/response DTOs                                       │  │
│  │  • OpenAPI annotations                                         │  │
│  │  • Input validation (@Valid, custom validators)                │  │
│  │  • Idempotency-Key header extraction                           │  │
│  │  • Error model mapping (GlobalExceptionHandler)                │  │
│  └────────────────────────┬───────────────────────────────────────┘  │
│                           │ calls                                    │
│  ┌────────────────────────▼───────────────────────────────────────┐  │
│  │  Layer 2: Idempotency (org.ledger.idempotency)                 │  │
│  │  • IdempotencyFilter: intercepts every mutating request        │  │
│  │  • Checks idempotency_keys table (unique constraint on key)    │  │
│  │  • Returns stored response if key seen                         │  │
│  │  • Stores response after successful execution                  │  │
│  │  • Handles concurrent same-key races via DB unique constraint  │  │
│  └────────────────────────┬───────────────────────────────────────┘  │
│                           │ calls (only on new keys)                 │
│  ┌────────────────────────▼───────────────────────────────────────┐  │
│  │  Layer 3: Service (org.ledger.transfer, org.ledger.account,    │  │
│  │           org.ledger.saga, org.ledger.reconciliation)          │  │
│  │                                                                │  │
│  │  TransferService          — owns the ACID transaction boundary │  │
│  │    • Validates accounts and amounts                            │  │
│  │    • Acquires locks (via chosen ConcurrencyStrategy)           │  │
│  │    • Enforces minimum balance                                  │  │
│  │    • Inserts balanced ledger entries (Σ = 0 enforced here)     │  │
│  │    • Updates materialized balance (if enabled)                 │  │
│  │                                                                │  │
│  │  AccountService           — account CRUD, balance queries      │  │
│  │                                                                │  │
│  │  SagaOrchestrator         — in-process multi-step coordinator  │  │
│  │    • Persists saga state before each step                      │  │
│  │    • Executes steps via SagaStep implementations               │  │
│  │    • On failure: runs compensations in reverse order           │  │
│  │    • On restart: recovers in-progress sagas from DB            │  │
│  │                                                                │  │
│  │  ReconciliationService    — invariant checker                  │  │
│  │    • Scheduled: asserts Σ(all entries) = 0                     │  │
│  │    • Asserts per-account balance = Σ(account entries)          │  │
│  │    • Emits metrics and structured log on drift                 │  │
│  └────────────────────────┬───────────────────────────────────────┘  │
│                           │ calls                                    │
│  ┌────────────────────────▼───────────────────────────────────────┐  │
│  │  Layer 4: Concurrency Strategy (org.ledger.concurrency)        │  │
│  │                                                                │  │
│  │  ConcurrencyStrategy (interface)                               │  │
│  │    lockAndLoad(accountIds, DSLContext) → List<AccountRecord>   │  │
│  │                                                                │  │
│  │  OptimisticStrategy                                            │  │
│  │    • Plain SELECT on accounts                                  │  │
│  │    • UPDATE accounts SET ... WHERE id=? AND version=?          │  │
│  │    • On version mismatch: retry up to MAX_RETRIES (default 5)  │  │
│  │    • Throws OptimisticLockException after exhaustion           │  │
│  │                                                                │  │
│  │  PessimisticStrategy                                           │  │
│  │    • SELECT ... FROM accounts WHERE id IN (?) FOR UPDATE       │  │
│  │      SKIP LOCKED (configurable: NOWAIT or blocking)            │  │
│  │    • Accounts locked for duration of transaction               │  │
│  │    • No retry needed; DB serializes access                     │  │
│  │                                                                │  │
│  │  Active strategy selected at startup via:                      │  │
│  │    CONCURRENCY_STRATEGY=optimistic|pessimistic (env var)       │  │
│  └────────────────────────┬───────────────────────────────────────┘  │
│                           │ calls                                    │
│  ┌────────────────────────▼───────────────────────────────────────┐  │
│  │  Layer 5: Repository / Data Access (org.ledger.db)             │  │
│  │                                                                │  │
│  │  All DB access via jOOQ DSL — zero raw SQL strings             │  │
│  │  jOOQ generated code: org.ledger.db.generated (do not edit)   │  │
│  │                                                                │  │
│  │  AccountRepository        — account reads/writes               │  │
│  │  LedgerEntryRepository    — append-only entry inserts + sums   │  │
│  │  TransferRepository       — transfer record management         │  │
│  │  IdempotencyRepository    — key lookup and storage             │  │
│  │  SagaRepository           — saga + saga_step persistence       │  │
│  │  ReconciliationRepository — aggregate queries for invariant    │  │
│  └────────────────────────┬───────────────────────────────────────┘  │
│                           │ JDBC                                     │
│  ┌────────────────────────▼───────────────────────────────────────┐  │
│  │  Layer 6: Infrastructure (org.ledger.infrastructure)           │  │
│  │                                                                │  │
│  │  DataSourceConfig         — HikariCP connection pool           │  │
│  │  TransactionConfig        — Spring @Transactional wiring       │  │
│  │  SecurityConfig           — Spring Security (API key / JWT)    │  │
│  │  MetricsConfig            — Micrometer registry wiring         │  │
│  │  SagaRecoveryRunner       — ApplicationRunner: recover sagas   │  │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

### 2.1 Key Design Decisions Embedded in the Component Structure

**Transaction boundary ownership.** `TransferService` is the sole owner of the `@Transactional` annotation on the transfer execution path. No controller, no filter, and no repository opens a transaction independently on the write path. This makes the transaction boundary explicit and auditable.

**Idempotency as a pre-service layer.** The idempotency check runs before the service layer is invoked. This means the service layer never needs to know whether a request is a retry — it always executes as if it is the first time. The idempotency layer stores the serialized HTTP response body, not a domain object, so replay is exact.

**ConcurrencyStrategy as a seam.** The strategy interface is the only place where locking behavior differs between optimistic and pessimistic modes. Everything above and below it is identical. This makes the benchmark comparison (SPEC 0008) a clean A/B test.

**SagaOrchestrator is in-process but DB-backed.** The orchestrator runs in the same JVM as the REST API. Its state is persisted to PostgreSQL before each step executes. On restart, `SagaRecoveryRunner` (an `ApplicationRunner`) queries for in-progress sagas and resumes or compensates them before the HTTP server begins accepting requests.

---

## 3. Data Flow

### 3.1 Happy Path: Single Transfer (New Idempotency Key)

```
Client
  │
  │  POST /transfers
  │  Idempotency-Key: key-abc
  │  Body: { from: acct-1, to: acct-2, amount: 1000 }
  │
  ▼
SecurityFilter
  │  Validates API key / JWT
  │  401 if invalid
  ▼
IdempotencyFilter
  │  SELECT * FROM idempotency_keys WHERE key = 'key-abc' FOR UPDATE
  │  → not found
  │  INSERT INTO idempotency_keys (key, fingerprint, status='IN_PROGRESS')
  │    (unique constraint prevents concurrent duplicate from proceeding)
  ▼
TransferController.createTransfer()
  │  Validates request body (@Valid)
  │  Calls TransferService.execute(request)
  ▼
TransferService.execute()  ← @Transactional (READ COMMITTED or SERIALIZABLE per config)
  │
  ├─ AccountRepository.findById(acct-1)   [via ConcurrencyStrategy]
  ├─ AccountRepository.findById(acct-2)   [via ConcurrencyStrategy]
  │
  │  [Optimistic path]                    [Pessimistic path]
  │  Plain SELECT                         SELECT ... FOR UPDATE
  │  (lock acquired at UPDATE time)       (lock acquired here)
  │
  ├─ Validate: acct-1 balance - 1000 >= min_balance(acct-1)
  │  → if not: throw InsufficientFundsException (no entries written)
  │
  ├─ INSERT INTO transfers (id, idempotency_key, status='PENDING')
  │
  ├─ INSERT INTO ledger_entries (transfer_id, acct-1, DEBIT,  1000)
  ├─ INSERT INTO ledger_entries (transfer_id, acct-2, CREDIT, 1000)
  │  (Σ = DEBIT 1000 + CREDIT -1000 = 0 ✓)
  │
  ├─ [Optimistic] UPDATE accounts SET balance=balance-1000, version=version+1
  │               WHERE id=acct-1 AND version=<read_version>
  │               → 0 rows updated: throw OptimisticLockException → retry
  │
  ├─ [Pessimistic] UPDATE accounts SET balance=balance-1000 WHERE id=acct-1
  │                UPDATE accounts SET balance=balance+1000 WHERE id=acct-2
  │
  ├─ UPDATE transfers SET status='COMPLETED'
  │
  └─ COMMIT
       │
       ▼
IdempotencyFilter (post-execution)
  │  UPDATE idempotency_keys
  │    SET status='COMPLETED', response_snapshot=<serialized response>
  │    WHERE key='key-abc'
  ▼
TransferController
  │  Returns HTTP 201 + TransferResponse
  ▼
Client receives response
```

### 3.2 Idempotent Retry (Key Already Seen)

```
Client
  │  POST /transfers
  │  Idempotency-Key: key-abc   ← same key, same or different timing
  ▼
IdempotencyFilter
  │  SELECT * FROM idempotency_keys WHERE key = 'key-abc'
  │  → found, status='COMPLETED', response_snapshot=<stored>
  │
  │  Fingerprint check:
  │    request body hash == stored fingerprint?
  │    → YES: return stored response immediately (HTTP 200 + stored body)
  │    → NO:  return HTTP 422 (key reuse with different body)
  │
  │  TransferService is NEVER called
  ▼
Client receives identical response to original request
```

### 3.3 Concurrent Duplicate Race (Two Identical Requests Simultaneously)

```
Request A                              Request B
    │                                      │
    ▼                                      ▼
IdempotencyFilter                     IdempotencyFilter
    │                                      │
    │  BEGIN TX                            │  BEGIN TX
    │  SELECT ... WHERE key='key-abc'      │  SELECT ... WHERE key='key-abc'
    │  → not found                         │  → not found
    │                                      │
    │  INSERT INTO idempotency_keys        │  INSERT INTO idempotency_keys
    │    (key='key-abc', status=           │    (key='key-abc', ...)
    │     'IN_PROGRESS')                   │
    │  → succeeds                          │  → UNIQUE CONSTRAINT VIOLATION
    │                                      │     (PostgreSQL serializes this)
    │  COMMIT                              │
    │                                      │  Catches UniqueConstraintException
    │                                      │  SELECT ... WHERE key='key-abc'
    │                                      │  → found (A's record)
    │                                      │  status='IN_PROGRESS':
    │                                      │    wait + retry SELECT (poll)
    │                                      │  status='COMPLETED':
    │                                      │    return stored response
    ▼                                      ▼
Request A executes transfer          Request B returns A's result
(exactly once)                       (no transfer executed)
```

### 3.4 Saga Flow: Multi-Step Transfer (A → B → C)

```
POST /transfers/saga
  │
  ▼
SagaOrchestrator.execute(SagaDefinition)
  │
  ├─ INSERT INTO sagas (id, type, state='STARTED', current_step=0, payload)
  │  COMMIT  ← state persisted before any step runs
  │
  ├─ Step 1: DebitAccountA
  │    INSERT INTO saga_steps (saga_id, step=1, state='IN_PROGRESS')
  │    COMMIT
  │    execute DebitAccountA.forward()
  │      → INSERT ledger_entry (acct-A, DEBIT, amount)
  │      → UPDATE accounts ...
  │    UPDATE saga_steps SET state='COMPLETED'
  │    UPDATE sagas SET current_step=1
  │    COMMIT
  │
  ├─ Step 2: CreditAccountB
  │    [same pattern: persist IN_PROGRESS, execute, persist COMPLETED]
  │
  ├─ Step 3: CreditAccountC
  │    [same pattern]
  │    UPDATE sagas SET state='COMPLETED'
  │    COMMIT
  │
  └─ Return success

On crash at Step 2 (after Step 1 COMMITTED, before Step 2 COMMITTED):
  │
  ▼
SagaRecoveryRunner (on restart, before HTTP server starts)
  │
  ├─ SELECT * FROM sagas WHERE state NOT IN ('COMPLETED', 'COMPENSATED')
  │  → finds saga in state='STARTED', current_step=1
  │
  ├─ Determines: Step 1 completed, Step 2 not started
  │
  ├─ Compensation: run Step 1 compensate() in reverse
  │    INSERT ledger_entry (acct-A, CREDIT, amount)  ← reversal entry
  │    UPDATE accounts ...
  │    UPDATE sagas SET state='COMPENSATED'
  │    COMMIT
  │
  └─ Invariant: Σ(all entries) = 0 ✓
```

### 3.5 Reconciliation Flow

```
ReconciliationService (scheduled, e.g. every 60 seconds)
  │
  ├─ Query 1: SELECT SUM(
  │              CASE WHEN direction='DEBIT'  THEN -amount_minor
  │                   WHEN direction='CREDIT' THEN  amount_minor
  │              END
  │            ) FROM ledger_entries
  │            → must equal 0
  │
  ├─ Query 2: SELECT account_id,
  │                  SUM(CASE WHEN direction='DEBIT'  THEN -amount_minor
  │                           WHEN direction='CREDIT' THEN  amount_minor
  │                      END) AS entry_sum,
  │                  a.balance AS materialized_balance
  │           FROM ledger_entries le
  │           JOIN accounts a ON a.id = le.account_id
  │           GROUP BY account_id, a.balance
  │           HAVING SUM(...) != a.balance
  │           → must return 0 rows
  │
  ├─ If any discrepancy:
  │    LOG.error("RECONCILIATION DRIFT DETECTED", details)
  │    metrics.counter("reconciliation.drift").increment()
  │    store in reconciliation_report table
  │
  └─ GET /reconciliation/report returns latest report
```

---

## 4. Service Boundaries

This is a single-service architecture. There are no microservices, no inter-process communication, and no message passing between components. The boundaries described here are **internal module boundaries** enforced by package structure and access modifiers, not network boundaries.

### 4.1 Internal Module Boundaries

```
┌─────────────────────────────────────────────────────────────┐
│  Module: api                                                │
│  Public surface: REST endpoints only                        │
│  May call: idempotency, transfer, account, reconciliation   │
│  Must NOT call: db.generated directly, concurrency          │
└─────────────────────────────────────────────────────────────┘
         │
┌────────▼────────────────────────────────────────────────────┐
│  Module: idempotency                                        │
│  Public surface: IdempotencyFilter, IdempotencyService      │
│  May call: db (IdempotencyRepository only)                  │
│  Must NOT call: transfer service (to avoid circular)        │
└─────────────────────────────────────────────────────────────┘
         │
┌────────▼────────────────────────────────────────────────────┐
│  Module: transfer                                           │
│  Public surface: TransferService                            │
│  Owns: the @Transactional boundary for all transfers        │
│  May call: account, concurrency, db                         │
│  Must NOT call: api, idempotency                            │
└─────────────────────────────────────────────────────────────┘
         │
┌────────▼────────────────────────────────────────────────────┐
│  Module: concurrency                                        │
│  Public surface: ConcurrencyStrategy interface              │
│  Implementations: OptimisticStrategy, PessimisticStrategy   │
│  May call: db (AccountRepository only)                      │
│  Must NOT call: transfer, saga, idempotency                 │
└─────────────────────────────────────────────────────────────┘
         │
┌────────▼────────────────────────────────────────────────────┐
│  Module: db                                                 │
│  Public surface: Repository interfaces                      │
│  Contains: jOOQ DSL calls, generated code (read-only)       │
│  Must NOT contain: business logic, transaction management   │
│  Must NOT issue: UPDATE/DELETE against ledger_entries        │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 The One External Boundary: PostgreSQL

The only external system boundary is the JDBC connection to PostgreSQL. All correctness guarantees depend on PostgreSQL's ACID semantics. The application makes the following explicit assumptions about this boundary:

- `fsync = on` (default PostgreSQL; never disabled)
- `synchronous_commit = on` (default; never set to `off` or `local`)
- Isolation level is set explicitly per transaction via jOOQ's `configuration().set(TransactionIsolationLevel.X)` — never left to the connection pool default
- The connection pool (HikariCP) does not silently retry failed transactions

### 4.3 The Prometheus Boundary

Micrometer exposes metrics at `/actuator/prometheus`. This is a read-only, pull-based boundary. The Prometheus scraper is external to the system and has no write access. This boundary has no correctness implications.

---

## 5. Database Schema

The schema is the contract between all components. It is defined here authoritatively; Flyway migrations implement it exactly.

### 5.1 Tables

```sql
-- Flyway migration: V1__initial_schema.sql

CREATE TABLE accounts (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    currency        CHAR(3)     NOT NULL DEFAULT 'USD',
    min_balance     BIGINT      NOT NULL DEFAULT 0,
    balance         BIGINT      NOT NULL DEFAULT 0,
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT accounts_balance_gte_min
        CHECK (balance >= min_balance),
    CONSTRAINT accounts_amount_positive
        CHECK (min_balance >= 0)
);

CREATE TABLE transfers (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(255) UNIQUE,
    status          VARCHAR(50) NOT NULL,   -- PENDING, COMPLETED, FAILED
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT transfers_status_valid
        CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

CREATE TABLE ledger_entries (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id     UUID        NOT NULL REFERENCES transfers(id),
    account_id      UUID        NOT NULL REFERENCES accounts(id),
    direction       VARCHAR(6)  NOT NULL,   -- DEBIT or CREDIT
    amount_minor    BIGINT      NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ledger_entries_direction_valid
        CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ledger_entries_amount_positive
        CHECK (amount_minor > 0)

    -- NO UPDATE, NO DELETE — enforced by application + hook
);

CREATE TABLE idempotency_keys (
    key                 VARCHAR(255) PRIMARY KEY,
    request_fingerprint VARCHAR(64)  NOT NULL,   -- SHA-256 of request body
    response_snapshot   TEXT,                    -- serialized JSON response
    status              VARCHAR(20)  NOT NULL,   -- IN_PROGRESS, COMPLETED, FAILED
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT idempotency_keys_status_valid
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED'))
);

CREATE TABLE sagas (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    type            VARCHAR(100) NOT NULL,
    state           VARCHAR(20)  NOT NULL,   -- STARTED, COMPLETED, COMPENSATING, COMPENSATED, FAILED
    current_step    INT          NOT NULL DEFAULT 0,
    payload         JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT sagas_state_valid
        CHECK (state IN ('STARTED', 'COMPLETED', 'COMPENSATING', 'COMPENSATED', 'FAILED'))
);

CREATE TABLE saga_steps (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    saga_id         UUID        NOT NULL REFERENCES sagas(id),
    step_index      INT         NOT NULL,
    step_type       VARCHAR(100) NOT NULL,
    state           VARCHAR(20)  NOT NULL,   -- PENDING, IN_PROGRESS, COMPLETED, COMPENSATED
    forward_result  JSONB,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT saga_steps_state_valid
        CHECK (state IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'COMPENSATED')),
    UNIQUE (saga_id, step_index)
);

CREATE TABLE reconciliation_reports (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    run_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    global_sum          BIGINT      NOT NULL,   -- must be 0
    drift_detected      BOOLEAN     NOT NULL,
    drift_details       JSONB,
    accounts_checked    INT         NOT NULL,
    accounts_drifted    INT         NOT NULL DEFAULT 0
);
```

### 5.2 Indexes

```sql
-- Flyway migration: V2__indexes.sql

-- Hot path: balance queries and locking
CREATE INDEX idx_ledger_entries_account_id
    ON ledger_entries (account_id);

CREATE INDEX idx_ledger_entries_transfer_id
    ON ledger_entries (transfer_id);

-- Idempotency lookup (key is already PRIMARY KEY, no additional index needed)

-- Saga recovery on restart
CREATE INDEX idx_sagas_state
    ON sagas (state)
    WHERE state NOT IN ('COMPLETED', 'COMPENSATED');

-- Transfer lookup
CREATE INDEX idx_transfers_idempotency_key
    ON transfers (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- Reconciliation aggregate (covering index for the sum query)
CREATE INDEX idx_ledger_entries_reconciliation
    ON ledger_entries (account_id, direction, amount_minor);
```

### 5.3 Isolation Level Decision

**Chosen isolation level: `READ COMMITTED` (default PostgreSQL) for the transfer execution path, with explicit `SELECT … FOR UPDATE` (pessimistic) or version-check `UPDATE … WHERE version = ?` (optimistic) to prevent lost updates.**

This is documented in `docs/adr/0001-isolation-level.md`. The reasoning:

- `READ COMMITTED` does not prevent lost updates on its own — that is why explicit locking is mandatory and is the entire point of the ConcurrencyStrategy layer.
- `SERIALIZABLE` prevents write skew and phantoms but at a throughput cost that is measured in SPEC 0008. It is available as a configuration option (`ISOLATION_LEVEL=serializable`) for the benchmark comparison.
- The combination of `READ COMMITTED` + `SELECT … FOR UPDATE` (pessimistic) or `READ COMMITTED` + optimistic version check provides the same lost-update protection as `REPEATABLE READ` for the specific access pattern used here (read-then-write on a known row set), without the overhead of full serializable snapshot isolation.

---

## 6. Deployment Architecture

### 6.1 Local Development

```
Developer machine
│
├── make run
│     └── java -jar target/ledger.jar
│           └── connects to: localhost:5432/ledger
│                 └── started by: docker compose up postgres
│
├── make test
│     └── mvn test
│           └── Testcontainers spins up postgres:16 container per suite
│                 (isolated, no shared state with dev DB)
│
├── make concurrency-test
│     └── mvn test -Pconc-test
│           └── Testcontainers postgres:16
│                 └── ConcurrencyTestHarness fires 10,000 transfers
│
└── make bench
      └── mvn jmh:run
            └── Testcontainers postgres:16
```

### 6.2 Docker Compose (Primary Deliverable)

```
docker-compose.yml
│
├── service: postgres
│     image: postgres:16-alpine
│     environment:
│       POSTGRES_DB: ledger
│       POSTGRES_USER: ledger
│       POSTGRES_PASSWORD: ledger
│     volumes:
│       - postgres_data:/var/lib/postgresql/data
│     healthcheck:
│       test: pg_isready -U ledger
│     ports: 5432:5432
│
└── service: app
      build: .  (multi-stage Dockerfile)
      depends_on:
        postgres: { condition: service_healthy }
      environment:
        SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/ledger
        SPRING_DATASOURCE_USERNAME: ledger
        SPRING_DATASOURCE_PASSWORD: ledger
        CONCURRENCY_STRATEGY: pessimistic
        ISOLATION_LEVEL: read_committed
        API_KEY: <dev-default>
      ports: 8080:8080
      command: ["java", "-jar", "/app/ledger.jar"]

volumes:
  postgres_data:
```

### 6.3 Dockerfile (Multi-Stage)

```dockerfile
# Stage 1: Build
FROM eclipse-temurin:21-jdk