# Transactional Payments Ledger — Spec-Driven Build Plan

> **One-line pitch (recruiter-facing).** A double-entry payments ledger backend in Java 21 that **provably never loses, creates, or double-charges money** under heavy concurrency and mid-transaction crashes — correctness demonstrated by a chaos/concurrency test harness, not claimed.
>
> **The axis this project owns:** *Data correctness & concurrency.* This is the highest-credibility-per-effort piece a backend/fintech candidate can ship: clear scope, clear definition of "done," and a headline result that is a *measured guarantee*, not a feature list.
>
> **Headline result to earn:** *"10,000 concurrent transfers with ~30% duplicate (idempotent) requests against hot accounts → 0 double-charges, 0 money created or destroyed, Σ-of-entries invariant held across every run."*

---

## 0. Locked decisions (read this first)

These were deliberately decided up front so every spec below is unambiguous. Each line is also an **interview talking point** — be ready to defend it.

| Decision | Choice | Why (the one-liner you'll say in an interview) |
|---|---|---|
| Language / runtime | **Java 21** (virtual threads) | Concurrency knowledge compounds across the resume's other Java projects; virtual threads make a high-concurrency server realistic. |
| Data access | **jOOQ** (type-safe SQL) | The whole project is about *controlling exactly what SQL and what locks run.* jOOQ gives compile-checked SQL with full control over `SELECT … FOR UPDATE` and isolation, without Hibernate's flush/dirty-checking hiding *when* SQL fires. |
| Project shape | **REST API service** (deployable) | Idempotency keys live at the HTTP boundary (`Idempotency-Key` header, à la Stripe). Makes the guarantee real, demoable, and curl-able. |
| Concurrency control | **Both** optimistic *and* pessimistic, **benchmarked** | "I implemented both, measured them under contention, and found the crossover point" is a data-driven systems answer, not a coin flip. |
| Money representation | **Integer minor units** (`long`/`BigInteger` cents), **single currency (USD)** | Never floats for money. Multi-currency + FX is a noted *stretch goal*, not core scope. |
| Multi-step transfers | **In-process saga orchestrator** with persisted, crash-recoverable state | Distributed sagas would overlap the Message Broker project and explode scope. In-process keeps it bounded and still proves compensation. |
| Database | **PostgreSQL** (real ACID) | Real transactions, real isolation levels, real `SERIALIZABLE`/`READ COMMITTED` behavior to reason about. |
| Migrations | **Flyway** | Versioned, reproducible schema; CI-friendly. |
| Testing | **JUnit 5 + Testcontainers** (real Postgres) **+ JMH** (throughput) **+ a custom concurrency/chaos harness** | Correctness is the deliverable; it must be *proven against a real database*, not an in-memory fake. |
| Deploy / deliverable | **Docker Compose + `make concurrency-test`**; **optional** free-tier container deploy for a live URL | Career Copilot stays the "live deployed product" anchor; here the deliverable is the repo, the results table, and the demo. |

> Everything below treats these as fixed. If you want to revisit any, change this table and the dependent specs follow.

---

## 1. The problem & why it's on the resume

Any system that moves money or tracks balances must guarantee correctness under concurrency and failure: no double-charges, no money invented or destroyed, safe retries, and clean rollback when a multi-step operation fails partway. Naive implementations mutate a balance column directly and quietly corrupt under races and crashes.

This project is the antidote, built and *proven*:

- **Double-entry bookkeeping** — every transfer is a balanced set of entries (debit A, credit B, summing to zero); balances are derived from an append-only, immutable entry log, so the books are always auditable and always balanced.
- **Idempotency** — a retried payment request is applied exactly once, even when two identical requests race.
- **Explicit concurrency control** — isolation level and locking strategy are *chosen, justified, and measured*, not accidental.
- **Sagas with compensation** — multi-step transfers either fully complete or fully roll back, even across a crash.
- **A continuously-checked invariant** — `Σ(all entries) = 0` is asserted by a reconciliation job after every run.

**Audience:** fintech / payments / backend-infrastructure interviewers (Stripe-style). The pattern underlies real payment systems, and "design a ledger / handle idempotent payments / pick an isolation level" are common interview probes you will have *built*, not just read about.

---

## 2. The genuinely hard parts (what you defend in an interview)

1. **Idempotency under concurrency** — two simultaneous identical requests must yield exactly one transfer. Solved with idempotency keys + a unique constraint + the right insert-or-return semantics. *(The #1 fintech-interview probe.)*
2. **Isolation & locking** — choosing/justifying an isolation level and a locking strategy, and naming the specific anomalies each prevents (lost update, dirty read, write skew, phantom).
3. **The invariant** — `Σ entries = 0` must hold *always*; it's a continuously assertable correctness property and your safety net.
4. **Crash-safe sagas** — multi-step transfers that compensate cleanly on partial failure and recover deterministically after a crash.
5. **The cost of the guarantee** — quantifying what serializable isolation / replication / fsync actually costs in throughput and tail latency (a great "I understand trade-offs" discussion).

---

## 3. Architecture & data flow

```
                        HTTP (JSON), Idempotency-Key header, OpenAPI
                                        │
                                        ▼
                          ┌─────────────────────────────┐
                          │   REST API (Spring Boot)     │
                          │   POST /transfers            │
                          │   GET  /accounts/{id}/balance│
                          └──────────────┬──────────────┘
                                         ▼
                          ┌─────────────────────────────┐
                          │  Idempotency layer           │
                          │  key seen? ─► return prior    │
                          │  result (no double-apply)     │
                          └──────────────┬──────────────┘
                                         ▼ (new request)
                          ┌─────────────────────────────┐
                          │  Ledger service (one ACID tx)│
                          │  • insert balanced entries:   │
                          │    debit(A), credit(B)  Σ = 0 │
                          │  • update balance w/ chosen    │
                          │    concurrency control         │
                          │    (optimistic | pessimistic)  │
                          └──────────────┬──────────────┘
                                         ▼
                          ┌─────────────────────────────┐
                          │  PostgreSQL (jOOQ)           │
                          │  accounts | ledger_entries    │
                          │  | idempotency_keys | sagas   │
                          │  append-only, immutable entries│
                          └──────────────┬──────────────┘
                                         ▼
   ┌──────────────────────────────┐   ┌──────────────────────────────┐
   │ Saga orchestrator             │   │ Reconciliation job            │
   │ step1→step2→…; on failure     │   │ asserts Σ(all entries)==0     │
   │ run compensations; persisted  │   │ + per-account balance ==      │
   │ state survives a crash        │   │ Σ(its entries); flags drift   │
   └──────────────────────────────┘   └──────────────────────────────┘
```

**Core invariants enforced everywhere:**
- Money is an integer count of minor units. **No `float`/`double` ever touches a monetary value.**
- `ledger_entries` is **append-only and immutable** — you never UPDATE or DELETE a posted entry; corrections are new compensating entries.
- Every transfer's entries **sum to zero**.
- Every mutating endpoint is **idempotent** via `Idempotency-Key`.
- A balance equals the sum of its account's entries (derived, then optionally materialized + reconciled).

---

## 4. Tech stack

Java 21 · Spring Boot 3.x · jOOQ · PostgreSQL · Flyway · Spring Security (lightweight; API-key/JWT) · JUnit 5 · Testcontainers · JMH · Micrometer/Prometheus (metrics) · Docker + Docker Compose · Make · GitHub Actions (CI). Optional deploy target: Fly.io / Render / Railway free tier.

---

## 5. Domain model (high-level)

- **account** — `id`, `name`, `currency` (USD for v1), optional `min_balance` (default 0 → no overdraft), `version` (for optimistic control), timestamps.
- **ledger_entry** — `id`, `transfer_id`, `account_id`, `direction` (DEBIT/CREDIT), `amount_minor` (BIGINT, > 0), `created_at`. **Immutable, append-only.** Per `transfer_id`, `Σ debits = Σ credits`.
- **transfer** — `id`, `idempotency_key`, `status`, `created_at`. The logical operation that produced a balanced set of entries.
- **idempotency_key** — `key` (unique), `request_fingerprint`, `response_snapshot`, `status`, `created_at`. Guarantees safe retries.
- **saga** / **saga_step** — `id`, `type`, `state`, `current_step`, `payload`, `compensation_log`. Persisted so a crash can resume or compensate.
- **(materialized) account_balance** *(optional)* — fast-read balance kept in lockstep with entries and continuously reconciled.

*(Exact column types, constraints, and indexes are written per-spec later — this is the high-level shape.)*

---

## 6. The SDD spec list — build in this order

This is the heart of the plan. **Spec-driven development rule: nothing gets implemented without a spec file in `specs/` first.** Each spec is a self-contained unit with a goal, in/out scope, and acceptance criteria (the measurable "done"). Build strictly in dependency order; each one is a *thin, shippable slice* that deepens the system.

> Numbering is stable (`0000`–`0009`); a per-spec file template lives in §8.2.

### SPEC 0000 — Walking skeleton & CI
**Goal.** A booting Spring Boot service wired to Postgres via jOOQ, with Flyway, Testcontainers, Docker Compose, and CI green.
**In scope.** `GET /health`; Flyway baseline migration; jOOQ codegen from the schema; one integration test hitting real Postgres in Testcontainers; GitHub Actions running it; `make` targets (`run`, `test`, `concurrency-test` stub).
**Out of scope.** Any ledger logic.
**Acceptance.** App boots, connects to Postgres, one integration test green in CI; `docker compose up` brings up app + db.

### SPEC 0001 — Double-entry core (the foundation)
**Goal.** Post a balanced transfer atomically; derive balances from entries.
**In scope.** `account` + `ledger_entry` schema; service that, in **one ACID transaction**, inserts debit+credit entries that sum to zero and rejects any unbalanced posting; balance = `Σ` entries.
**Out of scope.** HTTP layer, idempotency, contention handling.
**Acceptance.** A balanced transfer persists and conserves money; an unbalanced posting is rejected; balance equals the sum of entries.

### SPEC 0002 — Transfer API (thin end-to-end slice)
**Goal.** Make the ledger reachable and demoable.
**In scope.** `POST /transfers`, `GET /accounts/{id}/balance`, `GET /transfers/{id}`; request validation; OpenAPI spec; error model (insufficient funds, unknown account).
**Out of scope.** Idempotency semantics (header accepted but not yet enforced), advanced locking.
**Acceptance.** `curl` a transfer end-to-end; balances move; total across both accounts is unchanged; OpenAPI doc renders.

### SPEC 0003 — Idempotency
**Goal.** A retried payment is applied exactly once — even when two identical requests race.
**In scope.** `Idempotency-Key` header; `idempotency_key` table with a **unique constraint**; first request executes and stores its response; retries return the stored response; request-fingerprint mismatch on a reused key is rejected.
**Out of scope.** Multi-step sagas.
**Acceptance.** Replaying one key N times → exactly one transfer; **two concurrent identical requests → exactly one applied**, the other returns the same stored result.

### SPEC 0004 — Concurrency control (BOTH strategies)
**Goal.** Correct balances under heavy contention, via two interchangeable strategies.
**In scope.** **Optimistic** (account `version` / compare-and-set with retry) and **pessimistic** (`SELECT … FOR UPDATE`); a strategy toggle; explicit, documented isolation level; overdraft rule enforced (no illegal negative balance); bounded retry on optimistic conflict.
**Out of scope.** The benchmark itself (that's SPEC 0008).
**Acceptance.** Both strategies pass the concurrency hammer (no lost updates, no illegal negatives); behavior under a single hot account is correct for both; an ADR records the isolation-level decision.

### SPEC 0005 — Invariant & reconciliation
**Goal.** Continuously prove the books balance.
**In scope.** Background job + `GET /reconciliation/report` asserting `Σ(all entries) = 0` and `per-account balance = Σ(its entries)`; drift detection that flags (and logs/metrics-alerts on) any mismatch.
**Out of scope.** Auto-repair (report only).
**Acceptance.** Invariant holds after every test run; an **intentionally injected** drift is detected and reported.

### SPEC 0006 — Sagas & multi-step transfers with compensation
**Goal.** Multi-leg transfers that fully complete or fully roll back, even across a crash.
**In scope.** In-process orchestrator (e.g., A→B→C, or "place hold → capture"); **persisted** `saga`/`saga_step` state; compensation actions on partial failure; deterministic recovery on restart.
**Out of scope.** Cross-service / distributed sagas.
**Acceptance.** Killing the service at **every** saga step and restarting leaves each saga *fully completed or fully compensated*; books reconcile to `Σ = 0` afterward.

### SPEC 0007 — Concurrency & crash test harness (the headline)
**Goal.** Manufacture the resume's headline number.
**In scope.** A driver that fires thousands of concurrent transfers against hot accounts including ~30% duplicate idempotent requests; assertions for money conservation, no double-charge, invariant held; crash injection mid-saga; wired to `make concurrency-test`; emits a results table.
**Out of scope.** Micro-latency benchmarking (SPEC 0008).
**Acceptance.** **10,000 concurrent transfers, ~30% duplicates → 0 double-charges, 0 money created/destroyed, invariant held**; crashes injected at every saga step always reconcile.

### SPEC 0008 — Performance benchmark
**Goal.** Quantify throughput, tail latency, and the trade-offs.
**In scope.** JMH and/or a load generator measuring transfers/sec and p50/p99 under contention; **optimistic vs pessimistic** comparison with a documented crossover; the cost of `SERIALIZABLE` vs `READ COMMITTED`.
**Out of scope.** Distributed scaling.
**Acceptance.** A results table + a latency plot, e.g. *"Sustained X transfers/sec at p99 Y ms under serializable isolation; optimistic wins below contention C, pessimistic above."*

### SPEC 0009 — Observability, packaging & deploy
**Goal.** Make it production-shaped and presentable.
**In scope.** Structured logging; Micrometer/Prometheus metrics (transfer rate, conflict rate, reconciliation status); hardened Dockerfile; Compose (app + Postgres); README with the architecture diagram, the results table, and the ADRs; optional free-tier deploy + a `demo.sh` that double-submits a payment and shows it deduped live.
**Out of scope.** Multi-region.
**Acceptance.** `docker compose up` + `make concurrency-test` reproduce the headline result on a clean machine; README tells the whole story; (optional) a live URL.

### Stretch specs (mention as "future work," build only if time allows)
- **0010 Multi-currency + FX** — per-currency sub-ledgers; FX as a balanced cross-currency transfer.
- **0011 Transactional outbox** — publish ledger events atomically with the entry write (natural bridge to your Message Broker project).
- **0012 Event-sourced audit log** — full append-only history + point-in-time balance reconstruction.

---

## 7. Testing strategy & the results to earn

Correctness *is* the project, so testing is a first-class deliverable, run against **real Postgres** (Testcontainers), not a fake.

| Test | What it proves | Result to report |
|---|---|---|
| **Concurrency hammer** *(headline)* | No double-charge, no money invented/destroyed, idempotency under races | *10,000 concurrent transfers, 30% duplicates → 0 double-charges, invariant held* |
| **Crash-mid-saga** | Sagas are atomic across crashes | *Crashes injected at every saga step → every saga fully completed or fully compensated; books reconciled* |
| **Invariant check** | `Σ entries = 0` always holds | *Invariant held across all runs* |
| **Property tests** | Random transfer schedules never violate conservation/non-negativity | *N randomized schedules, 0 invariant violations* |
| **Throughput / latency** | Performance under contention + the cost of the guarantee | *Sustained X transfers/sec at p99 Y ms; optimistic vs pessimistic crossover documented* |

---

## 8. Claude Code setup for spec-driven development

This is the agentic scaffolding that makes the SDD loop fast *and* shows recruiters you build with modern, disciplined tooling. (As of 2026, **custom slash commands are unified into Skills** — a skill in `.claude/skills/<name>/SKILL.md` is invocable as `/name`. Files in `.claude/commands/` still work but skills are the recommended form.)

### 8.1 Repository layout
```
payments-ledger/
├── CLAUDE.md                      # the project "constitution" (see 8.3)
├── .mcp.json                      # project-scoped MCP servers
├── Makefile                       # run | test | concurrency-test | bench
├── docker-compose.yml
├── specs/                         # the source of truth for SDD
│   ├── TEMPLATE.md
│   ├── 0000-walking-skeleton.md
│   ├── 0001-double-entry-core.md
│   └── …                          # one file per spec in §6
├── docs/adr/                      # architecture decision records
│   ├── 0001-isolation-level.md
│   └── 0002-optimistic-vs-pessimistic.md
├── .claude/
│   ├── settings.json              # hooks + tool permissions (committed, team-shared)
│   ├── skills/                    # invocable workflows (= slash commands)
│   │   ├── spec-new/SKILL.md
│   │   ├── spec-implement/SKILL.md
│   │   ├── spec-verify/SKILL.md
│   │   ├── invariant-check/SKILL.md
│   │   ├── concurrency-test/SKILL.md
│   │   └── adr-new/SKILL.md
│   ├── agents/                    # subagents (isolated context)
│   │   ├── spec-author.md
│   │   ├── concurrency-reviewer.md
│   │   ├── sql-reviewer.md
│   │   └── test-engineer.md
│   └── hooks/                     # scripts fired by lifecycle events
│       ├── format-and-build.sh
│       ├── gate-commit.sh
│       └── block-float-money.sh
└── src/ …
```

### 8.2 The `specs/` template (so every spec is consistent)
```markdown
# SPEC <NNNN> — <name>
Status: draft | approved | implemented | verified
Depends on: <spec ids>

## Goal
<one paragraph: the capability this delivers>

## In scope
- …
## Out of scope
- …

## Design notes (high-level)
<key choices; link any ADR>

## Acceptance criteria (the measurable "done")
- [ ] …
- [ ] …

## Test plan
<which tests in §7 cover this>
```

### 8.3 `CLAUDE.md` — proposed content (the constitution Claude reads every session)
```markdown
# Payments Ledger — Project Memory

## What this is
A double-entry payments ledger REST API in Java 21 + Spring Boot + jOOQ + Postgres.
Correctness under concurrency and crashes is THE deliverable. We work spec-first.

## Non-negotiable invariants (NEVER violate)
1. Money is an integer count of minor units (long/BigInteger). NEVER float/double for money.
2. `ledger_entries` is append-only and immutable. Never UPDATE/DELETE a posted entry;
   corrections are new compensating entries.
3. Every transfer's entries sum to zero (Σ debits = Σ credits).
4. Every mutating endpoint is idempotent via the `Idempotency-Key` header.
5. A balance equals the sum of its account's entries.
6. All SQL goes through jOOQ. Locking and isolation are explicit and documented in an ADR.

## How we work (SDD loop)
- No code without an approved spec in `specs/`. If asked to build something with no spec,
  create/Ë†approve the spec first (use /spec-new).
- Write the failing test first, then implement to green.
- Record every meaningful decision (isolation level, locking strategy) as an ADR (/adr-new).
- Do not commit unless the build, unit tests, and the invariant check pass.

## Commands
- Run:              make run
- Unit/integration: make test           (JUnit 5 + Testcontainers, real Postgres)
- Headline test:    make concurrency-test
- Benchmark:        make bench           (JMH)
- DB migrations:    Flyway (src/main/resources/db/migration)

## Conventions
- Java 21, virtual threads for request handling.
- Package-by-feature; service layer owns the transaction boundary.
- jOOQ generated code in `org.ledger.db.generated` (do not edit by hand).
- Tests must run against Testcontainers Postgres, never an in-memory DB.

## Directory map
- specs/     specifications (source of truth)
- docs/adr/  architecture decision records
- .claude/   skills, subagents, hooks
```

### 8.4 Skills (invocable workflows = slash commands)
Each is a `SKILL.md` with frontmatter (`name`, `description`; `disable-model-invocation: true` for manual-only; `context: fork` to run in a subagent).

| Skill (`/command`) | Purpose | Invocation |
|---|---|---|
| `/spec-new` | Scaffold a new `specs/NNNN-*.md` from the template; interview the user for goal, in/out scope, acceptance criteria. | manual |
| `/spec-implement` | Given a spec id, plan → write the failing test → implement to green, strictly within that spec's scope. | manual |
| `/spec-verify` | Run the tests + invariant + lint mapped to a spec and report PASS/FAIL against each acceptance criterion. | manual |
| `/invariant-check` | Run the `Σ entries = 0` + per-account reconciliation and report. | auto-invokable after large changes |
| `/concurrency-test` | Run the chaos/concurrency harness and print the headline results table. | manual |
| `/adr-new` | Capture an architecture decision (e.g. isolation level) as `docs/adr/NNNN-*.md`. | manual |

> The `/adr-new` + `/spec-new` discipline is itself an interview flex: it shows you design before you code and leave an auditable decision trail.

### 8.5 Subagents (`.claude/agents/`, isolated context)
Keep the main session focused; delegate heavy or specialized work. Assign a capable model to reviewers, a fast/cheap model to mechanical search work.

| Subagent | Role | Suggested model |
|---|---|---|
| `spec-author` | Turn a rough idea into a rigorous spec: tighten scope, enumerate edge cases, write measurable acceptance criteria. | capable (e.g. Opus-class) |
| `concurrency-reviewer` | **The signature subagent.** Review a diff *only* for race conditions: lost updates, missing/incorrect locks, isolation anomalies (write skew, phantoms), idempotency holes, non-atomic read-modify-write. | capable |
| `sql-reviewer` | Review generated jOOQ/SQL: correctness, locking clauses, index usage, N+1, accidental full scans, `EXPLAIN` sanity. | capable |
| `test-engineer` | Strengthen the concurrency/property/crash tests; find the schedule that breaks an invariant. | capable |

### 8.6 Hooks (`.claude/settings.json`, fire on lifecycle events)
Hooks turn your non-negotiables into automatic guardrails (types available: command / http / prompt / agent / mcp_tool).

| Hook | Event + matcher | Action |
|---|---|---|
| `format-and-build` | `PostToolUse` on `Edit`/`Write` | Run the formatter (Spotless) + `mvn -q compile` + fast unit tests; feed failures back to Claude as text. |
| `block-float-money` | `PreToolUse` on `Edit`/`Write` | **Reject** any diff that introduces `float`/`double`/`Float`/`Double` on a monetary field, or a direct balance-column `UPDATE` outside the ledger service. Enforces invariant #1/#2 mechanically. |
| `gate-commit` | `PreToolUse` on `Bash` (matcher: `git commit`) | Block the commit unless `make test` *and* `/invariant-check` pass. The quality gate. |
| `notify-done` | `Stop` / `Notification` | Desktop notification when the long `concurrency-test` finishes. |

Example `settings.json` shape:
```json
{
  "hooks": {
    "PreToolUse": [
      { "matcher": "Edit|Write",
        "hooks": [{ "type": "command", "command": "$CLAUDE_PROJECT_DIR/.claude/hooks/block-float-money.sh" }] },
      { "matcher": "Bash",
        "hooks": [{ "type": "command", "command": "$CLAUDE_PROJECT_DIR/.claude/hooks/gate-commit.sh" }] }
    ],
    "PostToolUse": [
      { "matcher": "Edit|Write",
        "hooks": [{ "type": "command", "command": "$CLAUDE_PROJECT_DIR/.claude/hooks/format-and-build.sh" }] }
    ]
  }
}
```
> Security note: hooks run with your credentials. Review every hook script; never run unreviewed third-party hooks.

### 8.7 MCP servers (`.mcp.json`, project scope)
Connect Claude Code to the systems it should reason about. Keep it lean.

| MCP server | Why it earns its place here |
|---|---|
| **Postgres MCP** | Let Claude inspect the live schema, run `EXPLAIN`, verify indexes and locking behavior, and query the ledger while building. Directly serves the DB-correctness mission. |
| **GitHub MCP** | Tie each spec to an issue/PR; let Claude open PRs and read CI status, keeping the SDD trail in version control. |

```json
{
  "mcpServers": {
    "postgres": { "command": "npx", "args": ["-y", "<postgres-mcp-server>", "postgresql://localhost:5432/ledger"] },
    "github":   { "command": "npx", "args": ["-y", "@modelcontextprotocol/server-github"] }
  }
}
```
*(Pin to whichever Postgres/GitHub MCP servers you trust; add via `claude mcp add` or commit `.mcp.json`.)*

### 8.8 Plugins
A plugin bundles skills + subagents + hooks + MCP into one installable unit, distributed via a marketplace (a git repo). **For a single portfolio repo you don't need one** — project-scoped `.claude/` is enough.

The *strategic* move: once this SDD setup is proven here, **package it as a reusable plugin** (e.g. `sdd-toolkit`: the `spec-*`/`adr-new` skills + `concurrency-reviewer` + the guardrail hooks) and reuse it across your other three resume projects. "I built a reusable spec-driven-development plugin and used it across four projects" is a genuinely senior story. Build the plugin *after* the workflow is stable, not before.

### 8.9 How the pieces fit — the SDD loop in practice
```
/spec-new ──► spec-author subagent sharpens it ──► you approve specs/NNNN.md
      │
      ▼
/spec-implement (writes failing test → implements)
      │   PostToolUse hook: format + build + fast tests on every edit
      │   block-float-money hook: rejects money-as-float / illegal balance writes
      ▼
concurrency-reviewer + sql-reviewer subagents review the diff
      │
      ▼
/spec-verify  ──► checks each acceptance criterion ──► /adr-new for big decisions
      │
      ▼
gate-commit hook: commit only if tests + invariant pass ──► PR via GitHub MCP
      │
      └────────────────────► next spec
```
This loop is the thing to screenshot/describe in the README: it shows specs as the source of truth, automated guardrails enforcing the invariants, and isolated expert reviewers — exactly the disciplined engineering recruiters want to see.

---

## 9. Deliverables — what to put in front of a recruiter

1. **Public repo** with a README that opens with the headline result and the architecture diagram.
2. **The results table** (§7) — the measured guarantees, front and center.
3. **`docs/adr/`** — the isolation-level and optimistic-vs-pessimistic decisions, written up. (Interviewers love these.)
4. **`make concurrency-test`** reproducing the headline number on a clean machine.
5. **`demo.sh`** — double-submits a payment live and shows it deduped; transfers money and shows conservation.
6. **(Optional) live URL** on a free tier.
7. **The `.claude/` directory itself**, committed — visible proof of the spec-driven, guardrailed workflow.

---

## 10. Resume bullets (ready to adapt)

- *Built a double-entry payments ledger (Java 21, Spring Boot, jOOQ, Postgres) and **proved** correctness under load: **10,000 concurrent transfers with 30% duplicate requests → 0 double-charges, 0 money created/destroyed**, ledger invariant (Σ = 0) held across all runs.*
- *Implemented idempotent payments (`Idempotency-Key` + unique constraints) guaranteeing exactly-once application even under racing identical requests.*
- *Designed and **benchmarked** both optimistic and pessimistic concurrency control against real Postgres (Testcontainers + JMH), documenting the contention crossover and the cost of serializable isolation.*
- *Built crash-recoverable sagas with compensation; injecting crashes at every step left every transfer fully completed or fully compensated, with the books always reconciling.*

**Skills proven:** transactional correctness (ACID), isolation levels & concurrency control, idempotency, double-entry modeling, sagas/compensation, invariant-based & property testing, concurrency/chaos stress testing, PostgreSQL + jOOQ, Testcontainers, JMH benchmarking, Docker, and spec-driven development with an agentic toolchain.

---

## 11. Suggested timeline (~4 focused weekends)

| Weekend | Specs | Outcome |
|---|---|---|
| 1 | 0000–0002 | Walking skeleton + double-entry core + transfer API; you can curl a balanced transfer. |
| 2 | 0003–0004 | Idempotency + both concurrency strategies; races are handled correctly. |
| 3 | 0005–0007 | Invariant/reconciliation + sagas + the concurrency/crash harness → **the headline number lands**. |
| 4 | 0008–0009 | Benchmarks, observability, packaging, README + ADRs, optional deploy. |

> Most-bounded of the four resume projects, highest credibility-per-weekend — which is exactly why it's slotted to build right after Career Copilot and before the more open-ended Message Broker.
