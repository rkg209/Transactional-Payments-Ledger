# Implementation Plan — SPEC 0001: Double-entry core

> Destination: this plan will be saved to `.claude/plans/0001-double-entry-core.md` in the repo
> (per the user's request) once approved.

## Context

SPEC 0000 (walking skeleton) is **code-complete and green** — Spring boots on real
PostgreSQL 16 via Testcontainers, Flyway migrates all 7 tables (V1/V2), `/health` works, and the
`ledger_entries` immutability trigger is proven by a live test. Its spec `Status:` line still reads
`approved` (a bookkeeping lag), but functionally the foundation is sound.

SPEC 0001 builds the **first ledger logic** on that foundation: post a balanced transfer atomically
in one ACID transaction and derive balances from entries. No HTTP (0002), no idempotency (0003), no
concurrency control (0004 — 0001 deliberately uses a *knowingly-racy* plain read-then-write). If
money can be created or destroyed here, nothing above can save the system, so this is the correctness
bedrock.

**Requirements:** FR-1…FR-6, NFR-2/3/14/18, CON-4/5.

### The one load-bearing gap this spec must close first

`pom.xml` has **no jOOQ code-generation plugin** and **no ArchUnit dependency**. The generated
package `org.ledger.db.generated` does not exist — the skeleton (`HealthController`,
`WalkingSkeletonIT`) uses jOOQ's plain-SQL API. But CLAUDE.md and this spec both require *type-safe*
repositories over generated code, and the spec requires an ArchUnit `ArchitectureFitnessTest`. So
0001 must stand up the codegen pipeline and add ArchUnit before any repository can compile.

## Key decisions (confirmed / recommended)

1. **jOOQ codegen mechanism — Testcontainers codegen (user-confirmed).** Use
   `org.testcontainers:testcontainers-jooq-codegen-maven-plugin` bound to `generate-sources`: it
   starts `postgres:16-alpine`, runs the real Flyway migrations, introspects the live catalog
   (trigger, plpgsql, regex CHECKs, partial indexes all faithful), generates into
   `target/generated-sources/jooq` package `org.ledger.db.generated`, tears down. Highest fidelity;
   reuses the image tests already pull; Docker is already a hard build dep here. **Needs ADR 0002.**
2. **Docker-API pin propagation.** Codegen runs Testcontainers in the *Maven JVM* at
   `generate-sources`, not in a surefire/failsafe fork — so the existing `<api.version>1.44>` sysprops
   don't reach it. Add **`.mvn/jvm.config`** containing `-Dapi.version=1.44` so every Maven
   invocation (codegen included) negotiates the Docker API. (Document the "why" in ADR 0002.)
3. **`execute()` signature:** flat args `TransferResult execute(UUID from, UUID to, long amountMinor,
   String currency)` returning a `record TransferResult(UUID transferId, TransferStatus status)`.
   Cannot accept `api.dto.CreateTransferRequest` — `transfer ↛ api` is forbidden by §1.2.
4. **Exception placement:** `InsufficientFundsException` in `org.ledger.transfer`,
   `AccountNotFoundException` in `org.ledger.account` (both `RuntimeException`, structured fields).
   Respects the dependency matrix; avoids an un-governed shared error package.
5. **No locking yet.** `AccountRepository` does a plain `findById` + `applyDelta` (read-then-write,
   no `FOR UPDATE`, no version-CAS). This racy path is the whole point of deferring to 0004 — do not
   pre-build locking.
6. **No new test-generator dependency** — property test uses a seeded `java.util.Random` loop, not
   jqwik.

## Authoritative facts (do not re-derive)

- **Schema** (`planning/04-database-schema.sql`, already migrated): money = `BIGINT` minor units →
  Java `long`; UUID PKs via `gen_random_uuid()`; `TIMESTAMPTZ`. `accounts(balance, min_balance,
  version …; CHECK balance>=min_balance)`. `transfers(from/to FK, amount_minor>0, status
  IN(PENDING,COMPLETED,FAILED), from<>to)`. `ledger_entries(transfer_id, account_id, direction
  IN(DEBIT,CREDIT), amount_minor>0)` — append-only, guarded by BEFORE UPDATE/DELETE trigger
  `ledger_entries_immutable_tg`.
- **Module rules** (`planning/03-system-design.md` §1.2): `transfer` may import `account`,
  `concurrency`, `db`; NOT `api`/`idempotency`/`saga`. `account` may import `db` only. `db` imports
  only `db.generated`, no business logic. `TransferService.execute()` is the **sole** `@Transactional`
  write-path method; repositories open no transactions.
- HikariCP `auto-commit=false` — every write must be inside a service-owned transaction (tests wrap
  writes in `TransactionTemplate`).

## Implementation steps (ordered — 2→3→4 must precede repositories)

1. **ADR 0002** `docs/adr/0002-jooq-codegen.md` — Testcontainers-codegen choice, the `.mvn/jvm.config`
   API-pin rationale, "generated code is regenerated not committed." (SDD: ADR before dependent code.)
2. **`pom.xml`** — add `testcontainers-jooq-codegen-maven-plugin` (`generate-sources`, goal
   `generate`) with internal deps pinned to the runtime versions (jOOQ `${jooq.version}` from the
   Spring Boot BOM, matching flyway-core + postgresql driver). Config: `database.type=POSTGRES`,
   `containerImage=${postgres.image}`, `flyway.locations=filesystem:src/main/resources/db/migration`,
   `inputSchema=public`, `excludes=flyway_schema_history`, `packageName=org.ledger.db.generated`,
   `directory=${project.build.directory}/generated-sources/jooq`. **No forcedTypes** (BIGINT→Long,
   UUID→UUID, TIMESTAMPTZ→OffsetDateTime already correct). Also add
   `com.tngtech.archunit:archunit-junit5` (test scope). Confirm Spotless still targets only
   `src/main/java` (must not format generated sources).
3. **`.mvn/jvm.config`** → `-Dapi.version=1.44`.
4. **Generate + verify** — `mvn generate-sources`; confirm
   `target/generated-sources/jooq/org/ledger/db/generated/**` holds `Accounts`, `Transfers`,
   `LedgerEntries` + `*Record`. (Do not commit.)
5. **Repositories** (`org.ledger.db`, `@Repository`, inject `DSLContext`, no transactions):
   - `AccountRepository`: `insert(name, currency, minBalance)→AccountsRecord`;
     `findById(UUID)→Optional`; `applyDelta(UUID, long delta)` (sets `balance+=delta`, `version+=1`,
     `updated_at=now` — racy, no version predicate).
   - `TransferRepository`: `insertPending(from,to,amountMinor,currency)→TransfersRecord`;
     `markCompleted(UUID)`; `findById(UUID)→Optional`.
   - `LedgerEntryRepository`: **only inserts + reads.**
     `insertBalancedPair(transferId, debitAccountId, creditAccountId, amountMinor)` — writes the DEBIT
     and CREDIT rows from the *same* `amountMinor` in one call, so an unbalanced pair is structurally
     impossible (the "by construction" guarantee); guard `amountMinor<=0`. Plus read helpers
     `entrySumForAccount(UUID)→long` and `globalEntrySum()→long` (signed CREDIT−DEBIT sums) for
     assertions. **No update/delete/markX methods exist here at all.**
6. **Enums / exceptions / results:** `transfer/TransferStatus` (PENDING,COMPLETED,FAILED);
   `transfer/InsufficientFundsException` (accountId, availableBalance = balance−minBalance,
   requiredAmount, currency); `account/AccountNotFoundException` (accountId);
   `transfer/TransferResult`; `account/AccountResult`.
7. **Services:**
   - `account/AccountService` (`@Service`): `createAccount(...)` (`@Transactional`),
     `getAccount(UUID)` (`@Transactional(readOnly=true)`, throws `AccountNotFoundException`).
   - `transfer/TransferService` (`@Service`, `@Transactional` on `execute()` — the only write-path
     transactional method). Steps: resolve both accounts (→`AccountNotFoundException`); **balance
     guard BEFORE any write** `if (from.balance − amount < from.minBalance) throw
     InsufficientFundsException`; `insertPending`; `insertBalancedPair`; `applyDelta(from,−amount)` +
     `applyDelta(to,+amount)`; `markCompleted`; return `TransferResult`. The DB
     `CHECK(balance>=min_balance)` is the backstop that aborts the whole tx on any guard error.
     `getTransfer(UUID)` read-only.
8. **ArchUnit** `test/.../architecture/ArchitectureFitnessTest` (surefire, no DB;
   `@AnalyzeClasses(packages="org.ledger", DoNotIncludeTests)`): encode the full §1.2 matrix
   (non-existent packages simply pass); enforce `db` has no domain deps; `api ↛ db.generated`; and the
   headline rule — **only `LedgerEntryRepository` may reference the `LedgerEntries` generated
   types**. Document in Javadoc the honest limitation: ArchUnit checks *type references*, not SQL
   verbs, so "no UPDATE/DELETE" is enforced by three layers together — this class exposing only
   inserts+reads, the ArchUnit boundary rule, and the runtime trigger test.
9. **Test base class** `test/.../support/AbstractPostgresIT` — extract the
   `PostgreSQLContainer("postgres:16-alpine")` + `@DynamicPropertySource` boilerplate from
   `WalkingSkeletonIT` (singleton-container pattern, started once, reused). Refactor
   `WalkingSkeletonIT` onto it. Provide a `@BeforeEach` reset via `TRUNCATE … CASCADE` (TRUNCATE does
   NOT fire the row-level trigger, so it's the correct reset; DELETE is blocked by design).
10. **Integration tests** (TDD: red first). `DoubleEntryCoreIT` (2 balanced entries, transfer
    COMPLETED, both balances moved opposite by exactly `amount`, conservation, `globalEntrySum()==0`);
    `InsufficientFundsIT` (overdraft → exception, balance untouched, zero rows written);
    `UnbalancedPostingIT` (assert by-construction property + atomic rollback of a forced
    mid-transaction failure leaves zero orphan transfer/entry rows — see open question 1);
    `LedgerImmutabilityIT` (seed via services, UPDATE/DELETE both throw `append-only`, row survives —
    migrated out of `WalkingSkeletonIT`); `DoubleEntryPropertyIT` (N random transfers over M accounts;
    after each: Σ=0, per-account balance = entry sum, no balance<min_balance, overdrafts rejected and
    leave state untouched).
11. **`/progress-log`** entry (mandatory) — record the codegen decision, the ArchUnit "no
    UPDATE/DELETE" limitation, and issues hit.
12. **SPEC 0001 `Status:`** draft → implemented once green. (Mark `verified` only after the invariant
    check passes.)

## Verification

- `mvn clean verify` from a clean tree: codegen regenerates into `target/`, main compiles against
  generated classes, surefire runs `ArchitectureFitnessTest` green, failsafe runs all `*IT` green
  against Testcontainers Postgres.
- **`/invariant-check` skill** reports `Σ(all entries)=0` globally and `balance=Σ(entries)` per
  account, zero drift.
- `mvn spotless:check` passes (generated sources excluded).
- Every SPEC 0001 acceptance criterion satisfied (balanced 2-entry posting; money conserved; Σ=0;
  unbalanced/insufficient rejected with nothing persisted; materialized balance = entry sum; ArchUnit
  boundary + ledger-entry rule; DB trigger blocks UPDATE/DELETE).
- **Commit rule reminder:** NO `Co-Authored-By` trailer (gate-commit hook blocks it). Commit only
  when the user asks, only when build+tests+invariant-check pass.

## Open questions to confirm at implementation time

1. **How `UnbalancedPostingIT` provokes imbalance.** Because `insertBalancedPair` derives both rows
   from one `amount`, a literal single-legged posting is impossible without a test-only production
   seam (not recommended). Plan: assert the by-construction property structurally + assert atomic
   rollback on a forced failure. Confirm this framing is acceptable.
2. **Refactoring `WalkingSkeletonIT`** onto the new base class (recommended, in-scope cleanup) vs.
   leaving the 0000 artifact frozen.

## Notes / caveats

- `planning/03-system-design.md` is **truncated at line 626** (ends mid-§4), so the `§6.1`
  balance-update-strategy reference doesn't exist on disk. Harmless for 0001 (which uses the plain
  read-then-write); the strategy contract lives in ADR 0001 for now. Flagged for 0004.
- Minor `planning/` doc discrepancies exist between `04-database-schema.sql` and
  `04-database-design.md` (accounts CHECKs, a thinner `transfers` table, FK `ON DELETE RESTRICT`).
  The **`.sql` file is authoritative** and is already what V1 migrated — no schema change in 0001.
