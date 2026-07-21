# ADR 0006 — Optimistic vs. pessimistic locking for the transfer path

Date: 2026-07-21
Status: accepted
Deciders: Project owner
Relates to: SPEC 0004, FR-19, FR-20, FR-21, FR-22, FR-23, NFR-3, NFR-14

## Context

`docs/adr/0001-isolation-level.md` settled the isolation level (READ COMMITTED, since it does not
by itself prevent lost updates) and committed this project to **explicit locking** at the call
site. It named two candidate locking disciplines — a version compare-and-set ("optimistic") and
`SELECT ... FOR UPDATE` ("pessimistic") — but deliberately left choosing between them to this ADR,
because the honest answer depends on contention profile and is exactly what SPEC 0008's benchmark
exists to measure. Building only one would turn that benchmark into a comparison against nothing.

ADR 0001's anomaly table (dirty read / non-repeatable read / phantom read / lost update / write
skew, by isolation level) is not repeated here — both strategies run under the same isolation level
and inherit the same anomaly profile from it; locking strategy does not change which anomalies READ
COMMITTED allows, it changes how the *lost update* anomaly specifically is prevented.

## Options considered

### Option A — Optimistic only (version compare-and-set)
- **Pros:** No row lock held across the write; readers of an account row never block on a
  transfer in flight. Cheapest under low contention.
- **Cons:** Under high contention on the same account (the project's own headline scenario — hot
  accounts, 10,000 concurrent transfers) most attempts lose the compare-and-set and must retry,
  wasting the work already done reconstructing the transaction. Retry is mandatory, not optional,
  so its cost and bound must be designed in from the start, not bolted on later.

### Option B — Pessimistic only (`SELECT ... FOR UPDATE`)
- **Pros:** Contending transactions serialize on the row lock instead of racing and failing; no
  retry loop needed for the lock itself. Deterministic under hot-account contention.
- **Cons:** Holds a lock for the duration of the whole transaction (both balance writes, the
  ledger-entry insert, the transfer row) — throughput is bounded by how long that transaction runs,
  and a slow transaction blocks every other transfer touching the same account.

### Option C — SERIALIZABLE isolation alone, no explicit locking
- Rejected for the same reason ADR 0001 rejected it: pays for write-skew protection this access
  pattern does not need (the only rows read are the only rows written), and still requires a retry
  loop for serialization failures — so the "simpler, no locking code" appeal is not real.

### Option D — Both, behind one seam, chosen at startup (chosen)
- **Pros:** Turns "which is better?" into a question SPEC 0008 answers with a benchmark under this
  project's actual contention profile, instead of a guess baked into the code. Both share the same
  lock-ordering discipline and the same retry-loop shape, so the comparison varies only the one
  thing that differs.
- **Cons:** Two implementations to maintain instead of one; the seam (`ConcurrencyStrategy`) has to
  be strict enough that neither implementation can accidentally skip locking.

## Decision

Both strategies are implemented behind `org.ledger.concurrency.ConcurrencyStrategy`, selected at
startup by `ledger.concurrency-strategy` (`optimistic` | `pessimistic`), with no runtime fallback:
an unrecognized value fails application startup rather than silently defaulting to a racy path.
SPEC 0008 benchmarks both under identical load and its numbers — not taste — pick the production
default.

**Lock ordering is the deadlock argument, in both strategies.** Two concurrent transfers A→B and
B→A deadlock if each strategy locks its own "from" row first. Both `lockAndLoad` implementations
issue a single `SELECT ... WHERE id IN (?,?) ORDER BY id [FOR UPDATE]` — one statement, ascending id
order — so the order is structural: a caller cannot pass the ids in the "wrong" order and get a
different lock sequence, because the statement itself re-sorts them. `DeadlockIT` proves this by
running A→B and B→A concurrently under a hard timeout.

**Retry sits outside the transaction, not inside it.** `TransferService.execute()` is a plain loop
around a single `TransactionTemplate.execute(...)` call; each attempt is one all-or-nothing
transaction (the transfer insert, both ledger entries, both balance updates, or none of them —
`OptimisticRetryIT` asserts the rollback leaves nothing behind). A prior attempt's partial work
never leaks into the next attempt because each attempt is a fresh transaction. This was chosen over
an AOP-based retry aspect (planning/03 §2.1's sketch) because the project has no
`spring-boot-starter-aop` dependency and `@Transactional(isolation = ...)` cannot be made
runtime-configurable regardless — a plain retry loop around `TransactionTemplate` gets both
properties (retry outside the transaction, isolation chosen per-transaction from config) from one
construct, visible at the call site, which is exactly the transparency ADR 0001 committed to.

**Both strategies retry on serialization failures, not just optimistic conflicts.** Under
`ledger.isolation-level=serializable`, PostgreSQL can raise SQLSTATE `40001` (serialization failure)
or `40P01` (deadlock detected) against either strategy. The retry loop's catch clause covers
`ConcurrencyConflictException` (the optimistic strategy's own signal) and any exception whose
unwrapped `SQLException.getSQLState()` is `40001` or `40P01`. Without the latter, running under
`serializable` would surface as intermittent `500`s instead of absorbed retries, and SPEC 0008 would
have nothing meaningful to benchmark at that isolation level.

## Consequences

**Positive:**
- Lost updates are prevented under both strategies, by construction, on the only path that writes
  account balances.
- The optimistic-vs-pessimistic question is answerable with SPEC 0008's data instead of asserted by
  fiat.
- `ArchitectureFitnessTest` mechanically enforces that `concurrency` cannot reach into `transfer`,
  `saga`, `idempotency`, or `api`, and that `api` cannot reach into `concurrency` — the seam is a
  real module boundary, not just an interface.

**Negative / accepted costs:**
- Two code paths to keep correct instead of one; a bug specific to one strategy can hide until that
  strategy is selected in some environment.
- Optimistic mode's retry loop makes latency under hot-account contention less predictable than
  pessimistic mode's (blocking is at least bounded by transaction duration, not by an attempt
  budget). SPEC 0008 quantifies this rather than leaving it as an assertion.
- **The 409/idempotency interaction is a real, accepted tension, not an oversight.**
  `IdempotencyFilter` commits its claim before the controller runs and marks the key `FAILED` only
  on a thrown exception or a `5xx` status (SPEC 0003). `409 CONFLICT_RETRY_EXHAUSTED` is a `4xx`, so
  it is snapshotted and the key is marked `COMPLETED` — a client retrying with the *same*
  `Idempotency-Key` gets the same `409` replayed verbatim, never a fresh attempt. This only matches
  planning/05-api-design.md §3.6's "409 is a transient conflict, retry" guidance if the retry uses a
  *new* idempotency key. SPEC 0004 does not reopen SPEC 0003's 4xx→`COMPLETED` decision; if the
  409-should-be-retryable-with-the-same-key reading is preferred, that is a follow-up spec's problem,
  not this one's.

**What would change our mind:**
- SPEC 0008 showing one strategy dominates the other across this project's realistic contention
  profile (hot accounts, ~30% duplicate load) with no scenario where the other wins — at that point
  maintaining both stops paying for itself and the losing strategy can be retired.
- SPEC 0008 showing `SERIALIZABLE`'s cost over `READ COMMITTED` + locking is negligible under this
  workload, which would revisit ADR 0001's isolation choice, not this one.
