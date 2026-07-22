# SPEC 0006 — Sagas & multi-step transfers with compensation

Status: verified
Depends on: 0005
Requirements: FR-27, FR-28, FR-29, FR-30, FR-31, NFR-4, NFR-10, CON-7

## Goal

Multi-leg transfers that either **fully complete or fully roll back — even across a crash**. The
guarantee is not "usually recovers"; it is that killing the process at *any* step leaves the saga
in exactly one of two terminal states, with the books reconciling either way.

## In scope

- In-process `SagaOrchestrator` (e.g. A→B→C, or hold → capture → settle).
- `SagaStep` interface: `forward()` / `compensate()`.
- **Persisted** `sagas` / `saga_steps` state, committed **before** each step executes.
- Compensation in reverse order on step failure.
- `SagaRecoveryRunner` (`ApplicationRunner`) — recovers in-progress sagas on restart, **before**
  the HTTP server accepts connections.
- `POST /api/v1/transfers/saga`, `GET /api/v1/sagas/{id}`.

## Out of scope

- Cross-service / distributed sagas. (CON-7 — that is the Message Broker project.)

## Design notes

**The orchestrator holds no transaction across steps.** Each step commits independently. That is
what *makes* it a saga rather than a distributed transaction: atomicity comes from compensation,
not from holding locks across the whole workflow.

The ordering rule that makes recovery possible: **persist intent before acting.** A step is written
as `IN_PROGRESS` in its own committed transaction *before* `forward()` runs. So after a crash, an
`IN_PROGRESS` step is ambiguous — `forward()` may or may not have committed — and recovery must
treat it as *possibly done*. Getting this backwards (acting, then persisting) produces steps that
executed but left no trace, which are unrecoverable by definition.

Recovery must therefore be **idempotent per step**, since it may re-examine a step whose forward
action did commit.

Compensation writes **new reversing entries**; it never deletes the originals (invariant #2). A
compensated saga leaves a longer audit trail than a successful one — that is correct, and it is
the honest record of what actually happened.

`sagas.state = FAILED` means compensation *itself* failed: the system is genuinely inconsistent and
needs a human. It is a real state, not a theoretical one, and reconciliation will flag the drift.

## Acceptance criteria (the measurable "done")

- [ ] A successful multi-step saga completes; Σ = 0 holds.
- [ ] A saga whose step fails compensates all prior steps in reverse; Σ = 0 holds.
- [ ] **Killing the service at *every* saga step and restarting leaves each saga either fully
      completed or fully compensated.** No saga is left in a permanently intermediate state.
- [ ] Books reconcile to Σ = 0 after every crash-and-recover cycle.
- [ ] Recovery runs before the HTTP server accepts traffic.
- [ ] Recovery is idempotent: running it twice changes nothing.

## Test plan

- `SagaHappyPathIT` — all steps succeed.
- `SagaCompensationIT` — a mid-saga failure compensates cleanly.
- `SagaCrashRecoveryIT` — **the headline test for this spec.** Parameterized over *every* step
  index: kill the process (or simulate by aborting the JVM's saga thread and restarting the
  context) at step i, restart, assert the saga reached a terminal state and Σ = 0. Run for every i.
- `SagaRecoveryIdempotenceIT` — recovery twice is a no-op the second time.
