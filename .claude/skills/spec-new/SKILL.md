---
name: spec-new
description: Scaffold a new spec in specs/ from the template. Interviews for goal, in/out scope, and measurable acceptance criteria. Use when starting any new piece of work that has no spec yet.
disable-model-invocation: true
---

# /spec-new

Create a new spec. **Nothing gets implemented without one** (CON-8).

## Steps

1. **Pick the number.** `ls specs/` and take the next free `NNNN`.

2. **Interview the user.** Do not write the spec from a one-line request — a vague spec produces
   vague code. Get answers to:
   - What capability does this deliver? (one paragraph, concrete)
   - What is explicitly **out** of scope? *(This matters more than "in scope." An unbounded spec is
     not a spec.)*
   - What are the **measurable** acceptance criteria? Push hard here: "handles concurrency
     correctly" is not measurable; "1000 concurrent transfers against one hot account → final
     balance exactly correct, 0 lost updates" is.
   - Which `FR-*`/`NFR-*` from `planning/01-requirements.md` does this satisfy?
   - What does it depend on?

3. **Delegate the sharpening.** Hand the rough answers to the `spec-author` subagent, which tightens
   scope, enumerates edge cases, and makes the criteria falsifiable.

4. **Write** `specs/NNNN-<kebab-name>.md` from `specs/TEMPLATE.md`. Status: `draft`.

5. **Add it to the table** in `specs/README.md`.

6. **Present it to the user for approval.** A spec is not `approved` until they say so. Do not start
   implementing.

## What makes a good acceptance criterion

It must be possible to be **wrong**. If there is no observation that could falsify it, it is a
sentiment, not a criterion.

- Bad: "Transfers are fast." · "Idempotency works." · "The code is clean."
- Good: "p99 < 50ms at 100 concurrent transfers." · "Two concurrent identical requests → exactly one
  transfer row and one pair of entries." · "ArchUnit: no class outside `db` imports `db.generated`."
