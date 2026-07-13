---
name: spec-implement
description: Implement a spec by id — plan, write the failing test first, then implement to green, strictly within that spec's scope. Ends with subagent review and a progress_report.md entry. Use when building an approved spec.
disable-model-invocation: true
---

# /spec-implement <NNNN>

Implement the named spec. **Test first. Scope strictly. Review before commit.**

## Steps

1. **Read the spec.** `specs/NNNN-*.md`. If its status is not `approved`, stop and ask — do not
   implement a draft.

2. **Read the design docs it points at.** The schema, module boundaries, and error catalogue are
   already settled in `planning/`. Consult them; do not re-derive or quietly contradict them.

3. **Plan.** State which files you will create or change, and map each acceptance criterion to the
   test that will prove it. If a criterion has no test, either it is not measurable (go fix the
   spec) or you are about to skip it.

4. **Write the failing tests first.** Run them. **Watch them fail for the right reason** — a test
   that passes before the feature exists is testing nothing, and a test that fails with
   `ClassNotFoundException` has not yet demonstrated anything either.

5. **Implement to green.** Nothing beyond the spec's scope. If you find yourself needing something
   out of scope, stop and raise it — that is a new spec, not a silent expansion of this one.

   Hold the invariants (CLAUDE.md). The hooks will stop you on floats-as-money and ledger mutation,
   but do not rely on them to think for you.

6. **Review with subagents** before declaring done:
   - `concurrency-reviewer` — on **any** diff touching transfers, balances, locking, idempotency,
     or sagas. This is not optional for those paths.
   - `sql-reviewer` — on any diff touching jOOQ or migrations.
   - `test-engineer` — to find the schedule that breaks your invariant, if one exists.

7. **Verify.** Run `/spec-verify NNNN`. Every acceptance criterion must be PASS.

8. **Update the spec status** to `implemented`.

9. **Append to `progress_report.md`** via `/progress-log`. Mandatory — the entry is part of the
   change, not a chore afterwards. Record the issues you actually hit, including the approach you
   tried first and abandoned. That is the most useful part of the record.

## The rule that matters most

**Stay in scope.** Each spec is a thin, shippable slice. The temptation to "just also add" the next
feature while you are in the file is how spec-driven development quietly becomes ordinary
development with extra paperwork.
