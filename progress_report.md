# Progress Report — Transactional Payments Ledger

The running story of how this project was built: what we did, why we did it, how we did it, what
broke, and how we fixed it. Entries are appended in sequence and **never rewritten** — a wrong turn
we later corrected is part of the story.

Format for every entry is defined in `CLAUDE.md`. Add entries with `/progress-log`.

---

## [001] Project planning and document review — 2026-07-14

**Spec:** Setup (pre-SPEC)

**What:** Reviewed the complete planning corpus before writing a single line of code — the root
build plan (`transactional-payments-ledger-spec.md`) and all five planning documents: project
summary, requirements (FR-1…FR-41, NFR-1…NFR-26), architecture, system design, database design
(plus the full `04-database-schema.sql`), and API design (plus `05-openapi.yaml`).

**Why:** The project is spec-driven by mandate (CON-8). Writing code before understanding the
settled decisions would mean re-deriving — and probably contradicting — choices that were already
made deliberately and defensibly. The planning documents are unusually complete: they fix the
schema down to individual CHECK constraints, the module dependency contract, the error-code
catalogue, and the exact isolation-level reasoning. Treating them as authoritative rather than
advisory is the whole point.

**How:** Read every document end to end. Extracted the decisions that constrain all future work:

- Money is `BIGINT` minor units. Never a floating-point type. (FR-1, CON-4)
- `ledger_entries` is append-only; corrections are new compensating entries. (FR-4, CON-5)
- READ COMMITTED isolation + *explicit* locking, because READ COMMITTED alone does not prevent
  lost updates — which is precisely why the ConcurrencyStrategy layer exists at all.
- Both optimistic and pessimistic strategies get built and benchmarked; the crossover point is a
  deliverable, not a footnote.
- All SQL through jOOQ so that *what SQL runs and what locks it takes* is visible at the call site.

Also confirmed the local toolchain: Java 21.0.11, Maven 3.9.16, Docker 29.6.1, GNU Make 3.81.

**Issues faced:** Two inconsistencies between planning documents, and one truncated file.

1. `planning/02-architecture.md` §5.1 defines `transfers` with only `(id, idempotency_key, status,
   timestamps)`, while `planning/04-database-schema.sql` defines a richer table that also carries
   `from_account_id`, `to_account_id`, `amount_minor`, and `currency` — plus FK constraints and a
   `transfers_different_accounts` CHECK.
2. `planning/04-database-schema.sql` is truncated at line 618, mid-comment. It stops partway
   through the `idempotency_keys` index section and never emits indexes for `sagas`,
   `saga_steps`, or `reconciliation_reports`.
3. The architecture doc's schema omits the `ledger_entries` immutability trigger that the
   schema SQL file defines.

**Resolution:**

1. Took `04-database-schema.sql` as authoritative. It is the most-derived artifact (explicitly
   "Derived from architecture.md v1.0, system-design.md v1.0"), it is strictly richer, and its
   extra columns are load-bearing: without `from_account_id`/`to_account_id`/`amount_minor` on
   `transfers`, `GET /transfers/{id}` (FR-9) could not answer what the transfer actually was
   without reconstructing it from ledger entries. Recorded the discrepancy here rather than
   silently editing the older document.
2. Wrote the missing indexes ourselves in `V2__indexes.sql`, following the intent of the ones
   that *were* specified: an index on `sagas(state)` partial to the non-terminal states (this is
   exactly the query `SagaRecoveryRunner` runs on every startup), and `saga_steps(saga_id,
   step_index)` which the UNIQUE constraint already provides.
3. Kept the trigger. It is genuine defence-in-depth: the application promises never to UPDATE or
   DELETE a ledger entry, but a promise enforced only by discipline is not enforced at all. The
   trigger makes invariant #2 true even against a stray `psql` session.

**Lesson:** when planning documents disagree, the more-derived one usually wins — but say so out
loud in the record rather than quietly picking one, so the next reader knows a choice was made.

---

## [002] SDD scaffolding — Claude Code config, specs, ADRs — 2026-07-14

**Spec:** Setup (pre-SPEC)

**What:** Built the spec-driven-development scaffolding that governs everything after it:

- `CLAUDE.md` — the project constitution: the six non-negotiable invariants, the SDD loop, the
  mandatory `progress_report.md` rule, and the mandatory **no-`Co-Authored-By`-in-commits** rule.
- `specs/` — `TEMPLATE.md`, a `README.md` index, and all ten specs (0000–0009). SPEC 0000 is
  `approved`; the rest are `draft`.
- `docs/adr/` — `TEMPLATE.md` and `0001-isolation-level.md`.
- `.claude/skills/` — seven slash commands: `spec-new`, `spec-implement`, `spec-verify`,
  `invariant-check`, `concurrency-test`, `adr-new`, `progress-log`.
- `.claude/agents/` — four subagents: `spec-author`, `concurrency-reviewer`, `sql-reviewer`,
  `test-engineer`.
- `.claude/hooks/` + `settings.json` — three guardrail scripts.
- `.mcp.json` — Postgres MCP server, for live schema inspection and `EXPLAIN` during development.

**Why:** Two reasons, and the second is the real one.

The stated reason (FR-36…FR-41, BG-5): the SDD workflow is itself a deliverable. Committing
`.claude/` is visible evidence of a disciplined, guardrailed process.

The actual reason: **invariants that are merely documented are not enforced.** "Never use a float
for money" written in a README is a hope. The same rule expressed as a `PreToolUse` hook that
rejects the edit is a fact. This project's entire claim is that correctness is *demonstrated, not
asserted* — so the process that builds it should be held to the same standard as the ledger itself.

**How:** Wrote `0001-isolation-level.md` now rather than later, because `planning/02-architecture.md`
§5.3 had already settled it (READ COMMITTED + explicit locking) and the reasoning would only decay.
The ADR names the sharp edge explicitly: **write skew is not prevented**, and this is safe only
because no transfer reads one row to decide a write to a different one. If that ever changes, the
reasoning breaks. Better to write that down now than rediscover it during an incident.

The `gate-commit` hook enforces the `Co-Authored-By` prohibition mechanically, so the rule survives
the moment someone forgets it.

**Issues faced:** The `gate-commit` hook **failed open**, which is the worst possible failure mode
for a guardrail.

Its first version extracted the commit command by parsing the hook's JSON payload in Python. If that
parse threw, the extracted command was the empty string — which matched nothing, so the hook exited
0 and *allowed the commit*. A malformed payload would have silently disabled the very rule the hook
existed to enforce. I only found this because my own test fixture happened to be malformed JSON
(zsh's `echo` expanded `\n` into a real newline, which is illegal inside a JSON string) and the hook
cheerfully let the forbidden commit through.

**Resolution:** Made the check **fail closed**. The `Co-Authored-By` scan now runs against the *raw
stdin*, before any parsing, so it needs no JSON parse to be correct; the parsed-command check remains
as a second, redundant layer. Verified with five cases: valid JSON with the trailer (blocked),
malformed JSON with the trailer (blocked), `--amend --no-verify` with the trailer (blocked), a clean
commit (allowed), and a non-commit command (allowed).

The `block-float-money` hook was tested the same way and got its own fix: a naive `grep` for
`float|double` flagged `double p99LatencyMs`, which is a perfectly legitimate non-monetary float.
A guardrail that cries wolf gets disabled by the person it annoys, and then it protects nothing —
so the pattern is now scoped to monetary identifiers (`amount`, `balance`, `minor`, `cents`, …).

**Lesson:** a guardrail is not done when it blocks the bad case. It is done when you have *also*
proven it allows the good case and fails closed on the weird one. Test your guardrails as adversarially
as you test your code — they are code, and they are the code you trust most.

---

## [003] SPEC 0000 — walking skeleton, green on real PostgreSQL — 2026-07-14

**Spec:** SPEC 0000

**What:** A booting Spring Boot 3.3.5 / Java 21 service on PostgreSQL 16 via jOOQ, with Flyway
migrations, a Testcontainers integration test, Docker Compose, and GitHub Actions CI.

- `V1__initial_schema.sql` — all 7 tables, constraints, and the `ledger_entries` immutability
  trigger, transcribed from `planning/04-database-schema.sql`.
- `V2__indexes.sql` — the specified indexes, plus the three the truncated source file never emitted.
- `GET /health` → `{"status":"UP","database":"UP"}` (FR-10).
- `WalkingSkeletonIT` — 5 tests, all green against a **real** PostgreSQL container.
- `Makefile`, multi-stage `Dockerfile` (JRE, non-root), `docker-compose.yml`, CI workflow.

**Result:** `mvn clean verify` → **Tests run: 5, Failures: 0, Errors: 0**. `docker compose up` →
`/health` returns `{"status":"UP","database":"UP"}` and `/actuator/prometheus` serves metrics.

**Why:** Nothing in this spec is ledger logic, and that is the point. Every correctness claim in
every later spec rests on this pipeline being sound. If Flyway, jOOQ codegen, or Testcontainers is
subtly misconfigured, then a green test suite for SPEC 0004 proves nothing at all. Prove the
foundation first, on a real database, before building anything on it.

The full schema ships now rather than table-by-table because jOOQ generates its type-safe classes
from the **live, migrated schema**: the tables must exist before any repository code can compile.

**How:** Followed `planning/04-database-schema.sql` verbatim rather than redesigning it. The one
real correctness assertion in the skeleton is `ledgerEntriesRejectUpdateAndDelete`, which proves the
database itself rejects `UPDATE` and `DELETE` on `ledger_entries`. That test earns its place this
early: invariant #2 (append-only) is what makes the zero-sum invariant checkable at all, and a
promise enforced only by application discipline is not enforced.

**Issues faced:** Four, three of them environmental and one genuinely instructive.

1. **Non-parseable POM.** XML comments cannot contain a double hyphen. My comment read
   `(CON-2) -- the whole point...` and Maven refused to read the file.

2. **Testcontainers could not find Docker** — `"Could not find a valid Docker environment"`. The
   Docker CLI worked fine, which made this look like a Testcontainers or socket-path problem. Two
   wrong guesses (`DOCKER_HOST`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`) changed nothing.

3. **Spotless crashed** with `NoSuchMethodError: Log$DeferredDiagnosticHandler.getDiagnostics()`.

4. **Foreign-key violation in the immutability test** — inserting into `transfers` failed with
   `violates foreign key constraint "transfers_from_account_fk"`, even though the test had just
   inserted that very account two lines earlier.

**Resolution:**

1. Rewrote the comment. Trivial, but a good reminder that a config file is code.

2. **The error message was a lie, and chasing it was the mistake.** Probing the socket directly with
   `curl` found the truth immediately: `/_ping` → 200, but `/v1.32/info` → **HTTP 400** with an empty
   stub body. `docker version` showed the engine speaks API 1.55 with a **minimum of 1.40** — and the
   `docker-java` client bundled with Testcontainers defaults to **v1.32**. The engine was rejecting
   the handshake, Testcontainers reported that as "no Docker environment," and so a version
   negotiation failure masqueraded as a missing installation.

   Fixed by pinning `api.version=1.44` in the failsafe/surefire config, so it holds for everyone and
   in CI without an environment variable anyone has to remember. Also upgraded Testcontainers
   1.20.4 → 1.21.3.

   *Lesson: when a tool says "X is not installed" and X plainly is, stop trusting the message and go
   talk to X yourself. Two `curl` calls found in seconds what two config guesses had missed.*

3. `google-java-format` 1.19.2 reaches into `javac` internals whose signature changed in recent JDK
   21 builds. Upgraded to 1.27.0 (and Spotless to 2.44.3). 1.25.2 was still too old.

4. **The most interesting failure, and not a bug.** `application.yml` sets HikariCP's
   `auto-commit: false`. So a statement issued *outside* a transaction is executed, never committed,
   and silently discarded when the connection returns to the pool — the `accounts` rows genuinely
   did not exist by the time the `transfers` insert ran on a different connection.

   This is correct and intended behavior: the service layer owns every transaction boundary
   (NFR-18). The test was wrong, not the config. Fixed by wrapping the setup in an explicit
   `TransactionTemplate` so it commits, with each trigger-violation attempt in its own transaction
   (the trigger's exception aborts the transaction it fires in, so they cannot share one).

   *Lesson: it is worth being slow to conclude "the config is wrong." Here the config was enforcing
   exactly the discipline we want, and the honest fix was to make the test respect it. Loosening
   `auto-commit` to make the test pass would have quietly removed a guardrail from the production
   write path.*

---
