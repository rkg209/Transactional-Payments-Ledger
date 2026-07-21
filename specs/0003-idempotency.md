# SPEC 0003 — Idempotency

Status: implemented
Depends on: 0002
Requirements: FR-14, FR-15, FR-16, FR-17, FR-18, NFR-5

## Goal

A retried payment is applied **exactly once** — even when two identical requests race. This is the
single most-probed question in a fintech interview, and the one this project must answer without
hedging.

## In scope

- `IdempotencyFilter` (a `OncePerRequestFilter`) intercepting all POST/PUT/PATCH.
- `FingerprintService` — SHA-256 of the raw request body bytes, computed before the body is consumed.
- `idempotency_keys` table with the **PRIMARY KEY on `key`** as the serialization mechanism.
- First request executes and stores its response snapshot; retries replay it byte-for-byte.
- Concurrent duplicate: the losing INSERT takes a unique-constraint violation, then polls until the
  winner resolves, then returns the winner's stored response.
- Fingerprint mismatch on a reused key → `422 IDEMPOTENCY_KEY_REUSE`, and **no** transfer executes.
- `FAILED` keys are retryable.
- `X-Idempotent-Replayed: true` header on replays; replays return `200`, not the original `201`.

## Out of scope

- Multi-step sagas. (SPEC 0006)
- Key expiry — the table grows monotonically in v1, by design
  (`planning/05-api-design.md` §4.5).

## Design notes

**The unique constraint is the concurrency primitive.** Not a mutex, not a distributed lock, not an
application-level check — those all have a window between "check" and "act" in which a second
request can slip through. The database's unique index does not: exactly one INSERT of a given key
can commit, and PostgreSQL decides which. The loser's failure *is* the coordination signal.

This is why the check-then-insert must be one atomic INSERT, not `SELECT` followed by `INSERT`. A
`SELECT ... FOR UPDATE` on a row that does not exist yet locks nothing — there is no row to lock.
This is the classic trap and the reason many naive idempotency implementations are quietly broken.

`response_snapshot` is `TEXT`, not `JSONB`, so replay is byte-identical: JSONB would reparse and
re-serialize, potentially reordering keys.

The service layer never learns whether a request is a retry. It always executes as if for the first
time; the filter above it decides whether to call it at all.

## Acceptance criteria (the measurable "done")

- [x] Replaying one key N times sequentially → **exactly one** transfer applied; N−1 replays return
      the stored response with `X-Idempotent-Replayed: true`.
- [x] **Two concurrent identical requests → exactly one transfer applied**; the other returns the
      same stored result. Neither returns an error.
- [x] Same key + different body → `422 IDEMPOTENCY_KEY_REUSE`, no transfer executed.
- [x] A key whose request failed is in `FAILED` state and may be retried successfully.
- [x] Replay of a `201` returns `200` with a byte-identical body.
- [x] Money is conserved and Σ = 0 holds across every idempotency test.

## Test plan

- `IdempotencyReplayIT` — sequential replay, N=10, exactly one transfer.
- `ConcurrentDuplicateIT` — **the important one.** Two threads, same key, released simultaneously
  from a `CyclicBarrier`. Assert exactly one transfer row, one pair of entries, and identical
  response bodies. Repeat 100× to catch the race that only fires sometimes.
- `FingerprintMismatchIT` — key reuse with a different body is rejected and applies nothing.
- `FailedKeyRetryIT` — a FAILED key is retryable.
