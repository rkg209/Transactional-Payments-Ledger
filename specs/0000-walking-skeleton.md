# SPEC 0000 — Walking skeleton & CI

Status: approved
Depends on: none
Requirements: FR-10, NFR-13, NFR-17, CON-1, CON-2, CON-3, CON-9, CON-10, CON-11, DR-1, DR-2, DR-3, DR-6, DR-9

## Goal

A booting Spring Boot service wired to PostgreSQL through jOOQ, with Flyway migrations applied,
Testcontainers-backed integration tests, Docker Compose, and CI green. This spec delivers **no
ledger logic whatsoever** — its entire purpose is to prove the pipeline works end to end, so that
every later spec is written against a foundation known to be sound. If the skeleton is wrong,
every correctness claim built on top of it is suspect.

## In scope

- `GET /health` returning service status and database connectivity.
- Flyway migrations `V1__initial_schema.sql` (all seven tables, constraints, and the
  `ledger_entries` immutability trigger) and `V2__indexes.sql`, transcribed from
  `planning/04-database-schema.sql`.
- jOOQ code generation from the migrated schema into `org.ledger.db.generated`.
- One integration test hitting a **real PostgreSQL** in Testcontainers.
- `Makefile` with `run`, `test`, `format` targets, plus `concurrency-test` and `bench` stubs.
- `docker-compose.yml` (app + Postgres) and a multi-stage `Dockerfile`.
- GitHub Actions CI running `make test`.
- Spring Boot configured for **virtual threads**.

## Out of scope

- Any ledger logic: no transfers, no entries, no balances. (SPEC 0001+)
- The HTTP API beyond `/health`. (SPEC 0002)
- Idempotency, concurrency strategies, sagas, reconciliation. (SPEC 0003–0006)
- Spring Security. The API is unauthenticated at this stage; auth lands with the API in SPEC 0002.

## Design notes

The full schema ships in this spec even though no table but none is read yet. This is deliberate:
jOOQ's code generator reads the **live, migrated schema** to produce its type-safe classes, so the
schema must exist before any repository code can compile. Introducing tables migration-by-migration
alongside their features would mean regenerating and recompiling jOOQ on every spec — and would
make `V1` a lie about what the schema is.

Schema source of truth is `planning/04-database-schema.sql`. That file is truncated at line 618 and
is missing indexes for `sagas`, `saga_steps`, and `reconciliation_reports`; we complete them in the
spirit of the ones it does specify (see `progress_report.md` entry [001]).

`/health` is a hand-written controller, distinct from Actuator's `/actuator/health`. FR-10 asks for
`/health` specifically, and we want DB connectivity to be an explicit assertion we control rather
than an Actuator default we inherit.

## Acceptance criteria (the measurable "done")

- [ ] `mvn clean verify` succeeds from a clean checkout.
- [ ] Flyway applies `V1` and `V2` against a blank PostgreSQL and the schema matches
      `planning/04-database-schema.sql`.
- [ ] jOOQ generates classes for all 7 tables into `org.ledger.db.generated`.
- [ ] `GET /health` returns HTTP 200 with `{"status":"UP","database":"UP"}`.
- [ ] `WalkingSkeletonIT` passes against a real Testcontainers PostgreSQL — **not** an in-memory DB.
- [ ] The `ledger_entries` immutability trigger rejects an `UPDATE` and a `DELETE` (asserted by test).
- [ ] `docker compose up` brings up app + db; `/health` is reachable on `localhost:8080`.
- [ ] `make test` passes, and CI runs it on push.

## Test plan

`WalkingSkeletonIT` (Testcontainers `postgres:16`):

1. **Context loads** — the Spring context starts and a `DSLContext` is injected. Proves the jOOQ +
   Hikari + Spring wiring is real.
2. **Migrations applied** — `flyway_schema_history` contains `V1` and `V2`, and all 7 expected
   tables exist. Proves the schema is reproducible from scratch (NFR-13).
3. **Health endpoint** — `GET /health` returns 200 with `database: UP`.
4. **Ledger immutability trigger** — an `UPDATE` and a `DELETE` against `ledger_entries` each raise
   an exception. Proves invariant #2 is enforced by the database itself, not merely by convention.
   This is the one real correctness assertion in the skeleton, and it is worth having early.
