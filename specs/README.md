# Specs — the source of truth

**Nothing gets implemented without a spec file here first.** (CON-8)

Build strictly in dependency order. Each spec is a thin, shippable slice that deepens the system.

| Spec | Name | Status | Delivers |
|---|---|---|---|
| [0000](0000-walking-skeleton.md) | Walking skeleton & CI | **approved** | App boots on real Postgres; CI green. |
| [0001](0001-double-entry-core.md) | Double-entry core | draft | Balanced transfers, atomically. The foundation. |
| [0002](0002-transfer-api.md) | Transfer API | draft | The ledger becomes curl-able. |
| [0003](0003-idempotency.md) | Idempotency | draft | Retried payments apply exactly once. |
| [0004](0004-concurrency-control.md) | Concurrency control | draft | Optimistic **and** pessimistic, both correct. |
| [0005](0005-invariant-reconciliation.md) | Invariant & reconciliation | draft | Continuously prove Σ = 0. |
| [0006](0006-sagas-compensation.md) | Sagas & compensation | draft | Crash-safe multi-step transfers. |
| [0007](0007-concurrency-crash-harness.md) | Concurrency & crash harness | draft | **The headline number.** |
| [0008](0008-performance-benchmark.md) | Performance benchmark | draft | The cost of the guarantee. |
| [0009](0009-observability-packaging.md) | Observability & packaging | draft | Production-shaped and presentable. |

## Lifecycle

`draft` → `approved` → `implemented` → `verified`

Update the `Status:` line in the spec file as it moves. A spec is `verified` only when
`/spec-verify` reports PASS against **every** acceptance criterion — not when the code merely
compiles.

## Stretch (future work — not written, mention as "future work")

- **0010** Multi-currency + FX — per-currency sub-ledgers; FX as a balanced cross-currency transfer.
- **0011** Transactional outbox — publish ledger events atomically with the entry write.
- **0012** Event-sourced audit log — point-in-time balance reconstruction.

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
