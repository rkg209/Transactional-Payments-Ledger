# ADR 0009 — Retry-exhaustion (409) marks an idempotency key FAILED, not COMPLETED

Date: 2026-07-22
Status: accepted
Deciders: Project owner
Relates to: SPEC 0007, ADR 0005, FR-14, FR-33

## Context

ADR 0005 drew the terminal-status line at the response class: any `4xx` marks the claimed
`idempotency_keys` row `COMPLETED` (a deterministic rejection, safe to replay forever), any `5xx`
marks it `FAILED` (transient, retryable via `reclaimFailed`). `SPEC 0007`'s concurrency-and-chaos
harness — 10,000 requests against 4 hot accounts under the optimistic strategy — found the line was
drawn in the wrong place for one specific code: `409 CONFLICT_RETRY_EXHAUSTED`.

That status is `TransferService` reporting it could not win its internal lock/CAS race within its
own retry budget *this attempt*. It is not a property of the request the way `INSUFFICIENT_FUNDS`
or `SAME_ACCOUNT_TRANSFER` are — the identical request, retried a moment later under less
contention, can succeed. Marking its key `COMPLETED` froze that outcome permanently:
`IdempotencyFilter.replay()` always answers `200` regardless of what the cached snapshot actually
contains, so every subsequent retry with that key — forever — silently replayed the stale 409 as if
it were a successful duplicate, and the transfer could never happen. The harness caught this as a
missing logical transfer (`uniqueApplied` one short of the planned count, both requests in the pair
landing in "duplicate detected") with no data corruption — money conservation held throughout,
because nothing was ever partially written — but a legitimate transfer was permanently lost.

## Options considered

### Option A — Leave the 4xx/5xx line where ADR 0005 drew it
- **Pros:** No code change; `IdempotencyFilter` stays a two-way branch.
- **Cons:** Ships a known bug: any transfer that ever loses its internal retry race under
  contention is permanently unrecoverable through that idempotency key. Silent and irreversible from
  the caller's point of view — no error ever surfaces after the first exhaustion, only an eternal
  false-success replay.

### Option B — Route `409 CONFLICT_RETRY_EXHAUSTED` to `FAILED` alongside `5xx` (chosen)
- **Pros:** `CONFLICT_RETRY_EXHAUSTED` is the only status the `ErrorCode` catalogue issues under
  `HttpStatus.CONFLICT`, so checking the status code is exact, not a heuristic. `FAILED` already has
  a correct, race-safe reclaim path (`reclaimFailed`, guarded by `WHERE status = 'FAILED'`) built for
  exactly this shape of transient failure — no new mechanism needed. A caller retrying the same key
  gets a fresh attempt instead of a frozen stale error.
- **Cons:** Widens the exception list `IdempotencyFilter` special-cases from "any 5xx" to "any 5xx or
  this one specific 4xx" — a small, targeted carve-out, but a carve-out nonetheless; future new 4xx
  codes need the same "is this deterministic?" judgment call before being added to the catalogue.

## Decision

`IdempotencyFilter.execute()` now marks a key `FAILED` when `wrappedResponse.getStatus() >= 500`
**or** `== 409` (`IdempotencyFilter.java`). Every other `4xx` still marks `COMPLETED`, unchanged from
ADR 0005 — those remain genuinely deterministic outcomes worth caching.

## Consequences

**Positive:** A transfer that loses its internal retry race can still succeed on a later client
retry with the same key, instead of being permanently wedged. `AbstractConcurrencyChaosHarness`'s
"every logical transfer applies exactly once" assertion holds under sustained optimistic-strategy
contention — the condition this ADR fixes was the harness's own first genuine (non-injected)
failure.

**Negative / accepted costs:** A key that keeps losing its retry race indefinitely (starvation, not
merely bad luck) now cycles `IN_PROGRESS` → `FAILED` → reclaimed → `IN_PROGRESS` → … rather than
settling into a cached terminal answer — bounded by the harness's own client-side retry cap
(`AbstractConcurrencyChaosHarness.MAX_CLIENT_ATTEMPTS`), not by anything server-side. A pathological
client that never gives up could in principle retry such a key forever; nothing here bounds that,
the same way nothing already bounded a `FAILED`-from-5xx key before this change.

**What would change our mind:** If a future error code needs the same treatment (contention-shaped,
not request-shaped, yet not a 5xx), extend the same condition rather than inventing a parallel
mechanism — the underlying rule is "does retrying the identical request, unchanged, have a
reasonable chance of a different outcome," not "what HTTP status class is this."
