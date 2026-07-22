# ADR 0012 — A stranded idempotency claim is recovered on retry, not by a sweeper

Date: 2026-07-22
Status: accepted
Deciders: Project owner
Relates to: SPEC 0010, ADR 0005, FR-14, FR-16, FR-17, NFR-5, NFR-10

## Context

ADR 0005 chose a unique-constraint claim as the idempotency primitive and accepted one cost
explicitly:

> A crash between the guarded operation's `COMMIT` and the filter's `markCompleted` call leaves the
> key stranded `IN_PROGRESS` forever … the caller's retry polls for 1 s and gets
> `503 IDEMPOTENCY_TIMEOUT` indefinitely, with no automatic recovery. `idx_idempotency_keys_status`
> exists precisely so a future sweep can find and reset these; building that sweep is SPEC 0007's
> crash-harness territory, not this one's.

SPEC 0007 never built it — idempotency-key recovery appears nowhere in its scope — so the deferral
was dropped rather than discharged. A verification pass over the finished project found the hole
still open.

This is a **liveness** failure, not a safety one. A stranded key creates no money, destroys none,
and double-charges nobody. But `IdempotencyFilter` is the only gate to `POST /transfers`, so a
stranded key means one specific payment is rejected forever, with no recourse short of a human
running `UPDATE idempotency_keys`. For a payments system that is not an acceptable resting state.

There are two distinct crash windows, and they fail differently:

| Crash point | Transfer committed? | Behavior before this ADR |
|---|---|---|
| Before the transfer's `COMMIT` | No | Key stranded → every retry `503`, forever |
| After `COMMIT`, before `markCompleted` | Yes | Key stranded → every retry `503`, forever |

## Options considered

1. **Leave it.** Document the manual `UPDATE` as the operator runbook. Cheapest, and honest, but it
   makes a routine crash into a human-intervention event — the exact property NFR-10 ("recover
   deterministically … with no manual intervention required") exists to forbid.
2. **A background sweeper job** that periodically resets stale `IN_PROGRESS` rows to `FAILED`. This
   is what ADR 0005 anticipated. It works, but it adds a second concurrent writer to
   `idempotency_keys` purely to produce a state change no caller can observe until they retry
   anyway, and it introduces a window in which the sweeper and a live retry race for the same row.
3. **Recover on retry** — the retry that is already asking about this key reclaims it in place, if
   and only if the claim is provably abandoned.

## Decision

**Option 3.** `IdempotencyFilter`, on expiry of its existing 1 s poll window, attempts exactly one
`reclaimStale(key, fingerprint, staleClaimAfter)` before falling back to `503`. The reclaim is a
single guarded `UPDATE`:

```
UPDATE idempotency_keys
   SET status = 'IN_PROGRESS', request_fingerprint = ?, response_snapshot = NULL, updated_at = now()
 WHERE key = ? AND status = 'IN_PROGRESS' AND updated_at < now() - <staleClaimAfter>
```

structurally identical to `reclaimFailed`, and race-safe for the same reason: only one of N racing
retries can match, and a loser (0 rows updated) must re-loop rather than assume success.

Three properties make this correct:

**1. The staleness predicate is evaluated in SQL, against the database's clock.** `updated_at` is
written with `now()`; the cutoff is computed with `now()`. Application/database clock skew therefore
cannot make a live claim look abandoned. The threshold (`ledger.idempotency.stale-claim-after-ms`,
default 30 s) sits more than an order of magnitude above the bounded worst case of a guarded request
— `max-attempts` (5) transactions with backoff capped at 800 ms — and above the 1 s poll that
precedes it.

**2. Safety does not rest on the threshold.** This is the part that matters. Even a badly-chosen
threshold that reclaimed a still-running request could not double-charge, because the re-executed
transfer carries the same `idempotency_key` and would take a `transfers_idempotency_key_uq`
violation. The clock buys *liveness*; the unique constraint keeps *safety*. This is the same
defence-in-depth split the project already uses for `ledger_entries`: the application promises, the
database enforces.

**3. The post-commit window is handled by making the re-execution a no-op, not by avoiding it.**
`TransferService.executeOnce` now looks up `findByIdempotencyKey` before inserting and returns the
committed transfer if one exists — the same pre-insert lookup `LegTransferStep` has always used to
make saga recovery re-runnable. Without it, reclaiming a key whose transfer *had* committed would
re-post, hit the unique constraint, and turn a permanent `503` into a permanent `500`. With it, the
caller gets the original transfer back and the key settles `COMPLETED`.

**A losing reclaim gets one more poll window rather than an immediate `503`.** When `reclaimStale`
updates 0 rows, either the claim is not stale (a genuine in-flight twin) or a concurrent retry just
won and is executing. In the second case the winner is about to write a response this request can
replay, so the deadline is extended once and the loop continues — otherwise a caller whose answer is
milliseconds away would be told to go away.

**No sweeper.** A key nobody retries does not need recovering; a key somebody retries recovers on
that retry. Building a background job to reach the same state earlier, invisibly, is cost without
benefit.

## Consequences

**Positive:** The crash windows ADR 0005 accepted are now self-healing with no operator action, which
is what NFR-10 asks for. `TransferService` also became genuinely idempotent at the service layer
rather than relying entirely on the filter above it — a strictly stronger position, and one the saga
path already depended on informally.

**Negative / accepted costs:** A caller who retries a stranded key inside the staleness window still
gets `503` and must retry again after it elapses — recovery is bounded by the threshold, not
instant. Reclaiming resets `updated_at`, so `idempotency_keys` no longer records when a key was
*first* claimed; nothing currently reads that, but a future audit view would need a separate column.
`TransferService.executeOnce` now issues one extra indexed lookup per transfer on the write path
(`transfers_idempotency_key_uq` serves it); the benchmark's throughput figures in ADR 0010 predate
this and would shift marginally.

**What would change our mind:** If stranded keys ever became common enough that callers routinely
ate a 30 s wait, the threshold should drop before a sweeper is considered — and the unique
constraint means dropping it is safe to experiment with.
