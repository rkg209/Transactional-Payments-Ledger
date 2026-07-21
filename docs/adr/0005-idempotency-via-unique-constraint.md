# ADR 0005 — Idempotency via a unique-constraint claim, not a lock

Date: 2026-07-21
Status: accepted
Deciders: Project owner
Relates to: SPEC 0003, FR-14, FR-15, FR-16, FR-17, FR-18, NFR-5

## Context

Every mutating endpoint must apply exactly once even when the caller retries with the same
`Idempotency-Key` — including two identical requests racing each other, which is the scenario the
project's headline claim (10,000 concurrent transfers, ~30% duplicates, 0 double-charges) is built
to survive. The mechanism has to decide, for two requests that arrive concurrently with the same
key, which one executes and which one waits — without a window in which both could slip through.

## Options considered

### Option A — `SELECT ... FOR UPDATE` on the key, then `INSERT` if absent
- **Pros:** Reads naturally as "check, then act."
- **Cons:** This is the classic idempotency bug. `SELECT ... FOR UPDATE` locks *existing* rows; a
  key that has never been seen has no row to lock, so two concurrent first-requests both pass the
  check and both attempt the `INSERT`. The race is only pushed one step later, not closed.

### Option B — Application-level mutex (in-process lock, e.g. a `ConcurrentHashMap<String, Lock>`)
- **Pros:** No extra database round trip.
- **Cons:** Does not survive a multi-instance deployment — two application nodes have independent
  lock tables and would both accept the same key. Ledger correctness cannot depend on a
  single-process assumption.

### Option C — Single `INSERT ... ON CONFLICT DO NOTHING` as the claim (chosen)
- **Pros:** The database's own unique index *is* the coordination primitive: exactly one `INSERT` of
  a given key can succeed; Postgres — not the application — decides which. There is no window
  between "check" and "act" because there is no separate check. Works identically across any number
  of application instances, since the constraint lives in the shared database.
- **Cons:** The loser gets a boolean, not the winner's result — it must separately poll for the
  winner to finish (see below).

## Decision

`idempotency_keys.key` is `PRIMARY KEY`. `IdempotencyKeyRepository.tryClaim` issues one
`INSERT ... ON CONFLICT DO NOTHING`; a return of 1 row means this request won and executes, 0 means
someone else holds the key. The loser then reads the existing row and either replays a `COMPLETED`
response, retries a `FAILED` one (see below), or polls (25 ms interval, 1000 ms budget) while it is
`IN_PROGRESS`, giving up with `503 IDEMPOTENCY_TIMEOUT` if the winner never finishes in time.

**The claim commits before the guarded operation runs.** `IdempotencyFilter` is deliberately *not*
`@Transactional`; its three writes (claim, then completion or failure) are separate, individually
committed statements around the guarded request, not one transaction spanning it. If the claim were
held open until the operation finished, the loser would block on the row lock for the operation's
full duration instead of getting an immediate conflict signal — turning a cheap 25 ms poll into a
wait as long as the slowest possible transfer.

**Terminal status depends on the response class.** A `4xx` (a client error like `INSUFFICIENT_FUNDS`
or `SAME_ACCOUNT_TRANSFER`) marks the key `COMPLETED`: the request was correctly rejected, and a
retry with the identical body should see the identical rejection, not re-run the operation. A `5xx`
or an unhandled exception marks the key `FAILED`, which is retryable — `FAILED` means *transient*.
A retry against a `FAILED` key uses `reclaimFailed` (`UPDATE ... WHERE status = 'FAILED'`), itself
guarded the same way: if two retries race, only one `UPDATE` matches the `WHERE` clause and wins;
the loser (0 rows updated) re-loops rather than assuming success.

**A replay always returns `200`, never the original `201`.** The status code communicates what
*this* request did — nothing — not what the original request did.

**`transfers.idempotency_key` is populated, not left as dead schema.** The filter passes the claimed
key through `TransferController` into `TransferService.execute`, so the table's own
`transfers_idempotency_key_uq` becomes a live secondary guard, not merely committed-but-unused DDL.

## Consequences

**Positive:** Correctness does not depend on process count, thread scheduling, or lock timeout
tuning — it depends on one database index, which is exactly as strong as the invariants this
project already trusts Postgres for elsewhere (§ledger_entries immutability trigger, the balance
CHECK constraints). `ConcurrentDuplicateIT` exercises the actual race (two threads, one
`CyclicBarrier`, 100 repetitions) rather than asserting the design on paper.

**Negative / accepted costs:** A crash between the guarded operation's `COMMIT` and the filter's
`markCompleted` call leaves the key stranded `IN_PROGRESS` forever — no money is lost or duplicated
(the claim still blocks a real duplicate), but the caller's retry polls for 1 s and gets
`503 IDEMPOTENCY_TIMEOUT` indefinitely, with no automatic recovery. `idx_idempotency_keys_status`
exists precisely so a future sweep can find and reset these; building that sweep is SPEC 0007's
crash-harness territory, not this one's.

**What would change our mind:** If stranded `IN_PROGRESS` keys became common enough in practice to
need automatic recovery before SPEC 0007 lands, that would justify pulling the sweep forward rather
than waiting.
