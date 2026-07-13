---
name: concurrency-test
description: Run the chaos/concurrency harness (make concurrency-test) and print the headline results table. Use to reproduce or check the project's headline correctness guarantee.
disable-model-invocation: true
---

# /concurrency-test

Run the harness that produces **the headline number**.

> 10,000 concurrent transfers, ~30% duplicate (idempotent) requests against hot accounts →
> **0 double-charges, 0 money created or destroyed, Σ(entries) = 0 held across every run.**

## Steps

1. Confirm Docker is running (Testcontainers needs it).
2. `make concurrency-test`
3. It takes several minutes. Say so before starting, then wait — do not poll.
4. Print the results table verbatim.

## The results table

```
┌─────────────────────────────────────────────────────────────┐
│  CONCURRENCY & CHAOS HARNESS — RESULTS                       │
├─────────────────────────────────────────────────────────────┤
│  Strategy               │ optimistic | pessimistic           │
│  Requests fired         │ 10,000                             │
│  Duplicate requests     │ 3,012  (30.1%)                     │
│  Unique transfers applied│ 6,988                             │
│  Double-charges         │ 0        ← must be 0               │
│  Money created/destroyed│ 0        ← must be 0               │
│  Σ(ledger_entries)      │ 0        ← must be 0               │
│  Accounts below minimum │ 0        ← must be 0               │
│  Sagas non-terminal     │ 0        ← must be 0               │
│  Crash injections       │ N (all recovered)                  │
│  Wall clock             │ M m S s                            │
└─────────────────────────────────────────────────────────────┘
```

## Interpreting it

**The pass bar is hard, not statistical.** One double-charge is a failure. A single unit of money
created is a failure. There is no acceptable non-zero rate here — rejecting exactly that idea is
the reason this project exists.

If any must-be-zero row is non-zero:
1. **Do not retry hoping it passes.** An intermittent correctness failure is a correctness failure;
   it means a race exists and you got lucky the other times. "It passed on the second run" is the
   most dangerous sentence in this codebase.
2. Capture the failing seed/schedule so it can be replayed deterministically.
3. Run `/invariant-check` to see the shape of the damage.
4. Hand the diff to the `concurrency-reviewer` subagent.

Flaky **is** failing. Report it honestly — including to the user, and including in
`progress_report.md`.
