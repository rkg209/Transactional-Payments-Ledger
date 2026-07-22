# Specs — the source of truth

**Nothing gets implemented without a spec file here first.** (CON-8)

Build strictly in dependency order. Each spec is a thin, shippable slice that deepens the system.

| Spec | Name | Status | Delivers |
|---|---|---|---|
| [0000](0000-walking-skeleton.md) | Walking skeleton & CI | **verified** | App boots on real Postgres; CI green. |
| [0001](0001-double-entry-core.md) | Double-entry core | **verified** | Balanced transfers, atomically. The foundation. |
| [0002](0002-transfer-api.md) | Transfer API | **verified** | The ledger becomes curl-able. |
| [0003](0003-idempotency.md) | Idempotency | **verified** | Retried payments apply exactly once. |
| [0004](0004-concurrency-control.md) | Concurrency control | **verified** | Optimistic **and** pessimistic, both correct. |
| [0005](0005-invariant-reconciliation.md) | Invariant & reconciliation | **verified** | Continuously prove Σ = 0. |
| [0006](0006-sagas-compensation.md) | Sagas & compensation | **verified** | Crash-safe multi-step transfers. |
| [0007](0007-concurrency-crash-harness.md) | Concurrency & crash harness | **verified** | **The headline number.** |
| [0008](0008-performance-benchmark.md) | Performance benchmark | **verified** | The cost of the guarantee. |
| [0009](0009-observability-packaging.md) | Observability & packaging | **verified** | Production-shaped and presentable. |
| [0010](0010-stale-idempotency-claim-recovery.md) | Stale idempotency claim recovery | **verified** | A key stranded by a crash stays completable. |

## Lifecycle

`draft` → `approved` → `implemented` → `verified`

Update the `Status:` line in the spec file as it moves. A spec is `verified` only when
`/spec-verify` reports PASS against **every** acceptance criterion — not when the code merely
compiles.

## Stretch (future work — not written, mention as "future work")

- **0011** Multi-currency + FX — per-currency sub-ledgers; FX as a balanced cross-currency transfer.
- **0012** Transactional outbox — publish ledger events atomically with the entry write.
- **0013** Event-sourced audit log — point-in-time balance reconstruction.

(Renumbered from 0010–0012: SPEC 0010 was taken by stale idempotency claim recovery, which was not
future work — it closed a hole ADR 0005 left open.)

## Workflow

```
/spec-new ──► spec-author subagent sharpens it ──► you approve specs/NNNN.md
      │
      ▼
/spec-implement  (failing test first → implement to green)
      │   block-float-money hook rejects money-as-float / ledger mutation
      │   format-and-build hook runs on every edit
      ▼
concurrency-reviewer + sql-reviewer subagents review the diff
      │
      ▼
/spec-verify ──► PASS/FAIL per acceptance criterion ──► /adr-new for big decisions
      │
      ▼
/progress-log ──► gate-commit hook ──► commit ──► next spec
```
