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
