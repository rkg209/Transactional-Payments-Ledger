# requirements.md — Transactional Payments Ledger

---

## 1. Business Goals

**BG-1.** Demonstrate provable correctness of a double-entry payments ledger under heavy concurrency and mid-transaction crashes, producing a measured headline result: *10,000 concurrent transfers with ~30% duplicate (idempotent) requests against hot accounts → 0 double-charges, 0 money created or destroyed, Σ-of-entries invariant held across every run.*

**BG-2.** Serve as a high-credibility portfolio artifact targeting fintech / payments / backend-infrastructure interviewers, specifically covering the design patterns underlying real payment systems (double-entry bookkeeping, idempotency, isolation levels, sagas with compensation).

**BG-3.** Produce a publicly reproducible result: any reviewer can clone the repository, run `make concurrency-test`, and independently verify the headline correctness guarantee on a clean machine.

**BG-4.** Generate durable interview collateral — Architecture Decision Records (ADRs), a results table, and a spec-driven development trail — that demonstrate disciplined engineering process, not just a feature list.

**BG-5.** Establish a reusable spec-driven development (SDD) workflow (skills, subagents, hooks, MCP servers) that can be packaged and applied across other portfolio projects.

---

## 2. Stakeholders

**SH-1. Primary developer / owner.** The individual building and maintaining the project; responsible for all implementation, testing, and documentation decisions.

**SH-2. Fintech / backend interviewers.** Technical evaluators at payments-adjacent companies (Stripe-style) who will review the repository, run the test harness, read the ADRs, and probe the design in interviews. Their acceptance of the correctness claims is the ultimate success criterion.

**SH-3. Recruiters / hiring managers.** Non-technical reviewers who read the README headline result and resume bullets; they need a clear, scannable proof of impact.

**SH-4. Future collaborators / plugin consumers.** Engineers who may reuse the `.claude/` SDD toolkit (skills, hooks, subagents) on other projects once it is extracted as a plugin.

---

## 3. Users / Personas

**UP-1. API consumer (automated / curl).** Any HTTP client — a `demo.sh` script, a test harness, or a human with curl — that calls `POST /transfers`, `GET /accounts/{id}/balance`, and `GET /transfers/{id}`. Expects JSON responses, idempotent retry semantics, and clear error messages.

**UP-2. Concurrency / chaos test harness.** The automated driver (SPEC 0007) that fires thousands of concurrent transfer requests, injects duplicates, and kills the service mid-saga. Consumes the same REST API as UP-1; its "user story" is: *I must never observe a double-charge, a negative balance below the minimum, or a non-zero ledger sum.*

**UP-3. Reconciliation job consumer.** An operator or monitoring system that calls `GET /reconciliation/report` and reads Prometheus metrics to verify the books are balanced. Expects a machine-readable report and alertable metrics.

**UP-4. Developer / spec author.** The primary developer using the Claude Code SDD workflow (skills, subagents, hooks) to write specs, implement features, and gate commits. Interacts with the system via `make` targets and `/skill` invocations.

---

## 4. Functional Requirements

### 4.1 Core Ledger

**FR-1.** The system SHALL represent all monetary values as integer counts of minor units (cents for USD) stored as `BIGINT`. No `float`, `double`, `Float`, or `Double` type SHALL ever be used to represent a monetary value anywhere in the codebase.

**FR-2.** The system SHALL implement double-entry bookkeeping: every transfer SHALL produce a balanced set of ledger entries (one or more debits and one or more credits) where the algebraic sum of all entries for that transfer equals exactly zero.

**FR-3.** The system SHALL reject any attempt to post an unbalanced set of entries (i.e., where Σ entries ≠ 0) and return an error without persisting any partial state.

**FR-4.** The `ledger_entries` table SHALL be append-only and immutable. The system SHALL never issue an `UPDATE` or `DELETE` against a posted ledger entry. All corrections SHALL be implemented as new compensating entries.

**FR-5.** An account balance SHALL be defined as and derivable from the sum of all ledger entries for that account. Any materialized balance cache SHALL be kept in lockstep with the entry log and continuously reconciled.

**FR-6.** The system SHALL enforce a minimum balance constraint per account (defaulting to 0, i.e., no overdraft). Any transfer that would reduce an account balance below its configured minimum SHALL be rejected atomically before any entries are written.

### 4.2 Transfer API

**FR-7.** The system SHALL expose a `POST /transfers` endpoint that accepts a transfer request (source account, destination account, amount in minor units, currency) and executes the corresponding double-entry posting in a single ACID transaction.

**FR-8.** The system SHALL expose a `GET /accounts/{id}/balance` endpoint that returns the current balance of the specified account in minor units.

**FR-9.** The system SHALL expose a `GET /transfers/{id}` endpoint that returns the status and details of a previously submitted transfer.

**FR-10.** The system SHALL expose a `GET /health` endpoint that returns the service health status including database connectivity.

**FR-11.** The system SHALL expose a `GET /reconciliation/report` endpoint that returns the current reconciliation status, including whether Σ(all entries) = 0 and whether each account's materialized balance matches its entry sum.

**FR-12.** The system SHALL validate all incoming transfer requests and return structured error responses for: unknown account IDs, insufficient funds (balance would fall below minimum), invalid or negative amounts, and malformed request bodies.

**FR-13.** The system SHALL publish an OpenAPI specification document describing all endpoints, request/response schemas, and error models.

### 4.3 Idempotency

**FR-14.** Every mutating endpoint (at minimum `POST /transfers`) SHALL accept an `Idempotency-Key` HTTP request header.

**FR-15.** The system SHALL store each idempotency key alongside a fingerprint of the request body and a snapshot of the response in the `idempotency_keys` table, protected by a database-level unique constraint on the key value.

**FR-16.** When a request arrives with an `Idempotency-Key` that has already been successfully processed, the system SHALL return the stored response snapshot without re-executing the transfer logic.

**FR-17.** When two concurrent requests arrive with the same `Idempotency-Key`, the system SHALL ensure exactly one transfer is applied. The second concurrent request SHALL either receive the stored result of the first or block until the first completes and then receive its result. Under no circumstances SHALL both requests apply a transfer.

**FR-18.** When a request arrives with an `Idempotency-Key` that was previously used with a different request fingerprint (i.e., different body), the system SHALL reject the request with an appropriate error (e.g., HTTP 422 or 409) and SHALL NOT execute the transfer.

### 4.4 Concurrency Control

**FR-19.** The system SHALL implement an **optimistic concurrency control** strategy using an account `version` column (compare-and-set / optimistic locking). On version conflict, the system SHALL retry the transaction up to a documented bounded maximum number of attempts before returning a conflict error.

**FR-20.** The system SHALL implement a **pessimistic concurrency control** strategy using `SELECT … FOR UPDATE` to lock account rows before reading and modifying balances.

**FR-21.** The system SHALL provide a configuration toggle to select between the optimistic and pessimistic concurrency control strategies at startup or per-request (exact granularity to be defined in SPEC 0004).

**FR-22.** The chosen isolation level for transfer transactions SHALL be explicitly documented in an Architecture Decision Record (ADR), naming the specific anomalies it prevents (lost update, dirty read, write skew, phantom read) and the anomalies it does not prevent.

**FR-23.** Both concurrency control strategies SHALL correctly enforce the minimum balance constraint (FR-6) under concurrent load with no lost updates and no illegal negative balances.

### 4.5 Reconciliation & Invariant

**FR-24.** The system SHALL run a background reconciliation job that periodically asserts: (a) Σ(all ledger entries across all accounts) = 0, and (b) for each account, the materialized balance equals the sum of that account's ledger entries.

**FR-25.** When the reconciliation job detects a discrepancy (drift), it SHALL log a structured error, increment a Prometheus/Micrometer counter metric, and include the discrepancy in the `GET /reconciliation/report` response. It SHALL NOT auto-repair the discrepancy.

**FR-26.** The system SHALL support intentional drift injection (for testing purposes) that the reconciliation job can detect and report.

### 4.6 Sagas & Multi-Step Transfers

**FR-27.** The system SHALL implement an in-process saga orchestrator capable of executing multi-step transfer workflows (e.g., A→B→C, or "place hold → capture → settle").

**FR-28.** Each saga's state (type, current step, payload, compensation log) SHALL be persisted to the `saga` and `saga_step` tables in the database before each step is executed, such that the state survives a process crash.

**FR-29.** When a saga step fails, the orchestrator SHALL execute the compensation actions for all previously completed steps in reverse order, leaving the system in a fully rolled-back state.

**FR-30.** On service restart, the system SHALL detect any sagas that were in-progress at the time of the crash and resume or compensate them deterministically, based on their persisted state.

**FR-31.** After any saga completes (whether successfully or via compensation), the ledger invariant (FR-2, FR-24) SHALL hold.

### 4.7 Test Harness & Benchmarks

**FR-32.** The system SHALL include a concurrency and chaos test harness (invocable via `make concurrency-test`) that: fires at least 10,000 concurrent transfer requests against hot accounts; includes approximately 30% duplicate requests (same idempotency key); injects process crashes at each saga step; and asserts at the end that 0 double-charges occurred, 0 money was created or destroyed, and the ledger invariant holds.

**FR-33.** The test harness SHALL emit a structured results table to stdout summarizing: total requests fired, unique transfers applied, duplicate requests detected, double-charges detected, money created/destroyed, invariant status, and crash-recovery outcomes.

**FR-34.** The system SHALL include JMH benchmarks (invocable via `make bench`) measuring: transfer throughput (transfers/sec) and tail latency (p50, p99) under contention for both optimistic and pessimistic concurrency strategies, and for both `READ COMMITTED` and `SERIALIZABLE` isolation levels.

**FR-35.** The benchmark results SHALL be captured in a documented results table identifying the contention level at which optimistic and pessimistic strategies cross over in performance, and the throughput cost of `SERIALIZABLE` vs `READ COMMITTED` isolation.

### 4.8 Spec-Driven Development Workflow

**FR-36.** Every implemented feature SHALL have a corresponding approved spec file in the `specs/` directory following the standard template (SPEC 0000–0009 as defined in the project plan, plus any additions).

**FR-37.** The repository SHALL include Claude Code skills (`/spec-new`, `/spec-implement`, `/spec-verify`, `/invariant-check`, `/concurrency-test`, `/adr-new`) as `.claude/skills/<name>/SKILL.md` files.

**FR-38.** The repository SHALL include Claude Code subagent definitions (`.claude/agents/`) for: `spec-author`, `concurrency-reviewer`, `sql-reviewer`, and `test-engineer`.

**FR-39.** The repository SHALL include Claude Code lifecycle hooks (`.claude/settings.json` + `.claude/hooks/`) implementing: post-edit format-and-build, pre-edit float-money blocking, and pre-commit gate enforcement.

**FR-40.** The `block-float-money` hook SHALL automatically reject any code edit that introduces `float`, `double`, `Float`, or `Double` on a monetary field, or a direct balance-column `UPDATE` outside the ledger service layer.

**FR-41.** The `gate-commit` hook SHALL block any `git commit` unless `make test` and `/invariant-check` both pass.

---

## 5. Non-Functional Requirements

### 5.1 Correctness (Primary Quality Attribute)

**NFR-1.** The system SHALL achieve 0 double-charges across a run of 10,000 concurrent transfers with ~30% duplicate idempotent requests. This is a hard pass/fail criterion, not a statistical target.

**NFR-2.** The system SHALL achieve 0 net money creation or destruction across any test run. The sum of all ledger entries SHALL equal exactly zero at all times after any committed transaction.

**NFR-3.** The ledger invariant (Σ entries = 0 globally; per-account balance = Σ account entries) SHALL hold after every committed transaction, verifiable by the reconciliation job.

**NFR-4.** Every saga SHALL terminate in either a fully-completed or fully-compensated state after any number of injected crashes at any step. No saga SHALL be left in a permanently inconsistent intermediate state.

**NFR-5.** Idempotency SHALL be guaranteed even when two identical requests arrive simultaneously. The system SHALL apply the transfer exactly once regardless of concurrency.

### 5.2 Performance

**NFR-6.** The system SHALL sustain a documented minimum throughput of transfers per second under serializable isolation (exact figure to be measured and recorded in the SPEC 0008 results table; the target is a measured result, not a pre-set SLA).

**NFR-7.** The system SHALL document p50 and p99 transfer latency under contention for each combination of concurrency strategy (optimistic / pessimistic) and isolation level (`READ COMMITTED` / `SERIALIZABLE`).

**NFR-8.** The optimistic concurrency strategy SHALL outperform the pessimistic strategy below a documented contention threshold (hot-account concurrency level), and the pessimistic strategy SHALL outperform or match the optimistic strategy above that threshold. Both claims SHALL be backed by benchmark data.

**NFR-9.** The concurrency test harness (`make concurrency-test`) SHALL complete within a reasonable wall-clock time on a developer laptop (target: under 10 minutes; exact bound to be set once baseline is measured).

### 5.3 Reliability & Crash Safety

**NFR-10.** The service SHALL recover deterministically from a process crash at any point during saga execution, with no manual intervention required, within one restart cycle.

**NFR-11.** The service SHALL use PostgreSQL's ACID guarantees (real fsync, real transactions) and SHALL NOT rely on in-memory state for correctness. All correctness-critical state SHALL be durable in the database.

### 5.4 Reproducibility

**NFR-12.** The headline correctness result SHALL be fully reproducible on a clean machine by running `docker compose up` followed by `make concurrency-test`, with no additional setup beyond Docker and Make.

**NFR-13.** All database schema changes SHALL be managed by versioned Flyway migrations. The schema SHALL be reproducible from scratch by running migrations against a blank PostgreSQL instance.

### 5.5 Maintainability & Auditability

**NFR-14.** All SQL executed by the application SHALL be written using jOOQ's type-safe DSL. Raw string SQL, JPQL, and HQL are prohibited. Locking clauses (`SELECT … FOR UPDATE`) and isolation level settings SHALL be explicit and visible in the jOOQ call sites.

**NFR-15.** Every significant architectural decision (isolation level choice, optimistic vs. pessimistic strategy, saga design) SHALL be documented as an ADR in `docs/adr/` before the corresponding code is merged.

**NFR-16.** The `ledger_entries` table SHALL have no `UPDATE` or `DELETE` statements issued against it anywhere in the application code. This SHALL be enforced by the `block-float-money` hook and verified by code review.

**NFR-17.** All tests SHALL run against a real PostgreSQL instance managed by Testcontainers. Tests against in-memory databases (H2, HSQLDB) are prohibited for any test that exercises transactional correctness.

**NFR-18.** The codebase SHALL follow a package-by-feature structure. The service layer SHALL own all transaction boundaries.

### 5.6 Observability

**NFR-19.** The service SHALL emit structured (JSON) logs for all transfer operations, saga state transitions, reconciliation runs, and error conditions.

**NFR-20.** The service SHALL expose Micrometer/Prometheus metrics including at minimum: transfer rate (transfers/sec), optimistic conflict/retry rate, reconciliation status (pass/fail), saga completion rate, and saga compensation rate.

**NFR-21.** All metrics SHALL be scrapable from a `/actuator/prometheus` (or equivalent) endpoint.

### 5.7 Security

**NFR-22.** The API SHALL require authentication on all non-health endpoints, implemented via API key or JWT using Spring Security. Unauthenticated requests SHALL receive HTTP 401.

**NFR-23.** The service SHALL not log raw monetary amounts or account identifiers at DEBUG level in production-profile configurations.

### 5.8 Code Quality Gates

**NFR-24.** The build SHALL fail if any source file introduces `float`, `double`, `Float`, or `Double` in a monetary context, as enforced by the `block-float-money` pre-edit hook and a corresponding static analysis rule.

**NFR-25.** No commit SHALL be accepted unless `make test` (JUnit 5 + Testcontainers integration tests) passes in full, as enforced by the `gate-commit` hook.

**NFR-26.** Code formatting SHALL be enforced by Spotless on every file edit via the `format-and-build` post-edit hook.

---

## 6. Constraints

**CON-1. Language.** Java 21 with virtual threads. No other JVM language (Kotlin, Scala, Groovy) is permitted for production source code.

**CON-2. Data access.** All database access SHALL use jOOQ. JPA, Hibernate, Spring Data JPA, and MyBatis are prohibited.

**CON-3. Database.** PostgreSQL is the only permitted database. In-memory databases are prohibited for correctness tests.

**CON-4. Money representation.** All monetary values SHALL be represented as `long` or `BigInteger` (integer minor units / cents). `float`, `double`, `Float`, `Double`, `BigDecimal` (for storage), and any floating-point type are prohibited for monetary values.

**CON-5. Ledger immutability.** `ledger_entries` rows SHALL never be updated or deleted after insertion. This is a hard architectural constraint, not a guideline.

**CON-6. Single currency.** Version 1 supports USD only. Multi-currency and FX are explicitly deferred to stretch SPEC 0010.

**CON-7. In-process sagas only.** The saga orchestrator SHALL be in-process (within the Spring Boot application). Distributed sagas, message brokers, and external orchestration engines are out of scope for v1.

**CON-8. Spec-first development.** No production code SHALL be written without a corresponding approved spec file in `specs/`. This is a process constraint enforced by the SDD workflow.

**CON-9. Test database.** All integration and correctness tests SHALL use Testcontainers to spin up a real PostgreSQL instance. Mocking the database layer for correctness tests is prohibited.

**CON-10. Build tooling.** Maven or Gradle SHALL be used as the build tool. The `Makefile` SHALL provide `run`, `test`, `concurrency-test`, and `bench` targets as the canonical entry points.

**CON-11. Schema management.** All schema changes SHALL be applied via Flyway versioned migrations. Ad-hoc DDL against the database is prohibited.

**CON-12. Scope boundary — distributed systems.** Cross-service communication, message brokers, event streaming, and distributed transactions are explicitly out of scope for v1 (noted as stretch goals SPEC 0011–0012).

**CON-13. Scope boundary — multi-region.** Multi-region deployment, replication topology, and geo-distribution are out of scope.

**CON-14. Scope boundary — FX.** Foreign exchange rate management and multi-currency transfers are out of scope for v1.

---

## 7. Technologies

### 7.1 Runtime & Framework

| Component | Technology | Version / Notes |
|---|---|---|
| Language | Java | 21 (LTS); virtual threads enabled |
| Application framework | Spring Boot | 3.x |
| HTTP server | Spring MVC or Spring WebFlux | Virtual-thread executor |
| Security | Spring Security | API key or JWT authentication |
| Database access | jOOQ | Type-safe SQL DSL; codegen from schema |
| Schema migrations | Flyway | Versioned migrations in `src/main/resources/db/migration` |
| Database | PostgreSQL | Latest stable; real ACID, real fsync |
| Metrics | Micrometer + Prometheus | Exposed via Actuator |
| Logging | SLF4J + Logback | Structured JSON output |

### 7.2 Testing

| Component | Technology | Notes |
|---|---|---|
| Unit / integration tests | JUnit 5 | All correctness tests against real Postgres |
| Database provisioning (tests) | Testcontainers | PostgreSQL container per test suite |
| Micro-benchmarks | JMH | Throughput and latency measurement |
| Concurrency / chaos harness | Custom (Java) | Invocable via `make concurrency-test` |
| Code formatting | Spotless | Enforced on every edit via hook |

### 7.3 Infrastructure & Tooling

| Component | Technology | Notes |
|---|---|---|
| Containerization | Docker | Hardened multi-stage Dockerfile |
| Local orchestration | Docker Compose | App + PostgreSQL |
| Build / task runner | Make | Canonical targets: `run`, `test`, `concurrency-test`, `bench` |
| CI | GitHub Actions | Runs `make test` on every push/PR |
| API specification | OpenAPI 3.x | Generated or hand-authored; rendered via Swagger UI or Redoc |

### 7.4 Agentic / SDD Toolchain

| Component | Technology | Notes |
|---|---|---|
| AI coding assistant | Claude Code | Project-scoped configuration |
| Skills (slash commands) | `.claude/skills/` | `spec-new`, `spec-implement`, `spec-verify`, `invariant-check`, `concurrency-test`, `adr-new` |
| Subagents | `.claude/agents/` | `spec-author`, `concurrency-reviewer`, `sql-reviewer`, `test-engineer` |
| Lifecycle hooks | `.claude/settings.json` + shell scripts | `format-and-build`, `block-float-money`, `gate-commit`, `notify-done` |
| MCP servers | `.mcp.json` | PostgreSQL MCP (live schema inspection, EXPLAIN); GitHub MCP (issues, PRs, CI status) |

### 7.5 Optional / Stretch

| Component | Technology | Notes |
|---|---|---|
| Cloud deployment | Fly.io / Render / Railway | Free tier; optional live URL |
| Plugin packaging | Claude Code plugin | Extract `.claude/` SDD toolkit for reuse across portfolio projects |

---

## 8. Deployment Requirements

**DR-1.** The application SHALL be packaged as a Docker image using a hardened, multi-stage `Dockerfile` that produces a minimal production image.

**DR-2.** A `docker-compose.yml` SHALL be provided that starts the application container and a PostgreSQL container with a single `docker compose up` command, with no additional configuration required beyond Docker being installed.

**DR-3.** The `docker compose up` sequence SHALL automatically apply all Flyway migrations against the PostgreSQL container before the application begins serving requests.

**DR-4.** The `make concurrency-test` target SHALL be executable on a clean machine (with Docker and Make installed) and SHALL reproduce the headline correctness result (FR-32, NFR-1, NFR-2) without any additional setup.

**DR-5.** The `make run` target SHALL start the application locally (with a running PostgreSQL instance available) for development and demo purposes.

**DR-6.** The `make test` target SHALL execute the full JUnit 5 + Testcontainers test suite, spinning up its own PostgreSQL container, requiring only a Java 21 JDK and Docker to be installed.

**DR-7.** The `make bench` target SHALL execute the JMH benchmark suite and emit the results table to stdout.

**DR-8.** A `demo.sh` script SHALL be provided that: creates two accounts, submits a transfer, submits the same transfer again with the same idempotency key, and demonstrates that exactly one transfer was applied and the balances conserve money.

**DR-9.** The GitHub Actions CI pipeline SHALL execute `make test` on every push and pull request to the main branch, using a PostgreSQL service container (not Testcontainers) or Testcontainers within the Actions runner, and SHALL fail the build on any test failure.

**DR-10.** Environment-specific configuration (database URL, credentials, isolation level toggle, concurrency strategy toggle) SHALL be externalized via environment variables or a Spring profile, with safe defaults provided for local development.

**DR-11. (Optional)** The application SHOULD be deployable to a free-tier cloud platform (Fly.io, Render, or Railway) with a publicly accessible URL, enabling live demonstration of the idempotency guarantee via `demo.sh` against the deployed instance.

**DR-12.** The repository README SHALL include: the headline correctness result, the architecture diagram, the full results table (§7 of the project spec), links to the ADRs, instructions to reproduce the headline result, and a description of the SDD workflow.

---

*End of requirements.md — version 1.0. All functional requirements (FR-1 through FR-41) and non-functional requirements (NFR-1 through NFR-26) are stable identifiers for reference in downstream spec files (`specs/NNNN-*.md`) and ADRs (`docs/adr/`).*