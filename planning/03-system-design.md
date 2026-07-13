# system-design.md — Transactional Payments Ledger

Version: 1.0 | Derived from: architecture.md v1.0 | Status: Authoritative

---

## Table of Contents

1. [Modules](#1-modules)
2. [Services](#2-services)
3. [Internal Workflows](#3-internal-workflows)
4. [Event Flows](#4-event-flows)
5. [State Transitions](#5-state-transitions)
6. [Design Patterns](#6-design-patterns)
7. [Integration Points](#7-integration-points)

---

## 1. Modules

The application is organized into six packages, each mapping to one architectural layer. Package boundaries are enforced by access modifiers and architectural fitness functions in the test suite (`ArchUnit` rules). No class in a lower-numbered layer may import from a higher-numbered layer; no class may skip a layer except where explicitly noted.

### 1.1 Module Map

```
org.ledger
├── api                          Layer 1 — HTTP surface
│   ├── controller
│   │   ├── TransferController
│   │   ├── AccountController
│   │   ├── ReconciliationController
│   │   └── HealthController
│   ├── dto
│   │   ├── CreateTransferRequest
│   │   ├── TransferResponse
│   │   ├── CreateAccountRequest
│   │   ├── AccountResponse
│   │   ├── BalanceResponse
│   │   ├── SagaTransferRequest
│   │   ├── ReconciliationReportResponse
│   │   └── ErrorResponse
│   ├── validation
│   │   ├── CurrencyCodeValidator
│   │   ├── PositiveAmountValidator
│   │   └── SameAccountValidator
│   └── exception
│       └── GlobalExceptionHandler
│
├── idempotency                  Layer 2 — Idempotency gate
│   ├── IdempotencyFilter
│   ├── IdempotencyService
│   ├── FingerprintService
│   └── IdempotencyStatus        (enum: IN_PROGRESS, COMPLETED, FAILED)
│
├── transfer                     Layer 3a — Transfer domain
│   ├── TransferService
│   └── TransferStatus           (enum: PENDING, COMPLETED, FAILED)
│
├── account                      Layer 3b — Account domain
│   └── AccountService
│
├── saga                         Layer 3c — Saga orchestration
│   ├── SagaOrchestrator
│   ├── SagaDefinition           (value object: ordered list of SagaStep)
│   ├── SagaContext              (mutable execution context passed to steps)
│   ├── SagaStep                 (interface: forward(), compensate())
│   ├── SagaState                (enum: STARTED, COMPLETED, COMPENSATING,
│   │                                   COMPENSATED, FAILED)
│   └── steps
│       ├── DebitAccountStep
│       ├── CreditAccountStep
│       └── RecordTransferStep
│
├── reconciliation               Layer 3d — Invariant verification
│   └── ReconciliationService
│
├── concurrency                  Layer 4 — Locking strategy
│   ├── ConcurrencyStrategy      (interface)
│   ├── OptimisticStrategy
│   ├── PessimisticStrategy
│   └── ConcurrencyStrategyFactory
│
├── db                           Layer 5 — Data access
│   ├── AccountRepository
│   ├── LedgerEntryRepository
│   ├── TransferRepository
│   ├── IdempotencyRepository
│   ├── SagaRepository
│   ├── ReconciliationRepository
│   └── generated                (jOOQ codegen output — do not edit)
│       ├── tables
│       │   ├── Accounts
│       │   ├── LedgerEntries
│       │   ├── Transfers
│       │   ├── IdempotencyKeys
│       │   ├── Sagas
│       │   ├── SagaSteps
│       │   └── ReconciliationReports
│       └── tables.records
│           ├── AccountsRecord
│           ├── LedgerEntriesRecord
│           ├── TransfersRecord
│           ├── IdempotencyKeysRecord
│           ├── SagasRecord
│           ├── SagaStepsRecord
│           └── ReconciliationReportsRecord
│
└── infrastructure               Layer 6 — Spring wiring
    ├── DataSourceConfig
    ├── TransactionConfig
    ├── SecurityConfig
    ├── MetricsConfig
    └── SagaRecoveryRunner
```

### 1.2 Module Dependency Rules

The following table is the authoritative dependency contract. ArchUnit tests enforce it on every build.

| Module | May Import | Must Not Import |
|---|---|---|
| `api` | `idempotency`, `transfer`, `account`, `saga`, `reconciliation` | `db.generated` directly, `concurrency` |
| `idempotency` | `db` (IdempotencyRepository only) | `transfer`, `saga`, `account`, `reconciliation` |
| `transfer` | `account`, `concurrency`, `db` | `api`, `idempotency`, `saga` |
| `account` | `db` | `api`, `idempotency`, `transfer`, `saga`, `concurrency` |
| `saga` | `transfer`, `account`, `db` | `api`, `idempotency`, `concurrency` directly |
| `reconciliation` | `db` (ReconciliationRepository only) | `api`, `idempotency`, `transfer`, `saga`, `concurrency` |
| `concurrency` | `db` (AccountRepository only) | `transfer`, `saga`, `idempotency`, `api` |
| `db` | `db.generated` | All domain modules; no business logic |
| `infrastructure` | All modules (wiring only) | None (it is the composition root) |

### 1.3 Module Responsibilities in Detail

#### `org.ledger.api`

Owns the HTTP surface exclusively. Controllers translate HTTP verbs and request bodies into domain calls and translate domain results or exceptions into HTTP responses. Controllers hold no business logic. The `GlobalExceptionHandler` (`@RestControllerAdvice`) maps every domain exception to a structured `ErrorResponse` with a stable HTTP status code:

| Exception | HTTP Status | Error Code |
|---|---|---|
| `InsufficientFundsException` | 422 | `INSUFFICIENT_FUNDS` |
| `AccountNotFoundException` | 404 | `ACCOUNT_NOT_FOUND` |
| `OptimisticLockException` (exhausted) | 409 | `CONFLICT_RETRY_EXHAUSTED` |
| `IdempotencyFingerprintMismatch` | 422 | `IDEMPOTENCY_KEY_REUSE` |
| `SagaCompensatedException` | 422 | `SAGA_COMPENSATED` |
| `ConstraintViolationException` | 400 | `VALIDATION_ERROR` |
| All others | 500 | `INTERNAL_ERROR` |

#### `org.ledger.idempotency`

Owns the idempotency gate. `IdempotencyFilter` is a Spring `OncePerRequestFilter` that intercepts all `POST`, `PUT`, and `PATCH` requests. `FingerprintService` computes a SHA-256 hash of the raw request body bytes before the body is consumed, storing the hash for later comparison. `IdempotencyService` encapsulates all reads and writes to the `idempotency_keys` table and owns the polling loop for the `IN_PROGRESS` race case.

#### `org.ledger.transfer`

Owns the atomic transfer execution path. `TransferService` is the sole class annotated `@Transactional` on the write path. It coordinates `ConcurrencyStrategy`, `LedgerEntryRepository`, `TransferRepository`, and `AccountRepository` within a single database transaction. It enforces the zero-sum invariant by construction: it always inserts exactly one DEBIT entry and one CREDIT entry of equal `amount_minor` per transfer.

#### `org.ledger.account`

Owns account lifecycle (create, read, close). `AccountService` delegates all persistence to `AccountRepository`. Balance reads are non-transactional by default (acceptable for display; the authoritative balance is always derived from ledger entries during reconciliation).

#### `org.ledger.saga`

Owns multi-step transfer orchestration. `SagaOrchestrator` is the only class that calls `SagaRepository` for state persistence. `SagaStep` implementations (`DebitAccountStep`, `CreditAccountStep`, `RecordTransferStep`) each call `TransferService` or `AccountService` for their domain work — they do not touch the database directly.

#### `org.ledger.reconciliation`

Owns the scheduled invariant check. `ReconciliationService` is annotated `@Scheduled` and runs aggregate SQL queries through `ReconciliationRepository`. It writes results to `reconciliation_reports` and emits Micrometer metrics. It has no write access to `accounts` or `ledger_entries`.

#### `org.ledger.concurrency`

Owns the locking abstraction. `ConcurrencyStrategy` is a single-method interface. `ConcurrencyStrategyFactory` reads the `CONCURRENCY_STRATEGY` environment variable at startup and returns the appropriate singleton implementation. The active strategy is injected into `TransferService` by Spring.

#### `org.ledger.db`

Owns all jOOQ DSL construction. Repository classes accept a `DSLContext` (injected by Spring) and return domain-neutral jOOQ records or mapped POJOs. No repository method opens a transaction; transaction management is the exclusive responsibility of the service layer.

#### `org.ledger.infrastructure`

The Spring composition root. Contains only `@Configuration` and `@Bean` definitions. `SagaRecoveryRunner` implements `ApplicationRunner` and runs saga recovery before the embedded Tomcat server begins accepting connections.

---

## 2. Services

### 2.1 `TransferService`

**Package:** `org.ledger.transfer`
**Spring scope:** Singleton
**Transaction ownership:** Yes — `@Transactional` on `execute()`

```
TransferService
  ├── execute(CreateTransferRequest, UUID idempotencyKey) → TransferResponse
  └── getTransfer(UUID transferId) → TransferResponse
```

**Collaborators injected at construction:**

| Collaborator | Role |
|---|---|
| `ConcurrencyStrategy` | Acquires account records with appropriate locking |
| `AccountRepository` | Reads and updates account balances |
| `LedgerEntryRepository` | Inserts the balanced entry pair |
| `TransferRepository` | Creates and updates the transfer record |
| `MeterRegistry` | Records `transfer.executed`, `transfer.failed` counters |

**Invariant enforcement inside `execute()`:**

1. Resolve both accounts via `ConcurrencyStrategy.lockAndLoad()`.
2. Assert `fromAccount.balance - amount >= fromAccount.minBalance`. Throw `InsufficientFundsException` if not. No database writes have occurred at this point.
3. Insert `transfers` row with `status = PENDING`.
4. Insert `ledger_entries` DEBIT row: `(transferId, fromAccountId, DEBIT, amount)`.
5. Insert `ledger_entries` CREDIT row: `(transferId, toAccountId, CREDIT, amount)`.
6. Update `accounts` balances (strategy-specific; see Section 6.1).
7. Update `transfers` row to `status = COMPLETED`.
8. Commit. The zero-sum invariant holds because steps 4 and 5 always execute together inside the same transaction, and `ledger_entries` has no UPDATE or DELETE path.

**Retry loop (optimistic path only):**

`execute()` is wrapped by `OptimisticRetryAspect` (`@Around` advice on `@Transactional` methods in the `transfer` package). The aspect catches `OptimisticLockException`, decrements a retry counter (default: 5), and re-invokes the method. After exhaustion it throws `OptimisticLockException` to the caller, which `GlobalExceptionHandler` maps to HTTP 409.

### 2.2 `AccountService`

**Package:** `org.ledger.account`
**Spring scope:** Singleton
**Transaction ownership:** Read-only `@Transactional(readOnly=true)` on queries; `@Transactional` on mutations.

```
AccountService
  ├── createAccount(CreateAccountRequest) → AccountResponse
  ├── getAccount(UUID accountId) → AccountResponse
  ├── getBalance(UUID accountId) → BalanceResponse
  └── listAccounts(Pageable) → Page<AccountResponse>
```

`getBalance()` returns the materialized `balance` column from the `accounts` table. This is the fast path for display. The authoritative balance (sum of ledger entries) is computed only by `ReconciliationService`.

### 2.3 `SagaOrchestrator`

**Package:** `org.ledger.saga`
**Spring scope:** Singleton
**Transaction ownership:** No — each step's forward/compensate action is transactional within itself; the orchestrator coordinates between transactions.

```
SagaOrchestrator
  ├── execute(SagaDefinition) → SagaResult
  └── recover(SagasRecord) → void          (called by SagaRecoveryRunner)
```

The orchestrator does not hold a single transaction across all steps. Each step's execution is a separate committed transaction. This is the defining characteristic of the Saga pattern: atomicity is achieved through compensation, not through a distributed lock.

**`SagaDefinition`** is an immutable value object:

```java
record SagaDefinition(
    String type,
    List<SagaStep> steps,
    JsonNode payload
) {}
```

**`SagaStep`** is the interface each step implements:

```java
interface SagaStep {
    String stepType();
    JsonNode forward(SagaContext ctx) throws Exception;
    void compensate(SagaContext ctx, JsonNode forwardResult);
}
```

`SagaContext` carries the saga ID, the payload, and a reference to the `DSLContext` for steps that need to read state.

### 2.4 `ReconciliationService`

**Package:** `org.ledger.reconciliation`
**Spring scope:** Singleton
**Transaction ownership:** `@Transactional(readOnly=true)` on the check queries; `@Transactional` on the report insert.

```
ReconciliationService
  ├── runCheck() → ReconciliationReport    (called by @Scheduled and by REST)
  └── getLatestReport() → ReconciliationReportResponse
```

`runCheck()` is annotated `@Scheduled(fixedDelayString = "${ledger.reconciliation.interval-ms:60000}")`. The interval is configurable via environment variable. The method is also callable on demand via `GET /reconciliation/run` (admin-only endpoint).

### 2.5 `IdempotencyService`

**Package:** `org.ledger.idempotency`
**Spring scope:** Singleton
**Transaction ownership:** Yes — each idempotency operation is its own short transaction, separate from the transfer transaction.

```
IdempotencyService
  ├── checkOrReserve(String key, String fingerprint) → IdempotencyCheckResult
  ├── markCompleted(String key, String responseSnapshot) → void
  ├── markFailed(String key) → void
  └── pollUntilResolved(String key) → IdempotencyCheckResult
```

`IdempotencyCheckResult` is a sealed interface with three implementations: `NotFound` (proceed), `AlreadyCompleted(String responseSnapshot)` (replay), and `InProgress` (poll).

---

## 3. Internal Workflows

### 3.1 Single Transfer Execution Workflow

This is the most critical workflow. Every step is numbered for cross-reference with the state transition diagram in Section 5.

```
[1] HTTP POST /transfers arrives at Tomcat thread pool

[2] SecurityFilter.doFilter()
    ├── Extract Authorization header
    ├── Validate API key against SecurityConfig.validKeys (Set<String>)
    └── 401 Unauthorized if invalid → workflow terminates

[3] IdempotencyFilter.doFilter()
    ├── Extract Idempotency-Key header
    │   └── 400 Bad Request if absent on POST/PUT/PATCH
    ├── Compute fingerprint = FingerprintService.sha256(requestBodyBytes)
    ├── IdempotencyService.checkOrReserve(key, fingerprint)
    │   ├── BEGIN TX (READ COMMITTED, short)
    │   ├── SELECT ... FROM idempotency_keys WHERE key = ? FOR UPDATE
    │   │   ├── NOT FOUND:
    │   │   │   ├── INSERT (key, fingerprint, status='IN_PROGRESS')
    │   │   │   ├── COMMIT
    │   │   │   └── return NotFound → proceed to [4]
    │   │   ├── FOUND, status='COMPLETED':
    │   │   │   ├── fingerprint match? → return AlreadyCompleted(snapshot)
    │   │   │   └── fingerprint mismatch? → throw IdempotencyFingerprintMismatch
    │   │   ├── FOUND, status='FAILED':
    │   │   │   └── return NotFound (allow retry of failed request)
    │   │   └── FOUND, status='IN_PROGRESS':
    │   │       └── return InProgress → poll loop [3a]
    │   └── COMMIT
    │
    │   [3a] Poll loop (InProgress case):
    │        ├── sleep 50ms
    │        ├── SELECT status FROM idempotency_keys WHERE key = ?
    │        ├── COMPLETED → return AlreadyCompleted(snapshot)
    │        ├── FAILED → return NotFound (allow retry)
    │        ├── IN_PROGRESS → repeat (max 20 iterations = 1 second)
    │        └── timeout → return 503 Service Unavailable
    │
    └── AlreadyCompleted → write stored response to HttpServletResponse
        └── workflow terminates (TransferService never called)

[4] TransferController.createTransfer()
    ├── @Valid validation on CreateTransferRequest
    │   ├── fromAccountId: not null, valid UUID
    │   ├── toAccountId: not null, valid UUID, != fromAccountId
    │   ├── amount: > 0, <= Long.MAX_VALUE
    │   └── currency: 3-char ISO 4217 code
    └── Calls TransferService.execute(request)

[5] TransferService.execute()  ← @Transactional begins here
    │
    ├── [5a] ConcurrencyStrategy.lockAndLoad([fromId, toId], dslContext)
    │        ├── [Optimistic] SELECT id, balance, version, min_balance, currency
    │        │               FROM accounts WHERE id IN (?, ?)
    │        │               ORDER BY id   ← consistent ordering prevents deadlock
    │        └── [Pessimistic] SELECT id, balance, version, min_balance, currency
    │                          FROM accounts WHERE id IN (?, ?)
    │                          ORDER BY id
    │                          FOR UPDATE
    │
    ├── [5b] Validate currency match: fromAccount.currency == toAccount.currency
    │        └── throw CurrencyMismatchException if not
    │
    ├── [5c] Validate balance: fromAccount.balance - amount >= fromAccount.minBalance
    │        └── throw InsufficientFundsException if not
    │        (no DB writes have occurred; transaction rolls back cleanly)
    │
    ├── [5d] INSERT INTO transfers (id=newUUID, status='PENDING', ...)
    │
    ├── [5e] INSERT INTO ledger_entries (transferId, fromAccountId, 'DEBIT',  amount)
    ├── [5f] INSERT INTO ledger_entries (transferId, toAccountId,   'CREDIT', amount)
    │        ← Σ = 0 invariant established atomically
    │
    ├── [5g] UPDATE accounts:
    │        ├── [Optimistic]
    │        │   ├── UPDATE accounts SET balance = balance - amount,
    │        │   │                       version = version + 1,
    │        │   │                       updated_at = now()
    │        │   │   WHERE id = fromId AND version = <read_version>
    │        │   ├── rowsUpdated == 0 → throw OptimisticLockException
    │        │   ├── UPDATE accounts SET balance = balance + amount,
    │        │   │                       version = version + 1,
    │        │   │                       updated_at = now()
    │        │   │   WHERE id = toId AND version = <read_version>
    │        │   └── rowsUpdated == 0 → throw OptimisticLockException
    │        └── [Pessimistic]
    │            ├── UPDATE accounts SET balance = balance - amount,
    │            │                       updated_at = now()
    │            │   WHERE id = fromId
    │            └── UPDATE accounts SET balance = balance + amount,
    │                                    updated_at = now()
    │                WHERE id = toId
    │
    ├── [5h] UPDATE transfers SET status = 'COMPLETED', updated_at = now()
    │        WHERE id = transferId
    │
    └── [5i] COMMIT
             ├── accounts.balance CHECK constraint evaluated by PostgreSQL
             ├── ledger_entries immutability: no UPDATE/DELETE issued
             └── All FK constraints satisfied

[6] IdempotencyFilter (post-execution, in finally block)
    ├── Capture response body bytes from ContentCachingResponseWrapper
    ├── IdempotencyService.markCompleted(key, responseBodyJson)
    │   ├── BEGIN TX
    │   ├── UPDATE idempotency_keys
    │   │   SET status='COMPLETED', response_snapshot=?, updated_at=now()
    │   │   WHERE key=?
    │   └── COMMIT
    └── On exception from [5]: IdempotencyService.markFailed(key)

[7] TransferController returns HTTP 201 Created + TransferResponse body
```

### 3.2 Saga Execution Workflow

```
[1] HTTP POST /transfers/saga
    Body: { steps: [{type: DEBIT, accountId: A, amount: 500},
                    {type: CREDIT, accountId: B, amount: 300},
                    {type: CREDIT, accountId: C, amount: 200}] }

[2] TransferController.createSagaTransfer()
    └── Calls SagaOrchestrator.execute(sagaDefinition)

[3] SagaOrchestrator.execute()
    │
    ├── [3a] Persist saga record (own transaction):
    │        BEGIN TX
    │        INSERT INTO sagas (id, type, state='STARTED', current_step=0, payload)
    │        COMMIT
    │        ← crash here: saga never started; recovery is a no-op
    │
    ├── [3b] For each step i in [0..n-1]:
    │
    │   ├── Persist step as IN_PROGRESS (own transaction):
    │   │    BEGIN TX
    │   │    INSERT INTO saga_steps (saga_id, step_index=i, state='IN_PROGRESS')
    │   │    UPDATE sagas SET current_step=i, updated_at=now()
    │   │    COMMIT
    │   │    ← crash here: recovery sees step i IN_PROGRESS, compensates steps 0..i-1
    │   │
    │   ├── Execute step.forward(ctx):
    │   │    ← this calls TransferService or AccountService
    │   │    ← that call is its own @Transactional (READ COMMITTED)
    │   │    ← crash here: same as above (step i not committed)
    │   │
    │   ├── On success: persist step as COMPLETED (own transaction):
    │   │    BEGIN TX
    │   │    UPDATE saga_steps SET state='COMPLETED', forward_result=?, updated_at=now()
    │   │    COMMIT
    │   │    ← crash here: recovery sees step i COMPLETED, compensates steps 0..i
    │   │
    │   └── On exception from step.forward():
    │        ├── Log failure
    │        ├── UPDATE sagas SET state='COMPENSATING'
    │        └── Jump to compensation loop [3c]
    │
    ├── [3c] Compensation loop (reverse order, i from current_step-1 down to 0):
    │
    │   ├── For each completed step i:
    │   │    ├── Execute step.compensate(ctx, forwardResult)
    │   │    │    ← inserts a reversal ledger entry (CREDIT to undo DEBIT, etc.)
    │   │    │    ← own @Transactional
    │   │    └── UPDATE saga_steps SET state='COMPENSATED'
    │   │
    │   └── UPDATE sagas SET state='COMPENSATED'
    │        COMMIT
    │
    └── [3d] On full success:
             UPDATE sagas SET state='COMPLETED'
             COMMIT
             Return SagaResult to controller

[4] SagaRecoveryRunner.run() (on application startup, before HTTP server starts):
    │
    ├── SELECT * FROM sagas
    │   WHERE state NOT IN ('COMPLETED', 'COMPENSATED', 'FAILED')
    │
    └── For each in-progress saga:
         ├── Reconstruct SagaDefinition from sagas.type + sagas.payload
         ├── Load completed steps from saga_steps WHERE state='COMPLETED'
         ├── Determine: which steps completed, which did not
         └── Call SagaOrchestrator.recover(sagaRecord)
              ├── If current step was IN_PROGRESS (not COMPLETED):
              │    treat as not executed; compensate all COMPLETED steps
              └── If current step was COMPLETED:
                   compensate all COMPLETED steps including current
```

### 3.3 Reconciliation Workflow

```
[1] Trigger: @Scheduled fires every ledger.reconciliation.interval-ms (default 60s)
    OR: GET /reconciliation/run (admin endpoint)

[2] ReconciliationService.runCheck()
    │
    ├── [2a] Global sum check (READ COMMITTED, read-only TX):
    │        SELECT SUM(
    │            CASE WHEN direction='DEBIT'  THEN -amount_minor
    │                 WHEN direction='CREDIT' THEN  amount_minor
    │            END
    │        ) AS global_sum
    │        FROM ledger_entries
    │        → expected: 0
    │        → actual != 0: drift detected
    │
    ├── [2b] Per-account balance check (same TX):
    │        SELECT
    │            le.account_id,
    │            SUM(CASE WHEN le.direction='DEBIT'  THEN -le.amount_minor
    │                     WHEN le.direction='CREDIT' THEN  le.amount_minor
    │                END) AS entry_sum,
    │            a.balance AS materialized_balance,
    │            a.balance - SUM(...) AS drift
    │        FROM ledger_entries le
    │        JOIN accounts a ON a.id = le.account_id
    │        GROUP BY le.account_id, a.balance
    │        HAVING SUM(...) != a.balance
    │        → expected: 0 rows
    │        → any rows returned: per-account drift detected
    │
    ├── [2c] Build ReconciliationReport:
    │        globalSum, driftDetected, driftDetails (JSONB),
    │        accountsChecked, accountsDrifted
    │
    ├── [2d] Persist report (own TX):
    │        INSERT INTO reconciliation_reports (...)
    │
    ├── [2e] Emit metrics:
    │        meterRegistry.gauge("reconciliation.global_sum", globalSum)
    │        meterRegistry.counter("reconciliation.drift.count")
    │            .increment(accountsDrifted)
    │        meterRegistry.counter("reconciliation.runs.total").increment()
    │
    └── [2f] If drift detected:
             LOG.error("RECONCILIATION_DRIFT", kv("globalSum", globalSum),
                       kv("accountsDrifted", accountsDrifted),
                       kv("details", driftDetails))
             ← structured log; no automated correction
             ← human operator must investigate
```

### 3.4 Account Creation Workflow

```
[1] HTTP POST /accounts
    Body: { name: "Alice", currency: "USD", minBalance: 0 }

[2] SecurityFilter validates API key

[3] IdempotencyFilter reserves key (same as transfer path)

[4] AccountController.createAccount()
    └── @Valid: name not blank, currency valid ISO 4217, minBalance >= 0

[5] AccountService.createAccount()  ← @Transactional
    ├── INSERT INTO accounts (id=newUUID, name, currency, min_balance,
    │                         balance=0, version=0)
    └── COMMIT

[6] IdempotencyFilter marks COMPLETED

[7] HTTP 201 Created + AccountResponse
```

### 3.5 Optimistic Lock Retry Workflow

```
[1] TransferService.execute() called (attempt 1 of 5)

[2] OptimisticStrategy.lockAndLoad() → plain SELECT, reads version=7

[3] Ledger entries inserted (steps 5d–5f above)

[4] UPDATE accounts SET balance=..., version=8 WHERE id=? AND version=7
    → 0 rows updated (another transaction committed version=8 first)

[5] OptimisticLockException thrown
    → @Transactional rolls back (ledger entries rolled back too)
    → OptimisticRetryAspect catches exception

[6] Retry counter: 4 remaining
    → Sleep: exponential backoff (50ms * 2^(attempt-1), max 800ms)
    → Re-invoke TransferService.execute() (attempt 2 of 5)

[7] On attempt 2: reads version=8, UPDATE WHERE version=8 succeeds
    → COMMIT
    → Return success

[8] If all 5 attempts fail:
    → OptimisticLockException propagates to GlobalExceptionHandler
    → HTTP 409 Conflict
    → IdempotencyFilter marks key as FAILED
    → Client may retry with same idempotency key
```

---

## 4. Event Flows

This system has no message broker and no asynchronous event bus. "Events" in this section refer to in-process control flow transitions and the observable side effects they produce (metrics, logs, database state changes). They are documented here to make the system's observable behavior explicit.

### 4.1 Transfer Lifecycle Events

Each event is a named moment in the transfer workflow with defined producers and consumers.

```
Event: TRANSFER_INITIATED
  Trigger:    TransferService.execute() begins (transaction open)
  Producer:   TransferService
  Consumers:  MeterRegistry (counter: transfer.initiated)
              StructuredLogger (INFO: transfer_id, from, to, amount)

Event: TRANSFER_ENTRIES_WRITTEN
  Trigger:    Both ledger_entries rows inserted (pre-commit)
  Producer:   LedgerEntryRepository (called by TransferService)
  Consumers:  None (