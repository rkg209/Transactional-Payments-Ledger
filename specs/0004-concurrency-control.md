# SPEC 0004 — Concurrency control (BOTH strategies)

Status: implemented
Depends on: 0003
Requirements: FR-19, FR-20, FR-21, FR-22, FR-23, NFR-3, NFR-14

## Goal

Correct balances under heavy contention, via two interchangeable, explicitly-chosen strategies.
SPEC 0001 knowingly shipped a racy read-then-write; this spec fixes it — and does so twice, so that
"which locking strategy?" becomes a question we answer with data (SPEC 0008) rather than with taste.

## In scope

- `ConcurrencyStrategy` interface — the single seam where locking behavior differs.
- `OptimisticStrategy` — plain SELECT, then `UPDATE … WHERE id = ? AND version = ?`; 0 rows updated
  means a concurrent write won, so retry. Bounded retries (default 5) with exponential backoff;
  exhaustion → `409 CONFLICT_RETRY_EXHAUSTED`.
- `PessimisticStrategy` — `SELECT … FOR UPDATE`, accounts locked in **ascending UUID order**.
- Strategy toggle: `CONCURRENCY_STRATEGY=optimistic|pessimistic` at startup.
- Explicit isolation level, set per-transaction, never inherited from the pool default.
  `ISOLATION_LEVEL=read_committed|serializable`.
- Overdraft rule correctly enforced under contention by **both** strategies.
- ADR `0002-optimistic-vs-pessimistic.md`. **Corrective note:** ADRs are append-only and 0002 was
  already taken by `jooq-codegen`; the actual ADR for this decision is
  `docs/adr/0006-optimistic-vs-pessimistic.md`.

## Out of scope

- The benchmark comparing them. (SPEC 0008)

## Design notes

**Lock ordering is the deadlock prevention.** Two concurrent transfers A→B and B→A will deadlock if
each locks its source first. Locking always in ascending UUID order means both transactions request
the same row first, so one simply waits. This is not an optimization; it is the correctness
argument, and it must hold in both strategies.

**READ COMMITTED does not prevent lost updates.** That is exactly why this layer exists — see
`docs/adr/0001-isolation-level.md`. The isolation level and the locking strategy are two halves of
one decision; neither is sufficient alone.

The optimistic retry must re-read state on each attempt. Retrying with the stale version read is
not a retry, it is the same doomed transaction executed again.

Note the subtlety: the retry wraps the **whole transaction**, including the ledger-entry inserts.
A rolled-back attempt must leave no entries behind, or a retry would double-post. This is why the
retry sits outside the `@Transactional` boundary, not inside it.

## Acceptance criteria (the measurable "done")

- [x] Both strategies pass the concurrency hammer: N concurrent transfers against a single hot
      account → **no lost updates**, final balance exactly correct.
- [x] Neither strategy ever produces an illegal negative balance under contention.
- [x] Σ(entries) = 0 after every run, under both strategies.
- [x] Concurrent A→B and B→A transfers do not deadlock (lock ordering works).
- [x] Optimistic retry exhaustion returns 409, and applies **nothing**.
- [x] A rolled-back optimistic attempt leaves zero ledger entries behind.
- [x] The strategy toggle switches behavior with no other code change.
- [x] ADR 0006 (renumbered from 0002 — see corrective note above) records the decision and the
      anomalies each level prevents.

## Test plan

- `ConcurrencyHammerIT` — parameterized over both strategies. K threads × M transfers against one
  hot account. Assert exact final balance (no lost updates) and Σ = 0.
- `DeadlockIT` — bidirectional transfers under both strategies; must complete, not deadlock.
- `OptimisticRetryIT` — force a conflict, assert retry succeeds; force exhaustion, assert 409 and
  no partial state.
- `NegativeBalanceIT` — many concurrent withdrawals from an account with just enough for one.
  Exactly one must succeed.
