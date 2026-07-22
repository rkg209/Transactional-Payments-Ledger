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

## [004] SPEC 0001 — double-entry core, atomic transfers, zero-sum proven — 2026-07-15

**Spec:** SPEC 0001

**What:** The first real ledger logic. `TransferService.execute()` posts a balanced transfer —
insert `transfers` (PENDING), insert exactly one DEBIT and one CREDIT `ledger_entries` row of equal
`amount_minor`, move both account balances, mark `COMPLETED` — in one ACID transaction, with no
locking (deliberately racy; SPEC 0004 fixes it). Concretely:

- `docs/adr/0002-jooq-codegen.md` — Testcontainers-codegen mechanism, with an addendum documenting
  a classpath-conflict fix discovered mid-implementation.
- `pom.xml` — `testcontainers-jooq-codegen-maven-plugin` bound to `generate-sources`, ArchUnit
  (`archunit-junit5`), `.mvn/jvm.config` pinning `-Dapi.version=1.44` for the Maven-JVM codegen run.
- `org.ledger.db`: `AccountRepository`, `TransferRepository`, `LedgerEntryRepository` (jOOQ over
  `org.ledger.db.generated`, regenerated from the live schema on every build, never committed).
- `org.ledger.account`: `AccountService`, `AccountResult`, `AccountNotFoundException`.
- `org.ledger.transfer`: `TransferService`, `TransferResult`, `TransferStatus`,
  `InsufficientFundsException`.
- `ArchitectureFitnessTest` — encodes planning/03-system-design.md §1.2's module matrix plus the
  headline rule: only `LedgerEntryRepository` may reference the generated `LedgerEntries` types.
- `AbstractPostgresIT` (singleton-container pattern) + five new `*IT` classes: `DoubleEntryCoreIT`,
  `InsufficientFundsIT`, `UnbalancedPostingIT`, `LedgerImmutabilityIT` (migrated out of
  `WalkingSkeletonIT`), `DoubleEntryPropertyIT` (200 seeded-random transfers over 8 accounts).

**Why:** FR-1…FR-6, NFR-2/3/14/18, CON-4/5. This is the correctness bedrock every later spec rests
on — if money can be created or destroyed here, nothing above (idempotency, locking, sagas) can
save the system.

**How:** The zero-sum invariant is enforced *by construction*, not by checking afterward:
`LedgerEntryRepository.insertBalancedPair()` is the only write method it exposes, and it always
writes the DEBIT and CREDIT together from one `amountMinor`, so no code path can post a single
unbalanced entry. The insufficient-funds guard runs *before* any write, so rejection needs no
rollback. `db` repositories never import `org.ledger.transfer.TransferStatus` (an enum in a
higher-numbered module) — they write raw `"PENDING"`/`"DEBIT"` string literals matching the schema
CHECK constraints, respecting the `db → db.generated only` dependency rule. Test fixtures fund
accounts via `AbstractPostgresIT.seedInitialBalance()` — a raw-SQL balance write that intentionally
produces no matching `ledger_entries`, documented as representing pre-existing capital (a legacy
opening-balance import), not money this system created — and is explicitly excluded from the
"balance == Σ(entries)" assertion for that reason; every balance produced *through*
`TransferService` still satisfies it exactly.

**Issues faced:**

1. Docker Desktop was not running at all (no daemon socket) when codegen first ran; a stale
   `~/.testcontainers.properties` from an unrelated project also pinned a socket path that no
   longer existed.
2. Once Docker was up, `testcontainers-jooq-codegen-maven-plugin`'s own POM transitively pulls
   `flyway-mysql:9.16.3` and `jackson-annotations:2.10.3` — versions that don't match this
   project's `flyway-core:10.10.0` (Spring Boot 3.3.5's BOM) and `jackson-databind:2.15.2`. The
   plugin's self-first classloading strategy meant declaring newer `flyway-core` directly wasn't
   enough; the stale transitive jars stayed on the classpath and were binary-incompatible with the
   newer ones, producing `NoClassDefFoundError`s (`FlywayTeamsUpgradeRequiredException`, then
   `JsonKey`) that read like missing dependencies, not version conflicts.
3. `ArchitectureFitnessTest` failed two different ways on first run: (a) five rules for
   not-yet-built modules (`idempotency`, `saga`, `reconciliation`, `concurrency`) failed with
   "matched no classes" — ArchUnit's default is to treat an empty match as failure, not a vacuous
   pass; (b) the ledger-entries boundary rule fired 62 violations, all from jOOQ's own generated
   `Tables`/`Keys`/`Public`/`DefaultCatalog` classes cross-referencing `LedgerEntries` as internal
   schema wiring — not application code.
4. `block-float-money.sh`'s invariant-#2 half (no UPDATE/DELETE on `ledger_entries`) has no
   test-file exemption, unlike its invariant-#1 half's doc/spec exemption — so it blocked
   `LedgerImmutabilityIT`, which must contain literal `UPDATE ledger_entries` / `DELETE FROM
   ledger_entries` text to assert the trigger rejects them. This is the exact test `WalkingSkeletonIT`
   (SPEC 0000) already contained before this session moved it out.
5. `InsufficientFundsIT`'s min-balance-breach test tried to create an account with
   `minBalance=100` directly — but `accounts.balance` defaults to 0 at INSERT, so the row violated
   `accounts_balance_gte_min` (`0 < 100`) before any transfer logic ran at all.

**Resolution:**

1. Removed the stale `~/.testcontainers.properties`, launched Docker Desktop (`open -a Docker`),
   confirmed the socket at `~/.docker/run/docker.sock` was live before retrying.
2. Pinned `flyway-mysql` and `jackson-annotations` to the same versions as the rest of the
   toolchain directly in the plugin's `<dependencies>` block, even though `flyway-mysql` is never
   exercised (`database.type=POSTGRES` only) — a maintenance tax documented in ADR 0002's addendum
   so the next version bump doesn't have to re-diagnose it from scratch.
3. (a) Added `src/test/resources/archunit.properties` with `archRule.failOnEmptyShould=false`,
   matching the plan's explicit intent that non-existent-module rules pass vacuously until those
   specs add classes. (b) Narrowed the `that()` predicate to exclude
   `org.ledger.db.generated..` itself from the "who may reference LedgerEntries" check, since
   jOOQ's own generated plumbing legitimately cross-references every table.
4. Asked the user how to resolve the hook gap rather than working around it (e.g. string-splitting
   the SQL to dodge the regex, which would have defeated the guardrail's intent). User chose to fix
   the hook: added a narrow `src/test/**` exemption to *only* the invariant-#2 check (invariant #1,
   no-float-money, still applies to tests — a float bug in test code is still a float bug). An
   AI-auto-mode permission classifier caught and rejected my first attempt at this fix because it
   placed the exemption's `case` block before invariant #1's check, which would have silently
   disabled float-money detection for all test files too — a good catch; the corrected version
   scopes the exemption to only the invariant-#2 `if` condition.
5. Added `AbstractPostgresIT.seedAccountState(id, balance, minBalance)`, which sets both columns in
   one UPDATE after the account already exists, so the CHECK only needs to hold in the final state
   — `AccountService.createAccount` still cannot set an initial balance, by design, so
   min-balance-breach tests must be fixture-seeded, not created pre-breached.

Full build verified clean: `mvn clean verify` — 20/20 tests green (9 ArchUnit + 11 integration, all
against real Postgres via Testcontainers) — and `mvn spotless:check` clean. `/invariant-check` run
both against the Testcontainers test suite (`DoubleEntryPropertyIT`: both invariants asserted after
every one of 200 random transfers) and directly via SQL against a `docker compose`-launched
Postgres seeded with a production-equivalent transfer: `global_sum = 0`, and per-account
`balance == Σ(entries)` held exactly for every account with no fixture seed. Zero drift.

---

## [005] SPEC 0002 — Transfer API: HTTP surface, auth, errors, pagination, OpenAPI — 2026-07-21
**Spec:** SPEC 0002
**What:** Added the `api` layer end to end: `AccountController` (`POST/GET /accounts`,
`GET /accounts/{id}`, `GET /accounts/{id}/balance`) and `TransferController`
(`POST /transfers`, `GET /transfers/{id}`); the full DTO set (`org.ledger.api.dto`); `ErrorCode` +
`GlobalExceptionHandler` covering every row of planning/05-api-design.md §3.2; a `RequestIdFilter`;
`SecurityConfig` + `ApiKeyAuthFilter` + `ApiKeyAuthenticationEntryPoint` (API-key auth on
`/api/v1/**`); cursor pagination (`Cursor`, `AccountRepository.findPage`,
`AccountService.listAccounts`) backed by new migration `V3__accounts_pagination_index.sql`; a
springdoc `OpenApiConfig` serving `/v3/api-docs` and Swagger UI; `demo.sh`. Widened `TransferResult`
to the full row and added `TransferService` guards (`SameAccountException`,
`InvalidAmountException`, `CurrencyMismatchException`) ahead of the balance check. Added ADR 0003
(API-key auth over JWT) and ADR 0004 (cursor pagination + springdoc as the OpenAPI source of truth,
superseding the stale `planning/05-openapi.yaml`). New tests: `TransferApiIT`, `SecurityIT`,
`ErrorModelIT`, `InternalErrorIT`, `GlobalExceptionHandlerUnitTest`, `PaginationIT`, `OpenApiIT`.
**Why:** FR-7, FR-8, FR-9, FR-12, FR-13, NFR-22, DR-8 — none of SPEC 0001's transfer logic was
reachable over HTTP before this; the acceptance bar was a curl-able transfer with the total
conserved, every §3.2 error reachable, and an OpenAPI document that cannot drift from the real
controllers.
**How:** Resolved two planning-doc conflicts explicitly in the plan before writing code (see ADR
0004): cursor pagination per the authoritative §5 prose rather than the stale yaml's offset
envelope, and a springdoc-generated OpenAPI document rather than hand-maintaining the yaml (which
has no `paths:` section at all). Exception types whose *trigger* belongs to a later spec
(`IdempotencyFingerprintMismatchException`, `IdempotencyTimeoutException`,
`OptimisticLockException`, `SagaCompensatedException`, `SagaNotFoundException`) were declared now,
each in a package `api` is actually allowed to depend on per the §1.2 matrix, so
`GlobalExceptionHandler`'s mapping is provably correct before SPEC 0003/0004/0006 add a throw site.
`OptimisticLockException` specifically went into `org.ledger.transfer`, not `org.ledger.concurrency`
— see Issues faced #1.
**Issues faced:**
1. The plan's own module table (`planning/03-system-design.md` §1.3, "`GlobalExceptionHandler` maps
   `OptimisticLockException`") is in direct tension with §1.2's dependency matrix (`api` must not
   import `concurrency`). Referencing a class — even without a static import — is a real bytecode
   dependency ArchUnit checks, so `GlobalExceptionHandler` catching an `org.ledger.concurrency.*`
   type would fail `ArchitectureFitnessTest` the moment that package gained classes.
2. The formatter hook (spotless, run after every `Write`/`Edit`) fires between edits, not just at
   the end. Splitting an import addition and its first use across two separate `Edit` calls let
   `removeUnusedImports` strip the "unused" import after edit 1, before edit 2 added the usage that
   would have justified it — `AccountService`, `AccountRepository`, and `ApiKeyAuthFilter` all lost
   an import this way and failed to compile.
3. `ApiKeyAuthFilter`, registered via `addFilterBefore`, runs on *every* request regardless of the
   `authorizeHttpRequests` rules — it is not scoped by them. The first version unconditionally
   demanded a valid key before the authorization chain ever got to evaluate `permitAll`, so
   `/health`, `/actuator/health`, and the springdoc routes all returned 401 instead of the intended
   200. `WalkingSkeletonIT` (a SPEC 0000 test, untouched by this spec) caught it immediately.
4. `jOOQ`'s plain-SQL `dsl.execute(String, Object...)` bound an `OffsetDateTime` bind value as
   `character varying` rather than `timestamptz` in `PaginationIT`'s raw backdated INSERT, so
   Postgres rejected the insert (`column "created_at" is of type timestamp with time zone but
   expression is of type character varying`).
5. `@MockBean`-based tests (`InternalErrorIT`) failed to load their Spring context: Mockito/Byte
   Buddy on this machine's Java 26 JDK (`JAVA_HOME` newer than the 21 this project targets, and
   newer than Byte Buddy officially supports) refused to subclass the concrete `AccountService`
   for mocking.
6. `docker compose up -d --build` cannot build the app image in this sandboxed environment: `mvn
   package` inside the Dockerfile's build stage runs the jOOQ Testcontainers codegen plugin, which
   needs a Docker socket that a plain `docker build` does not provide access to. Pre-existing
   Dockerfile limitation from SPEC 0000/0001, not introduced here.
**Resolution:**
1. Declared `OptimisticLockException` in `org.ledger.transfer` instead of `org.ledger.concurrency`
   — `TransferService` is the class that actually coordinates `ConcurrencyStrategy` and would
   surface an exhausted retry, and `api` is allowed to depend on `transfer`. Documented the
   reasoning directly in the exception's Javadoc so a future reader doesn't "fix" it back into
   `concurrency` and reintroduce the ArchUnit violation.
2. Re-added the three stripped imports by hand and re-ran `mvn compile` to confirm. Lesson for this
   session: when an edit adds a member that uses a type not yet imported, add the import in the
   *same* `Edit` call, not a follow-up one — the formatter hook runs in between and has no way to
   know a later edit is coming.
3. Extracted the five permitted-route patterns into one shared `PublicPaths.PATTERNS` array used by
   both `SecurityConfig`'s `permitAll()` matchers and a new `ApiKeyAuthFilter.shouldNotFilter`
   override (`AntPathMatcher` against `getServletPath()`), so the filter and the authorization rule
   can never drift apart, and re-ran the full suite to confirm `/health`/`/actuator/**` came back to
   200.
4. Cast the bind placeholder explicitly (`?::timestamptz`) and passed the timestamp as its
   `toString()` form instead of the raw `OffsetDateTime`, which resolved the type ambiguity.
5. Isolated the `@MockBean` test into its own class and passed
   `-Dnet.bytebuddy.experimental=true` to both the surefire and failsafe plugin configuration in
   `pom.xml` (alongside the existing Docker API version pin) — a general fix for this class of test
   on newer JDKs, not a workaround limited to one test.
6. Not fixed in this spec (out of scope for SPEC 0002 — it's a build-pipeline concern, not an API
   concern). Verified `demo.sh` and the acceptance criteria instead against `docker compose up -d
   postgres` plus `mvn spring-boot:run` on the host, which is a faithful stand-in: the same jar,
   the same migrations, the same Postgres image, just built outside the container. Flagged in
   `specs/0002-transfer-api.md` for whoever next touches the Dockerfile.

Full build verified clean: `mvn -B verify` — 65/65 tests green (9 ArchUnit + 56 unit/integration,
all against real Postgres via Testcontainers) — and `mvn spotless:check` clean. Manually verified
against a running stack: unauthenticated `GET /api/v1/accounts` → 401, authenticated → 200,
`/v3/api-docs` → 200, Swagger UI renders. `/invariant-check` run via raw SQL against the
`docker compose`-launched Postgres after `demo.sh`: `global_sum = 0` (the invariant that matters).
The per-account `balance == Σ(entries)` check showed expected, documented non-zero deltas for
accounts with an out-of-band `seedInitialBalance`-style seed (exactly the carve-out SPEC 0001's
`AbstractPostgresIT` already documents) — not drift.

---

## [006] SPEC 0003 — Idempotency: exactly-once via a unique-constraint claim — 2026-07-21
**Spec:** SPEC 0003
**What:** `IdempotencyFilter` (`org.ledger.idempotency`), `FingerprintService`,
`CachedBodyHttpServletRequest`, `IdempotencyKeyRepository` (`org.ledger.db`), registered after
`ApiKeyAuthFilter` in `SecurityConfig`. `TransferController`/`TransferService`/`TransferRepository`
now thread the claimed key through to `transfers.idempotency_key`, making that column's secondary
unique guard live. `SecurityErrorResponseWriter` promoted and renamed to the public
`api.error.ErrorResponseWriter` (with a `details` overload) so both `ApiKeyAuthFilter` and
`IdempotencyFilter` can render the standard error envelope from outside the DispatcherServlet.
New tests: `IdempotencyReplayIT`, `ConcurrentDuplicateIT` (100 reps), `FingerprintMismatchIT`,
`FailedKeyRetryIT`. New `docs/adr/0005-idempotency-via-unique-constraint.md`.
**Why:** FR-14–FR-18, NFR-5. Closes the one hole in the project's headline claim — until this spec,
`POST /transfers` and `POST /accounts` required `Idempotency-Key` but ignored it, so a retried
request double-applied.
**How:** A single `INSERT ... ON CONFLICT DO NOTHING` on `idempotency_keys.key` is the concurrency
primitive (never `SELECT`-then-`INSERT`, which locks nothing for a row that doesn't exist yet). The
filter is deliberately not `@Transactional`: the claim commits and is visible to a racing request
immediately, rather than being held open for the guarded operation's full duration. 4xx responses
mark the key `COMPLETED` (replayed verbatim on retry); 5xx/unhandled marks it `FAILED` (retryable,
via a `WHERE status = 'FAILED'`-guarded reclaim so two racing retries can't both win). Replays
always return `200`, never the original `201`. Losers of the claim race poll every 25 ms for up to
1 s before giving up with `503 IDEMPOTENCY_TIMEOUT`.
**Issues faced:**
1. The exact import-stripping trap already documented in entry 005: adding an import in one `Edit`
   call and its first use in a follow-up call let the Spotless formatter hook — which runs between
   edits, not just at the end — remove the "unused" import before the usage landed. Hit this
   repeatedly across `IdempotencyFilter`, `SecurityConfig`, `ApiKeyAuthFilter`,
   `ApiKeyAuthenticationEntryPoint`, and `TransferController` while wiring the new filter in.
2. The real bug: `IdempotencyReplayIT`'s first assertion passed, but every retry re-executed the
   transfer instead of replaying it, eventually failing on `transfers_idempotency_key_uq`. Root
   cause — `application.yml` sets `spring.datasource.hikari.auto-commit: false` project-wide
   (deliberately, per its own comment: "HikariCP must never silently retry a failed transaction").
   `IdempotencyKeyRepository`'s writes ran outside any `@Transactional` boundary (by design — see
   the ADR on why the filter itself must not be `@Transactional`), so with auto-commit off, each
   write appeared to succeed (`Affected row(s): 1`, visible within its own connection) but was
   silently rolled back the moment its connection returned to the pool, because nothing ever called
   `commit()`. A direct repository-level repro (two sequential `tryClaim` calls, then
   `SELECT count(*)`) confirmed the row count was 0 immediately after two "successful" claims.
   Every other DB-writing method in the codebase already routes through an `@Transactional` service
   method for exactly this reason; the idempotency repository, called directly from a filter, was
   the first to be exempt.
3. A minor correctness gap surfaced during the manual `curl` verification pass: the replay response
   used `response.setContentType("application/json")` without `setCharacterEncoding`, so
   `getWriter()` defaulted to ISO-8859-1 — harmless for the ASCII bodies in this spec's tests, but a
   latent bug against the "byte-for-byte replay" claim for any non-ASCII content.
**Resolution:**
1. Re-added each stripped import in the same `Edit` call as (or immediately after confirming) its
   usage was already present in the file, rather than in a separate preceding call.
2. Marked every `IdempotencyKeyRepository` method `@Transactional` (`readOnly = true` on `find`), so
   each call opens and commits its own transaction despite the connection pool's auto-commit being
   off — restoring the "three separate, individually committed statements" the design calls for.
   Documented the reason directly in the repository's Javadoc so it isn't "simplified" away later.
3. Added `response.setCharacterEncoding(StandardCharsets.UTF_8.name())` alongside the content-type
   on the replay path.

Full build verified clean: `mvn -B verify` — 143/143 tests green (9 ArchUnit + 134 unit/integration,
including 100 repetitions of `ConcurrentDuplicateIT`, all against real Postgres via Testcontainers)
— and `mvn spotless:check` clean. Manually verified against `docker compose up -d postgres` +
`mvn spring-boot:run`: same key + body twice → `201` then `200` with `X-Idempotent-Replayed: true`
and a byte-identical body; same key + different amount → `422 IDEMPOTENCY_KEY_REUSE`;
`idempotency_keys` rows show `COMPLETED` with 64-char fingerprints; `transfers.idempotency_key` is
populated. `/invariant-check` against that same manual run: `global_sum = 0`; the three accounts
showing a nonzero `balance - Σ(entries)` delta all carry an out-of-band seeded balance from this
manual session (the same documented, non-drift carve-out as SPEC 0001/0002), not real drift.

---

## [007] SPEC 0004 — Concurrency control: optimistic and pessimistic locking — 2026-07-21
**Spec:** SPEC 0004
**What:** New `org.ledger.concurrency` package (`ConcurrencyStrategy`, `LockedAccounts`,
`OptimisticStrategy`, `PessimisticStrategy`, `ConcurrencyConflictException`); two new
`AccountRepository` methods (`findOrdered`/`findOrderedForUpdate`, both a single
`WHERE id IN (?,?) ORDER BY id [FOR UPDATE]` statement) plus `applyDeltaIfVersion`; a rewritten
`TransferService.execute()` — a plain bounded retry loop (`ledger.concurrency.max-attempts`,
default 5, backoff `ledger.concurrency.backoff-base-ms * 2^(attempt-1)` capped at 800ms) around one
`TransactionTemplate` transaction per attempt; new `infrastructure/ConcurrencyConfig` (composition
root: picks the strategy bean and the transaction isolation level from config, both fail-fast on an
unrecognized value) and `LedgerConcurrencyProperties`; `docs/adr/0006-optimistic-vs-pessimistic.md`;
nine new tests under `src/test/java/org/ledger/concurrency/`.
**Why:** FR-19..23 / NFR-3 / NFR-14 — SPEC 0001 shipped a knowingly racy read-then-write
(`TransferService.java`, `AccountRepository.applyDelta`), documented in both files' Javadoc as
"SPEC 0004 replaces this." This closes it with both candidate locking disciplines behind one seam,
so SPEC 0008 picks a production default from a benchmark, not a guess.
**How:** Per ADR 0006: no AOP (the project has no `spring-boot-starter-aop`, and
`@Transactional(isolation=...)` cannot be runtime-configured anyway) — a plain retry loop around
`TransactionTemplate` gets retry-outside-the-transaction and per-transaction isolation from one
construct, visible at the call site. Both strategies' `lockAndLoad` issue one statement locking (or
reading) both rows in ascending id order — structural, not a caller convention. The retry loop
catches `ConcurrencyConflictException` and any exception whose unwrapped `SQLState` is `40001`
(serialization failure) or `40P01` (deadlock detected), so `ledger.isolation-level=serializable`
retries instead of 500ing. Tests: an abstract base + `Optimistic.../Pessimistic...` subclass per
scenario (`ConcurrencyHammerIT` — 32 threads × 25 transfers into one hot account;
`DeadlockIT` — bidirectional A→B/B→A under a hard timeout; `NegativeBalanceIT` — 20 concurrent
withdrawals against exactly-enough-for-one balance), a `SerializableConcurrencyHammerIT` variant,
plus a Mockito-based `TransferServiceRetryUnitTest` and a deterministic `OptimisticConflictIT` for
the retry/CAS mechanism itself (see Issues faced — racing real transaction timing for these two
turned out not to be reproducible on demand).
**Issues faced:**
1. **A real Postgres deadlock, not just a CAS conflict, in the optimistic strategy.**
   `OptimisticDeadlockIT` failed with `ERROR: deadlock detected` — from Postgres itself, which
   should be impossible for a strategy that never takes an explicit lock. Root cause:
   `TransferService.executeOnce` applied deltas in request order (`fromAccountId` then
   `toAccountId`), and a plain `UPDATE` always takes an implicit row lock regardless of isolation
   level or the `WHERE version = ?` predicate. Two opposite-direction transfers (A→B and B→A) each
   locked their own "from" row first and then blocked waiting for the other's row — the exact
   deadlock the plan's lock-ordering discipline was supposed to prevent, except that discipline was
   only implemented in `lockAndLoad`'s read, not in the write order that actually takes the lock for
   the lock-free optimistic path.
2. **`OptimisticConflictIT`'s own competing write silently rolled back.** The test called
   `accountRepository.applyDeltaIfVersion(...)` directly to simulate a competing commit; it reported
   1 row affected, but a subsequent read still showed the pre-update balance. This is the exact bug
   already documented in entry [006]: `spring.datasource.hikari.auto-commit=false` project-wide
   means any write issued without an explicit surrounding transaction appears to succeed on its own
   connection and is silently discarded when that connection returns to the pool.
3. **Default `ledger.concurrency.max-attempts=5` was not enough for the test's own contention
   level.** `OptimisticConcurrencyHammerIT` (32 threads, one hot account), `OptimisticDeadlockIT`
   (once fixed per #1, still 32 threads racing two accounts), and `SerializableConcurrencyHammerIT`
   all exhausted the production-default retry budget under real, maximally-adversarial contention.
4. **"FATAL: sorry, too many clients already."** Nine new `@TestPropertySource` combinations means
   nine more distinct Spring contexts, each with its own up-to-20-connection Hikari pool, several
   alive at once under the Spring test context cache — pushing total connections past Postgres's
   default `max_connections=100`.
**Resolution:**
1. Changed `executeOnce` to apply both deltas in ascending-id order (matching `lockAndLoad`'s read
   order) regardless of which account is `from` and which is `to` — whichever id sorts first gets
   its `UPDATE` issued first in every transfer, so two opposing transfers can never each hold one
   row while waiting on the other.
2. Wrapped every direct repository/strategy call in the test with `tx.execute(...)` /
   `tx.executeWithoutResult(...)`, matching how these methods are actually invoked in production
   (inside `TransferService`'s `TransactionTemplate`).
3. Raised `ledger.concurrency.max-attempts` (and lowered `backoff-base-ms`) via
   `@TestPropertySource` on the specific hammer/deadlock test classes that hammer a hot account with
   32 real concurrent threads, rather than loosening the production default — the production value
   is SPEC 0008's job to tune against a real benchmark, and 5 stays the documented, deliberate
   starting point for that comparison.
4. Raised the shared Testcontainers Postgres's `max_connections` to 300 via
   `.withCommand("postgres", "-c", "max_connections=300")` in `AbstractPostgresIT` — fixes the root
   cause (more Spring contexts than the default anticipates) once, rather than shrinking every new
   test class's Hikari pool individually.

Full suite green: `mvn -B verify` — 167/167 tests (16 via Surefire: 9 ArchUnit + 2
`TransferServiceRetryUnitTest` + 5 `GlobalExceptionHandlerUnitTest`; 151 via Failsafe across every
`*IT` class, including all four new scenarios under both strategies plus the `SERIALIZABLE`
variant) — and `mvn spotless:check` clean. Manually verified against `docker compose up -d
postgres`: `CONCURRENCY_STRATEGY=optimistic`
and `=pessimistic` both start and log their active strategy at `INFO`;
`CONCURRENCY_STRATEGY=bogus` fails startup with `Unrecognized ledger.concurrency-strategy 'bogus'`
rather than silently defaulting. 20 concurrent HTTP transfers against a hot account under the
running `optimistic` instance, checked directly via `psql`: `global_sum = 0`, and the unseeded
destination account's `balance` matched its entry sum exactly; the only accounts with a nonzero
delta were ones with an out-of-band seeded starting balance (the same documented, non-drift
carve-out as prior entries).

---

## [008] Phase 4 checkpoint — strong manual/E2E verification pass — 2026-07-21
**Spec:** Fix
**What:** Full verification sweep of everything implemented through SPEC 0004 before moving on to
SPEC 0005. Ran `mvn -B verify` (151 Failsafe integration tests + 16 Surefire tests, including 9
ArchUnit boundary checks — all green) and `mvn spotless:check` (clean). Then exercised the real
running stack end to end: auth (missing/invalid key), account creation, transfers, the full
idempotency lifecycle (first request, byte-identical replay with `X-Idempotent-Replayed: true`,
same-key-different-body rejection, missing-key rejection), every documented error path
(`INSUFFICIENT_FUNDS`, `SAME_ACCOUNT_TRANSFER`, `CURRENCY_MISMATCH`, `INVALID_AMOUNT`,
`ACCOUNT_NOT_FOUND`, `MALFORMED_REQUEST`, `VALIDATION_ERROR`), cursor pagination, public
OpenAPI/Swagger routes, the `ledger_entries` append-only trigger (`UPDATE`/`DELETE` both correctly
rejected), the global and per-account zero-sum invariant via direct SQL, and a 300-request /
100-unique-key concurrent duplicate-key smoke test against one hot account (100 real `201`s, 200
correctly-replayed `200`s, 0 double-charges, `global_sum` stayed exactly 0 throughout). Fixed two
real bugs found along the way (`Dockerfile`, `Makefile`, `.dockerignore` (new), `demo.sh`). Added
`MANUAL_TESTING.md` at the repo root — a step-by-step guide covering every implemented feature,
gitignored (local-only, not part of the committed SDD trail) — for the user's own manual sign-off
gate before SPEC 0005 starts.
**Why:** User asked for a strong testing pass across everything built so far (phase 4 = specs
0000–0004), with any bugs found fixed immediately, plus a durable manual-testing reference so they
can independently verify and approve before the next phase begins.
**How:** Treated the automated suite as necessary but not sufficient — it was already 151/151
green — and instead tried to actually *use* the service the way an operator would: build the real
Docker image, start the real stack, hit it with `curl`, read real Postgres state directly. That is
what surfaced both real bugs below; neither would show up in `mvn verify`, because Testcontainers
tests never build the Docker image or exercise `docker compose` at all.
**Issues faced:**
1. **`make up` / `docker compose up --build` failed outright — a genuine blocker for anyone trying
   to run the project as documented.** The `Dockerfile`'s build stage ran `mvn -B -q clean package
   -DskipTests` inside the image build. `mvn package` triggers jOOQ code generation
   (`generate-sources` phase, `testcontainers-jooq-codegen-maven-plugin`, see ADR 0002), which spins
   up a real Postgres container via Testcontainers to introspect the schema — and that requires a
   Docker daemon, which a plain `docker build` build stage has no access to (no
   Docker-in-Docker/socket mount configured anywhere). Every build failed at
   `generate-jooq-sources` with `Could not find a valid Docker environment.` This had evidently
   never been exercised end to end before — `mvn verify` (used everywhere else, including CI) runs
   on the host, which *does* have a Docker daemon, so the same codegen step always worked there.
2. **`demo.sh`'s idempotency section actively lied about current behavior.** It printed "SPEC 0002
   requires the Idempotency-Key header but does not yet enforce it ... Today this DOUBLE-APPLIES
   the transfer" — accurate when written (before SPEC 0003), false now, and it never asserted
   anything: it just printed the claim and moved on, so nobody running the script would notice it
   was wrong. Running it confirmed the replay was in fact correctly a no-op (balance unchanged,
   `X-Idempotent-Replayed: true`) — the code was right, only the script's narrative was stale.
3. Several of my own manual `curl` attempts were simply wrong and briefly looked like bugs: sending
   `X-API-Key` instead of `Authorization: ApiKey <key>` (per ADR 0003), hitting `/accounts` instead
   of `/api/v1/accounts`, sending `amountMinor` instead of the DTO's actual `amount` field, and
   first checking the zero-sum invariant with a naive `SUM(amount_minor)` — which is meaningless
   because the schema stores `amount_minor` as an unsigned magnitude and encodes the sign in
   `direction` (by design, see `04-database-schema.sql`), so every real transfer summed to `2 ×
   amount` instead of `0` until the query accounted for `direction`.
**Resolution:**
1. Rewrote `Dockerfile` to a single runtime-only stage (`eclipse-temurin:21-jre-alpine`) that
   copies a prebuilt `target/payments-ledger-*.jar` rather than compiling anything itself. Added a
   `make jar` target (`mvn -B -q clean package -DskipTests`, run on the host where Testcontainers
   can already reach Docker normally) and made `make up` depend on it. Added a `.dockerignore` so
   the build context sent to the daemon doesn't include `target/generated-sources`, `target/classes`,
   `.git`, etc. Verified: `make up` now builds and starts cleanly, `docker compose ps` shows both
   containers healthy, `/health` returns `{"status":"UP","database":"UP"}`.
2. Rewrote the replay section of `demo.sh` to actually assert: it captures the response headers
   from the replay request and fails with a non-zero exit if `X-Idempotent-Replayed: true` is
   missing, and compares balances before/after the replay, failing loudly on any mismatch instead
   of narrating an outcome nobody checked. Re-ran `./demo.sh` against a freshly truncated database —
   completes with no `MISMATCH` and the correct assertions holding.
3. Corrected the test commands (right header, right path, right field name, direction-aware SQL).
   Once corrected, every endpoint, every documented error code, pagination, the immutability
   trigger, and the zero-sum invariant (global and per-account, both via raw SQL and via a live
   300-request concurrent-duplicate load) behaved exactly per spec — nothing else needed fixing.

Full suite still green after the fixes (`mvn -B verify`, `mvn spotless:check`). Manual verification
against the rebuilt `docker compose` stack: 100 unique transfers + 200 correctly-replayed
duplicates out of 300 concurrent requests over 100 shared idempotency keys, `global_sum = 0`
throughout, `SELECT count(*) FROM transfers WHERE idempotency_key LIKE 'hammer-%'` = exactly 100.

---

## [009] SPEC 0005 — Invariant & reconciliation: standing checker, not just a manual skill — 2026-07-21
**Spec:** SPEC 0005
**What:** New `org.ledger.reconciliation` package (`ReconciliationService`, `ReconciliationReport`,
`AccountDrift`), `org.ledger.db.ReconciliationRepository` + `AccountDriftRow`, the per-account drift
query (`LedgerEntryRepository.findDriftedAccounts()`), `ReconciliationController`
(`GET /api/v1/reconciliation/report`, `POST /run`), `api/dto/ReconciliationReportResponse` +
`AccountDriftDetail`, `infrastructure/SchedulingConfig` (`@Scheduled`, gated by
`ledger.reconciliation.scheduled`), ADR 0007, and six new ITs under
`src/test/java/org/ledger/reconciliation/`. `AbstractPostgresIT` gained `createGenesisAccount`,
`fundFromGenesis`, and `injectDrift` fixtures.
**Why:** FR-11/FR-24/FR-25/FR-26/NFR-20 — every prior spec asserted "entries sum to zero" and
"balance == Σentries" only inside its own tests; nothing continuously re-derived either invariant
from the ledger at runtime, persisted the verdict, or screamed when it failed.
**How:** `ReconciliationService.runCheck()` uses two plain `TransactionTemplate`s (read-only, then
`REQUIRES_NEW` for the report insert) instead of `@Transactional` annotations, because it calls its
own read step and write step on `this` — a self-invocation that never goes through the Spring proxy
`@Transactional` depends on, same reasoning `TransferService` already uses `TransactionTemplate` for.
The per-account query is a `LEFT JOIN` (an account with a balance and zero entries is drift too;
`INNER JOIN` — the form planning/03 §3.3 and the `/invariant-check` skill originally used — makes
that case invisible) and reads `accounts.balance` and the grouped `ledger_entries` sum in one SQL
statement, so both sides come from one snapshot under READ COMMITTED. Metrics: a gauge
(`reconciliation.global_sum`) bound once to a held `AtomicLong`, plus two counters
(`reconciliation.drift.count`, `reconciliation.runs.total`).
**Issues faced:**
1. The drift query originally lived in `ReconciliationRepository` and referenced the generated
   `LedgerEntries` types directly — `ArchitectureFitnessTest.only_ledger_entry_repository_touches_ledger_entries_generated_types`
   failed (violated 11 times), since only `LedgerEntryRepository` may reference those types.
2. The plan's genesis-account design called for a deeply negative `min_balance` so genesis alone
   could fund other accounts while every account, including genesis, still satisfied
   `balance == Σentries` exactly. `accounts_min_balance_gte_zero` (`CHECK (min_balance >= 0)`,
   unconditional, every row, both INSERT and UPDATE) makes that impossible: no account in this
   schema can ever go negative, so no account can donate the first unit of capital to another
   without already holding it.
3. `AbstractPostgresIT.createGenesisAccount` initially called `accountRepository.insert(...)`
   directly with no wrapping transaction; with `spring.datasource.hikari.auto-commit=false`, the
   insert was silently never committed, and the very next statement in the same test
   (`fundFromGenesis`) failed with `AccountNotFoundException` against an ID that had, from the
   test's point of view, just been created.
4. `ReconciliationApiIT`'s unauthenticated-POST assertion failed with `ResourceAccess: cannot retry
   due to server authentication, in streaming mode` — a `TestRestTemplate`/`HttpURLConnection`
   limitation retrying a POST body after a 401 challenge, not a server defect.
5. `ReconciliationScheduleIT` (the one IT that runs the real `@Scheduled` job) intermittently failed
   its `@BeforeEach` `TRUNCATE` with `DeadlockLoserDataAccessException`: a scheduled run's read
   transaction and the test's `TRUNCATE` can legitimately take locks in opposite order when a
   scheduled run is mid-flight at test start.
6. `ReconciliationPerformanceIT`'s `EXPLAIN` showed a plain `Index Scan` on the narrower
   `idx_ledger_entries_account_id`, never `Index Only Scan` on the wider covering
   `idx_ledger_entries_reconciliation`, even after `ANALYZE` and even with `enable_seqscan = off`.
**Resolution:**
1. Moved `findDriftedAccounts()` into `LedgerEntryRepository` itself (it already owns every other
   read/write against `ledger_entries`); `ReconciliationRepository` now just delegates to it, the
   same pattern `globalEntrySum()` already used.
2. Accepted that a truly zero-drift genesis is not achievable under this schema and changed the
   design instead of fighting it: genesis is seeded once with a known constant
   (`GENESIS_STARTING_CAPITAL`, via the existing `seedInitialBalance` — the same "capital that
   predates this ledger" concept already documented there) and funds every other test account
   through real `TransferService` transfers. `balance` and `entrySum` move together, unit for unit,
   for every transfer out of genesis, so its drift is fixed at exactly the seed amount forever,
   independent of how much is drawn from it — reconciliation tests assert on that one known,
   named account explicitly instead of expecting zero drift system-wide. Recorded as ADR 0007
   decision 5, with the corrected rationale (the schema is right to forbid this; some account must
   still start from an external seed).
3. Wrapped the insert in `tx.execute(...)`, matching how `seedInitialBalance`/`injectDrift` already
   wrap their writes. Lesson: a test helper that calls a bare repository method (bypassing the
   `@Transactional` service layer on purpose) must open its own transaction explicitly — nothing
   else will.
4. Followed `SecurityIT`'s existing convention (already GET-only for exactly this reason) rather
   than fighting the client library: `ReconciliationApiIT` now asserts 401 via `GET` only.
5. Added a bounded retry-on-deadlock loop to `AbstractPostgresIT.resetDatabase()`, mirroring
   `TransferService`'s own retry-on-serialization-failure/deadlock handling for the identical
   SQLSTATE — the right fix for two genuinely concurrent, correct transactions racing, not a bug in
   either one.
6. Discovered `ANALYZE` alone does not set the visibility map's all-visible bits that
   `Index Only Scan` needs to skip the heap — only `VACUUM` does. Added a `vacuumAnalyze` helper that
   runs `VACUUM ANALYZE` via a raw connection with autocommit toggled on (`VACUUM` cannot run inside
   a transaction block, and `TransactionTemplate` always opens one), and forced
   `enable_seqscan`/`enable_indexscan`/`enable_bitmapscan` off for the `EXPLAIN` itself, since at
   this fixture's small scale Postgres's cost-based planner correctly prefers a plan the test isn't
   trying to rule out — the point of the test is that the covering index is index-only-scannable at
   all, not that Postgres always chooses it unprompted at a few thousand rows.

Full suite green: `mvn -B verify` — 160 tests, 0 failures, 0 errors. `mvn spotless:check` clean.
`.claude/skills/invariant-check/SKILL.md` corrected to the `LEFT JOIN` form; `specs/0005-invariant-reconciliation.md`
status flipped to `implemented`.

---
## [010] SPEC 0006 — Sagas & multi-step transfers with compensation — 2026-07-22
**Spec:** SPEC 0006
**What:** Implemented the `org.ledger.saga` module (`SagaOrchestrator`, `LegTransferStep`,
`SagaDefinition`/`SagaLeg`/`SagaContext`, `SagaState`/`SagaStepState`, `SagaFailedException`,
`SagaRecoveryRunner`), `SagaRepository` (`org.ledger.db`), `TransferRepository.findByIdempotencyKey`,
the API surface (`POST /api/v1/transfers/saga`, `GET /api/v1/sagas/{sagaId}`,
`CreateSagaTransferRequest`/`SagaStepRequest`/`ValidSagaSteps`/`SagaStepsValidator`,
`SagaResponse`/`SagaStepResponse`, `SagaController`), and the Tomcat pre-accept-ordering wiring
(`TomcatConnectorGate`, `TomcatConnectorPauseCustomizer` in `org.ledger.infrastructure`). No Flyway
migration needed — `sagas`/`saga_steps` and their recovery indexes already existed. Added ADR 0008
recording the chain-of-legs decision. Four new saga ITs plus one standalone startup-wiring IT, all
under `src/test/java/org/ledger/saga/`. Updated `OpenApiIT` for the two new documented endpoints
(7 → 9 `/api/v1` paths).
**Why:** FR-27 through FR-31 (multi-leg transfers, forward/compensate, crash-safe recovery before
the server accepts traffic) and NFR-4/NFR-10/CON-7. The two endpoints, the `sagas`/`saga_steps`
schema, and the placeholder `SagaNotFoundException`/`SagaCompensatedException` classes were already
staged by earlier specs — this is what actually wires them up.
**How:** `planning/05-openapi.yaml`'s `CreateSagaTransferRequest` describes a flat, fan-out-shaped
`{type: DEBIT|CREDIT, accountId, amount}` step list that does not fit `transfers`' hard two-account
`NOT NULL (from_account_id, to_account_id)` shape. Raised this with the project owner; chosen
resolution (recorded in ADR 0008) is chain-of-legs: the wire schema is kept as-is, but a class-level
bean-validation constraint (`@ValidSagaSteps`) requires an even-length, alternating `DEBIT`/`CREDIT`
sequence of equal-amount, different-account pairs — exactly the spec's own `A→B→C` example — and
`SagaController` reshapes a validated request into `List<SagaLeg>`. Each leg is a complete, ordinary
transfer, so there is exactly one `SagaStep` implementation (`LegTransferStep`), not the three
(`DebitAccountStep`/`CreditAccountStep`/`RecordTransferStep`) `planning/03-system-design.md` sketched
for the fan-out model. `forward()`/`compensate()` both route through `TransferService.execute`
*unchanged*, keyed by a deterministic `saga:{id}:step:{i}:forward`/`:compensate` idempotency key;
`LegTransferStep` pre-checks `TransferRepository.findByIdempotencyKey` before calling
`TransferService.execute`, which is what makes a retried forward/compensate (from
`SagaOrchestrator.recover`) safe — it finds the leg that already committed instead of double-posting
or racing `transfers_idempotency_key_uq`. `SagaOrchestrator.execute` persists each `saga_steps` row
as `IN_PROGRESS` in its own committed transaction *before* calling `forward()` ("persist intent
before acting"), so an `IN_PROGRESS` row found on restart is ambiguous by construction; `recover()`
disambiguates it with the same idempotency-key lookup (found = committed = treat as `COMPLETED`; not
found = never ran = leave alone), then either marks the saga `COMPLETED` (every leg completed) or
compensates every `COMPLETED` leg in reverse and marks it `COMPENSATED` — never resuming forward
progress. Every state write checks the current value first, so a second `recover()` pass against an
already-terminal saga writes nothing (`SagaRecoveryIdempotenceIT` asserts `updated_at` is bit-for-bit
unchanged). "Recovery runs before the HTTP server accepts traffic" is enforced structurally, not by
runner ordering: `TomcatConnectorPauseCustomizer` pauses the embedded connector the instant it is
created (Spring Boot's `ApplicationRunner`s otherwise fire *after* the connector is already accepting
connections), and `SagaRecoveryRunner` resumes it via `TomcatConnectorGate` only after every
recoverable saga has been attempted; a fatal error just *listing* recoverable sagas leaves the
connector deliberately paused forever, rather than silently accepting traffic. `SagaRepository`
follows `TransferRepository`'s existing convention of taking/returning plain state strings, never the
`org.ledger.saga` enums — `ArchitectureFitnessTest.db_must_not_depend_on_domain_logic` forbids
`org.ledger.db` from depending on `org.ledger.saga`. `SagaCrashRecoveryIT` simulates a process kill
with a test-only hook (`SagaOrchestrator.setStepCommittedHookForTesting`) that throws immediately
after a chosen step's forward commit, abandoning the in-flight `execute()` call uncaught — there is
no in-process way to simulate an actual JVM crash inside one Testcontainers-backed test.
**Issues faced:**
1. `docs/adr/0008-saga-leg-model.md`, `SagaController`, and the DTOs needed to reference
   `org.ledger.saga` types (`SagaDefinition`, `SagaLeg`, `SagaResult`), but `SagaDefinition` itself
   cannot reference `org.ledger.api.dto.CreateSagaTransferRequest` —
   `ArchitectureFitnessTest.saga_must_not_depend_on_forbidden_modules` forbids `org.ledger.saga` from
   depending on `org.ledger.api`.
2. First pass at `SagaOrchestrator.recover` re-wrote `sagas.state`/`saga_steps.state` unconditionally
   on every call, which would have broken `SagaRecoveryIdempotenceIT`'s "second run touches nothing"
   assertion even though the *data* ended up correct both times.
3. `mvn compile` failed with `cannot find symbol: class HashMap` in `SagaOrchestrator` after two
   edits landed in the wrong order: the Spotless `removeUnusedImports` post-edit hook silently
   stripped the `java.util.HashMap` import (unused at that moment, since the call site still read
   `new java.util.HashMap<>()`) before the follow-up edit shortened the call site to `new
   HashMap<>()`, leaving an unqualified reference with no import.
4. `AssertJ`'s `catchThrowableOfType` takes `(ThrowingCallable, Class<T>)`, not
   `(Class<T>, ThrowingCallable)` — first draft of `SagaCompensationIT` had the arguments reversed
   and failed to compile.
5. `mvn verify` initially failed one pre-existing test, `OpenApiIT`, which hard-asserts the number of
   documented `/api/v1` paths.
**Resolution:**
1. Kept the pairing/reshaping logic (`CreateSagaTransferRequest` → `List<SagaLeg>`) in
   `SagaController` instead of `SagaDefinition`, since nothing prevents `org.ledger.api` from
   depending on `org.ledger.saga` (only the reverse is forbidden) — confirmed no such rule exists in
   `ArchitectureFitnessTest` before relying on it.
2. Tracked each step's already-observed state in a local `Map<Integer, SagaStepsRecord>` (mutated as
   pass one disambiguates `IN_PROGRESS` rows) and a local `sagaState` variable, and guarded every
   write with an equality check against the target state first. Verified directly:
   `SagaRecoveryIdempotenceIT` runs `recover()` twice against the same interrupted saga and asserts
   `updated_at` on both the saga row and every step row is identical across the two runs, plus no new
   `ledger_entries`.
3. Re-added the `java.util.HashMap` import after the fact and re-ran `mvn compile`. Lesson: after a
   PostToolUse formatter hook reformats a file, re-read it (or at least re-check the specific region)
   before the next dependent edit, rather than assuming the prior edit's context is still exactly as
   written.
4. Swapped the argument order to `catchThrowableOfType(() -> sagaOrchestrator.execute(definition),
   SagaCompensatedException.class)`.
5. Updated `OpenApiIT` to assert the correct new count (9) and the two new path keys
   (`/api/v1/transfers/saga`, `/api/v1/sagas/{sagaId}`) — a legitimate test update, not a workaround,
   since the endpoint count genuinely changed.

Full suite green: `mvn -B verify` — 167 tests, 0 failures, 0 errors. `mvn spotless:check` clean.
`ArchitectureFitnessTest` (9 rules) passes with real saga code exercised for the first time.
Invariant check run via the JUnit path (`docker compose`'s Postgres port was held by an unrelated
container in this environment): `globalEntrySum() == 0` after every saga path exercised by the new
ITs — happy path, mid-chain compensation, and crash recovery at every step index (0, 1, 2).

---

## [011] SPEC 0007 — Concurrency & crash harness — 2026-07-22

**Spec:** SPEC 0007

**What:** Built the headline driver in `src/test/java/org/ledger/harness/`
(`AbstractConcurrencyChaosHarness`, `HarnessWorkload`, `HarnessResults`,
`Optimistic/PessimisticConcurrencyChaosHarness`) plus `src/test/java/org/ledger/saga/ChaosSagaPhase.java`
(package `org.ledger.saga`, to reach `SagaOrchestrator.setStepCommittedHookForTesting`). Named
`*ConcurrencyChaosHarness` deliberately so Failsafe's default `*IT*` includes skip it in `make test`.
Replaced the placeholder `make concurrency-test` target and added `make concurrency-test-x10`. Also
fixed a real bug found while running it: `IdempotencyFilter.execute()` only routed `>=500` responses
to `FAILED`; `409 CONFLICT_RETRY_EXHAUSTED` (a transient contention signal, not a deterministic
request outcome) fell into the `>=4xx` branch and got permanently cached as `COMPLETED`, so
`replay()` would forever re-serve the stale 409 as a fake "200 replayed success" and that transfer
could never happen. Fixed to route 409 to `FAILED` alongside 5xx, documented in new
`docs/adr/0009-retry-exhaustion-is-not-a-terminal-idempotency-outcome.md`.

**Why:** FR-32/FR-33/NFR-1..4/NFR-9/NFR-12/DR-4 — prove the headline claim (10,000 concurrent
transfers, ~30% duplicates, 0 double-charges, 0 money created/destroyed, Σ(entries)=0) reproducibly,
in one command, on a clean machine, for both concurrency strategies.

**How:** Phase 0 seeds 4 hot accounts (12 ordered pairs) plus 2 min_balance-floor accounts, all
funded through real transfers (`fundFromGenesis`) so `balance == Σentries` holds for everything but
genesis; floor accounts get `min_balance` raised via a direct `UPDATE` afterward (not via
`seedAccountState`, which would have introduced a second source of reconciliation drift beyond
genesis and broken Phase 3's "drift is genesis-only" assertion). Phase 1 fires 7,700 logical
transfers over real HTTP (`TestRestTemplate`, virtual-thread-per-task executor, 256-permit
semaphore, one `CountDownLatch` start gate), ~30% get a byte-identical duplicate twin fired
concurrently under the same key (deterministic `Random(20260722L)` seed), for ~10,060 wire requests;
409/503/500 responses are treated as client back-pressure and re-sent with the same key (bounded 20
attempts, jittered backoff) exactly like a real client. Phase 2 crashes 12 sagas (4 per step index
0..2) concurrently via `ChaosSagaPhase`, which uses a `ThreadLocal` instead of the plan's
originally-suggested `ctx.sagaId()`-keyed map: a saga's id does not exist until `execute()` is
already running on that thread, so it cannot be known in advance, but the hook always fires
synchronously on the same thread that called `execute()`, making thread-scoped state equally safe
without needing the id ahead of time. Phase 3 re-derives every invariant independently from the
database (not from the HTTP counters). Phase 4 prints the FR-33 table on both success and failure
via `try`/`finally`.

**Issues faced:**
1. First Optimistic run's storm produced two hard failures: `500 INTERNAL_ERROR` from Postgres
   ("new multixact has more than one updating member") on `OptimisticStrategy`'s version-CAS
   `UPDATE` under this harness's contention level — a rare, genuinely transient Postgres condition
   `TransferService.isRetryable()` doesn't classify as retryable internally.
2. After treating 500 as harness-level retryable (test-only change), the Optimistic run still came
   up exactly one transfer short (`uniqueApplied` 7699 instead of 7700) with 0 hard failures reported
   and money conservation still holding — the `IdempotencyFilter` bug described above: a claimant
   that exhausted `TransferService`'s internal retry budget (409) got its key permanently marked
   `COMPLETED` with the error snapshot, so every subsequent retry of that key (including the
   harness's own) replayed the stale 409 as a fake 200 success and the transfer never actually
   happened.
3. First `mvn compile` failed: a Javadoc block literally containing `**/IT*.java` terminated the
   comment early at the embedded `*/`.

**Resolution:**
1. Documented as a genuine (non-injected) harness finding rather than silently working around it;
   extended the harness's own client-retry classification to treat 500 as retryable too, since
   `IdempotencyFilter` already marks a 500's key `FAILED` and allows same-key reclaim — a resilient
   real client retrying on 500 is realistic, not a test-only carve-out.
2. Asked the project owner how to handle it (production fix vs. harness-only workaround vs.
   weakening the assertion) rather than deciding unilaterally, since it's a real correctness gap in
   already-accepted ADR 0005's design, not this spec's own scope. Chose to fix `IdempotencyFilter`:
   409 now routes to `FAILED` alongside 5xx, recorded in ADR 0009. Verified via the mandated
   sabotage step (temporarily forcing `IdempotencyFilter` to skip `tryClaim`, and separately
   `injectDrift` before Phase 3) that the harness actually goes red and prints a diagnosable table
   in both cases, then reverted both.
3. Rewrote the Javadoc to describe the glob patterns in prose instead of literal path syntax.

Full suite green: `mvn -B verify` — 167 tests unchanged (the two harness classes are correctly
excluded by Failsafe's default includes). `make concurrency-test`: both strategies PASS —
pessimistic ~14-15s wall clock, optimistic ~29-35s, both far under the 10-minute budget (NFR-9).
`make concurrency-test-x10`: 10/10 consecutive passes, 20/20 individual strategy runs green.
Invariant check across all 20 runs: `global_sum == 0` every run; `accountsDrifted == 1` every run,
always genesis only, at exactly `GENESIS_STARTING_CAPITAL`.

---

## [012] Performance benchmark: optimistic vs pessimistic, RC vs SERIALIZABLE — 2026-07-22
**Spec:** SPEC 0008
**What:** New `org.ledger.bench` package (`src/test/java`): `TransferBenchmark` (JMH,
`SingleShotTime`, `@Fork(0)`), `BenchContext` (one Spring context per (strategy, isolation) cell,
cached across contention levels), `BenchFixture` (hot-account seeding via real transfers, mirroring
`AbstractPostgresIT`), `LatencyRecorder`, `BenchmarkSink`, `BenchmarkReport` (pure formatter —
crossover detection, SERIALIZABLE cost %, claim sentence, aggregation across JMH's 3 measurement
iterations), `SvgLatencyPlot` (hand-rolled SVG, no charting library), and `BenchRunner` (the
`exec:exec` entry point). `pom.xml` gained a `bench` Maven profile (JMH deps test-scoped, an
annotation-processor execution for the JMH harness, `exec-maven-plugin` wired to `BenchRunner`).
`Makefile`'s `bench` target is implemented; added `bench-quick` (3 contention levels, short bursts)
for fast iteration. `docs/adr/0010-concurrency-strategy-and-isolation-by-measurement.md` records the
production defaults. `application.yml` comments updated to cite ADR 0010 (`concurrency-strategy` /
`isolation-level` confirmed, not changed; `DB_POOL_SIZE` left as-is, explicitly not backed by this
run). `docs/bench/{results.md,results.csv,latency.svg}` committed. `BenchmarkReportTest` (plain
JUnit) covers crossover detection (including the "must hold for every higher level, not just one"
and no-crossover cases), the SERIALIZABLE cost average, and the multi-iteration aggregation.

**Why:** FR-34/FR-35/NFR-6/NFR-7/NFR-8 — three decisions ADR 0001 and ADR 0006 deliberately left
open (`ConcurrencyStrategy` default, isolation-level default, Hikari pool size) were blocked on this
spec's numbers, not taste. `make bench` exited 1 with a placeholder before this.

**How:** Load is driven at `TransferService.execute(...)` directly, not over HTTP, so the matrix
measures locking/isolation cost, not Tomcat + `IdempotencyFilter` overhead. Each JMH invocation runs
`contention` virtual threads against one hot-account pair, `WARMUP_TRANSFERS_PER_THREAD` unrecorded
then a `CyclicBarrier` phase change into `MEASURED_TRANSFERS_PER_THREAD` recorded — JMH-level warmup
is `@Warmup(iterations = 0)` on purpose, since JMH gives a benchmark method no way to know it's in a
warmup iteration. `@TearDown(Level.Trial)` re-derives `balance == Σentries` for the two accounts the
trial actually wrote plus the global entry sum, and throws (failing the whole benchmark) on drift —
a throughput number from a run that lost money is worthless. Full sweep: {optimistic, pessimistic} ×
{read_committed, serializable} × {1,2,4,8,16,32,64} contention, 3 measurement iterations each, run
against real Testcontainers Postgres (~7:46 wall clock on the author's machine).

**Result:** Pessimistic wins or ties at *every* contention level measured under READ COMMITTED
(370→643 transfers/sec across the sweep vs optimistic's 233→337, with zero retry-exhausted failures
against optimistic's hundreds at high contention) — the ADR 0006-predicted low-contention optimistic
advantage did not appear in this workload. SERIALIZABLE's cost is asymmetric: 0.3% for optimistic
(noise), 61.4% for pessimistic — `SELECT ... FOR UPDATE` under SERIALIZABLE pays PostgreSQL's SSI
predicate-lock overhead on top of the row lock it already holds. Production defaults
(`concurrency-strategy=pessimistic`, `isolation-level=read_committed`) were *confirmed* by this data,
not changed — both were already the `application.yml` defaults. Full writeup: ADR 0010.

**Issues faced:**
1. First `make bench-quick` run: JMH's own `@Fork(1)` spawns a nested JVM that does not inherit
   `-Dapi.version=1.44` / `-Dnet.bytebuddy.experimental=true` from the `exec:exec`-launched JVM,
   so Testcontainers failed in the forked JVM with a misleading "no Docker environment" — exactly
   the risk flagged in the implementation plan.
2. After fixing (1): every `BenchContext` boot connected to `localhost:5432` (the dev/prod default)
   instead of the Testcontainers Postgres, and failed outright since nothing was listening there.
3. After fixing (2): `application.yml`'s `org.jooq.tools.LoggerListener: DEBUG` logged every bind
   variable of every statement on every virtual thread during the burst — tens of thousands of log
   lines per trial, with the shared-appender I/O measurably dominating latency rather than the lock
   contention the benchmark exists to measure.
4. After fixing (3) and running the full sweep once: `docs/bench/results.md` showed 3 rows per
   matrix cell instead of 1, with visibly inconsistent numbers between the 3 rows for the same
   (strategy, isolation, contention) combination.

**Resolution:**
1. Changed `TransferBenchmark` to `@Fork(0)` and `BenchRunner`'s `OptionsBuilder` to `.forks(0)`:
   JMH runs embedded in the JVM `exec:exec` already controls, so the Docker API pin reaches the JVM
   that actually starts `BenchPostgres`. No second fork is needed since this JVM is already
   dedicated to the benchmark.
2. Root-caused to `SpringApplicationBuilder.properties(...)` setting Boot's lowest-precedence
   *default* properties, which never win against `application.yml`'s already-concrete
   `spring.datasource.url` (it has a hardcoded fallback, not an unset key). Switched
   `BenchContext.boot` to an `ApplicationContextInitializer` that `addFirst`s a `MapPropertySource`
   on the environment, making the override the highest-precedence source unconditionally.
3. Root-caused to Boot's `LoggingApplicationListener` reading `logging.level.*` once, early
   (`ApplicationEnvironmentPreparedEvent`), before any `ApplicationContextInitializer` runs — so a
   one-time programmatic silence at process start got overwritten the moment the first Spring
   context booted. Fixed by calling the Logback API directly (`root` and
   `org.jooq.tools.LoggerListener` both set to WARN) *after every* `BenchContext.boot()` call, not
   once at process start, and by setting the named logger explicitly (an explicit level on a named
   logger wins over an inherited root level, so silencing root alone left this one logger at DEBUG).
4. Root-caused to `TransferBenchmark`'s `@Measurement(iterations = 3)`: `BenchmarkSink` recorded one
   `Cell` per JMH iteration, and `BenchmarkReport`'s internal `Map<Integer, Double>` (keyed by
   contention level) silently kept only the *last* of the 3 rows on each write — two-thirds of every
   cell's data was discarded with no error, exactly the "one piece of bench logic that can silently
   produce a wrong headline number" the class's own Javadoc warned about. Added
   `BenchmarkReport.aggregateByCell` (median throughput/latency, summed counts) called from `write()`
   before any rendering or crossover/cost computation, with two new `BenchmarkReportTest` cases
   covering the aggregation. Reran the full sweep after the fix; the corrected results are the ones
   reported above and in ADR 0010.

`mvn -B verify` unaffected: `TransferBenchmark` (no `*IT`/`*Test` suffix) is picked up by neither
Surefire nor Failsafe; `BenchmarkReportTest` runs under `mvn test`. `make bench-quick` and
`make bench` both produce `docs/bench/{results.md,results.csv,latency.svg}` with the invariant gate
passing on every trial.

---
