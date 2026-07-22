# SPEC 0007 — Concurrency & crash test harness

Status: implemented
Depends on: 0006
Requirements: FR-32, FR-33, NFR-1, NFR-2, NFR-3, NFR-4, NFR-9, NFR-12, DR-4

## Goal

**Manufacture the headline number.** Everything before this spec built the guarantee; this spec
*proves* it, reproducibly, on a clean machine, in one command. This is the deliverable a recruiter
runs and an interviewer probes.

> **The number:** 10,000 concurrent transfers, ~30% duplicate (idempotent) requests against hot
> accounts → **0 double-charges, 0 money created or destroyed, Σ(entries) = 0 held across every run.**

## In scope

- A driver firing ≥10,000 concurrent transfers against **hot accounts** (deliberate contention —
  a uniform spread across many accounts would prove nothing).
- ~30% of requests are duplicates: same `Idempotency-Key`, fired concurrently with their twin.
- Crash injection at every saga step.
- End-of-run assertions:
  - **0 double-charges** — count of applied transfers equals count of *unique* idempotency keys.
  - **0 money created or destroyed** — Σ(all balances) equals the pre-run total.
  - **Σ(entries) = 0**.
  - No account below its `min_balance`.
  - Every saga in a terminal state.
- A printed results table (FR-33): requests fired, unique transfers applied, duplicates detected,
  double-charges, money delta, invariant status, crash-recovery outcomes.
- Wired to `make concurrency-test`.

## Out of scope

- Micro-latency benchmarking. (SPEC 0008)

## Design notes

**Hot accounts are the whole design.** Contention is the thing being tested. Spreading 10,000
transfers across 10,000 account pairs would pass trivially and prove nothing about locking. A
small number of hot accounts is what makes the result meaningful.

Duplicates must be fired **concurrently with their originals**, not sequentially afterwards.
A sequential replay tests the "key already COMPLETED" path — the easy one. The concurrent race
tests the unique-constraint serialization path, which is where real implementations break.

The pass/fail bar is **hard, not statistical** (NFR-1). One double-charge is a failure. There is
no "0.01% duplicate rate is acceptable" — that is precisely the claim this project exists to reject.

Run must complete in under ~10 minutes on a laptop (NFR-9), or nobody will run it.

## Acceptance criteria (the measurable "done")

- [ ] **10,000 concurrent transfers, ~30% duplicates → 0 double-charges.**
- [ ] **0 money created or destroyed** — total across all accounts is bit-for-bit unchanged.
- [ ] **Σ(entries) = 0** after the run.
- [ ] Crashes injected at every saga step always reconcile to a terminal state.
- [ ] No account ends below `min_balance`.
- [ ] The results table prints to stdout.
- [ ] `make concurrency-test` reproduces all of the above on a clean machine with only Docker +
      Make installed (DR-4).
- [ ] Completes in under 10 minutes on a developer laptop.
- [ ] The run is repeatable: 10 consecutive runs, 10 passes. Flaky is failing.

## Test plan

`ConcurrencyChaosHarness` — the driver itself, run under `make concurrency-test` against
Testcontainers Postgres. Parameterized over both concurrency strategies: the headline must hold for
optimistic *and* pessimistic, or the guarantee is a property of one lucky configuration rather than
of the system.
