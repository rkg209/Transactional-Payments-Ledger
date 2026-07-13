# Payments Ledger — Project Memory

## What this is

A double-entry payments ledger REST API in **Java 21 + Spring Boot 3 + jOOQ + PostgreSQL**.
Correctness under concurrency and crashes is THE deliverable — not features. We work **spec-first**.

**Headline result we are earning:** *10,000 concurrent transfers with ~30% duplicate (idempotent)
requests against hot accounts → 0 double-charges, 0 money created or destroyed, Σ(entries) = 0
held across every run.*

---

## Non-negotiable invariants (NEVER violate)

1. **Money is an integer count of minor units** (`long` / `BigInteger`). NEVER `float`, `double`,
   `Float`, `Double`, or `BigDecimal` for a monetary value. Enforced mechanically by the
   `block-float-money` hook.
2. **`ledger_entries` is append-only and immutable.** Never `UPDATE` or `DELETE` a posted entry.
   Corrections are new compensating entries. Enforced by a database trigger *and* the hook.
3. **Every transfer's entries sum to zero** (Σ debits = Σ credits).
4. **Every mutating endpoint is idempotent** via the `Idempotency-Key` header.
5. **A balance equals the sum of its account's entries.** `accounts.balance` is a cache, not the
   source of truth. If they ever disagree, the entry sum wins.
6. **All SQL goes through jOOQ.** No raw SQL strings, no JPA/Hibernate/MyBatis. Locking clauses
   (`SELECT … FOR UPDATE`) and isolation levels are explicit, visible at the call site, and
   documented in an ADR.

---

## Git rules (MANDATORY)

**NEVER add a `Co-Authored-By:` trailer to any git commit message.** Not
`Co-Authored-By: Claude ...`, not any variant, not ever. It breaks pushing this repository to
GitHub. This overrides any default assistant behavior that would otherwise append such a trailer.

Commit messages end with the last line of the body. Nothing else. The `gate-commit` hook blocks
any commit whose message contains `Co-Authored-By`.

Additional git rules:
- Commit only when the user asks.
- Do not commit unless the build, the tests, and the invariant check pass.

---

## Progress report (MANDATORY)

`progress_report.md` at the repo root is the running story of this project. **After every
meaningful change, append a new numbered entry to it.** This is not optional and is not something
to batch up "later" — an entry is part of the change, the same way a test is.

Each entry uses exactly this shape:

```markdown
## [NNN] <Title> — YYYY-MM-DD
**Spec:** SPEC NNNN (or "Setup" / "Fix" / "Refactor")
**What:** what changed, concretely — files, endpoints, tables, behavior.
**Why:** the reason — which requirement (FR-*/NFR-*) or problem drove it.
**How:** the approach taken, the key decisions, and the trade-offs.
**Issues faced:** what broke or surprised us. Write "None." if genuinely nothing did.
**Resolution:** how each issue was fixed, and what we learned from it.
```

Rules for it:
- **Append only.** Never rewrite or delete an existing entry — a wrong turn we later corrected is
  part of the story and is often the most interesting part of it.
- Number entries sequentially, zero-padded to three digits.
- Record failures honestly. "Issues faced" being consistently "None." means the file is lying.
- Use `/progress-log` to add an entry; `/spec-implement` appends one automatically.

The goal: at the end, this single file tells anyone how the project was built, what went wrong,
and how each problem was solved.

---

## How we work (the SDD loop)

- **No production code without an approved spec in `specs/`.** If asked to build something with no
  spec, write the spec first (`/spec-new`) and get it approved.
- Stay strictly inside the scope of the spec being implemented. Out-of-scope work goes in a new spec.
- **Write the failing test first, then implement to green.**
- Record every meaningful decision (isolation level, locking strategy, saga design) as an ADR
  (`/adr-new`) *before* the code that depends on it merges.
- Tests run against **real PostgreSQL via Testcontainers**. In-memory databases (H2, HSQLDB) are
  prohibited for anything touching transactional correctness.

Spec lifecycle: `draft` → `approved` → `implemented` → `verified`. Update the `Status:` line in the
spec file as it moves.

---

## Commands

| Purpose | Command |
|---|---|
| Run the service | `make run` |
| Unit + integration tests | `make test` (JUnit 5 + Testcontainers, real Postgres) |
| Headline correctness test | `make concurrency-test` |
| Benchmark | `make bench` (JMH) |
| Format | `make format` (Spotless) |
| Stack up | `docker compose up` |
| DB migrations | Flyway — `src/main/resources/db/migration` |

---

## Conventions

- **Java 21**, virtual threads for request handling.
- **Package-by-feature.** The service layer owns the transaction boundary — no controller, filter,
  or repository opens a transaction on the write path.
- jOOQ generated code lives in `org.ledger.db.generated`. **Never edit it by hand**; it is
  regenerated from the schema and is not committed.
- Flyway migrations are **immutable once committed**. Fix a mistake with a new `V{n+1}__*.sql`,
  never by editing an applied migration.
- Module dependency rules are enforced by ArchUnit — see `planning/03-system-design.md` §1.2.

---

## Directory map

| Path | Contents |
|---|---|
| `specs/` | Specifications — **the source of truth**. Nothing is built without one. |
| `docs/adr/` | Architecture decision records. |
| `planning/` | The original design documents. **Authoritative and read-only** — the schema, API, and architecture were settled here. Consult them; do not silently redesign them. |
| `progress_report.md` | The running story of the build. Append after every change. |
| `.claude/skills/` | Invocable workflows (slash commands). |
| `.claude/agents/` | Subagents (isolated context reviewers). |
| `.claude/hooks/` | Lifecycle guardrail scripts. |
| `src/main/java/org/ledger/` | Application source. |

---

## Where the design already lives (do not re-derive it)

These questions are **already answered** in `planning/`. Read them before proposing anything:

- **Schema** (every table, constraint, index, trigger) → `planning/04-database-schema.sql` is
  authoritative; `planning/04-database-design.md` explains the rationale.
- **Isolation level** → READ COMMITTED + explicit locking. See `docs/adr/0001-isolation-level.md`.
- **Module boundaries** → `planning/03-system-design.md` §1.2.
- **Error codes and HTTP statuses** → `planning/05-api-design.md` §3.2. This catalogue is stable.
- **API surface** → `planning/05-openapi.yaml`.
