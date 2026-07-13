---
name: adr-new
description: Capture an architecture decision as docs/adr/NNNN-*.md — context, options considered, decision, and honest consequences. Use before merging code that depends on a significant, arguable choice.
disable-model-invocation: true
---

# /adr-new

Record a decision **before** the code that depends on it merges (NFR-15).

## When an ADR is warranted

Write one when a choice is **arguable** — when a competent engineer could reasonably have chosen
otherwise, and a future reader will wonder why we didn't.

- Yes: isolation level. Optimistic vs pessimistic. Saga vs distributed transaction. API key vs JWT.
  Storing `response_snapshot` as TEXT rather than JSONB.
- No: variable names, formatting, obvious library choices, anything with only one sane option.

## Steps

1. `ls docs/adr/` → next free `NNNN`.
2. Fill in `docs/adr/TEMPLATE.md`:
   - **Context** — the forces at play. Write it so someone who *disagrees with the outcome* still
     recognizes the problem as real.
   - **Options considered** — with genuine pros *and* cons for each. If an option has no pros, you
     have written a strawman and the ADR is worthless: nobody was ever going to pick it, so listing
     it proves nothing.
   - **Decision** — plainly stated.
   - **Consequences** — including the **costs we are accepting**. An ADR with no downsides listed is
     marketing, not engineering. Every real decision has a price; name it.
   - **What would change our mind** — the evidence that would make us revisit this. This is what
     makes an ADR a living document rather than a monument.
3. Link it from the spec that motivated it.
4. Append to `progress_report.md`.

## Why this matters here

These ADRs are a deliverable, not bookkeeping. "Which isolation level would you choose and why?" is
a standard fintech interview question, and `docs/adr/0001-isolation-level.md` is the answer —
written down, with the trade-offs named and the sharp edges (write skew!) flagged. Interviewers read
these. Write them for that reader.
