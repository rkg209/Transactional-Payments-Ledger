---
name: spec-verify
description: Verify a spec by id — run the tests, invariant check, and lint mapped to it, then report PASS/FAIL against every acceptance criterion individually. Use before marking a spec done.
disable-model-invocation: true
---

# /spec-verify <NNNN>

Report **PASS or FAIL against each acceptance criterion**, one by one. Not "the tests pass" —
criterion by criterion, because that is what the spec actually promised.

## Steps

1. Read `specs/NNNN-*.md` and extract every checkbox in *Acceptance criteria*.

2. For each criterion, identify the specific evidence that proves it: a named test, a command's
   output, an `EXPLAIN` plan, a metric. **A criterion with no evidence is a FAIL**, not an
   "assumed pass."

3. Run:
   - `make test`
   - `/invariant-check`
   - `mvn spotless:check`
   - Anything else the spec's test plan names.

4. **Report as a table:**

   | # | Criterion | Result | Evidence |
   |---|---|---|---|
   | 1 | … | PASS | `DoubleEntryCoreIT#conservesMoney` |
   | 2 | … | **FAIL** | expected 0 drift, got 3 |

5. Then state the verdict plainly:
   - **All PASS** → update the spec status to `verified`. Say so.
   - **Any FAIL** → the spec is **not** done. List exactly what remains. Do not round up.

## The thing to resist

Do not mark a criterion PASS because the code "looks right" or because a *neighboring* test passes.
The entire premise of this project is that correctness is **demonstrated, not asserted** — and that
standard applies to our own verification process first of all. If we are sloppy here, every number
in the README is suspect.

If a criterion turns out to be untestable as written, that is a finding worth reporting: fix the
spec, don't quietly drop the criterion.
