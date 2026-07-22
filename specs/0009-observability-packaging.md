# SPEC 0009 — Observability, packaging & deploy

Status: implemented
Depends on: 0008
Requirements: FR-33, NFR-19, NFR-20, NFR-21, NFR-23, DR-1, DR-2, DR-8, DR-11, DR-12

## Goal

Make the system production-shaped and *presentable*. The engineering is done by this point; this
spec makes it legible to someone who has thirty seconds and a browser tab.

## In scope

- Structured JSON logging: transfers, saga transitions, reconciliation runs, errors.
- Micrometer/Prometheus metrics: transfer rate, optimistic conflict/retry rate, reconciliation
  status, saga completion rate, saga compensation rate. Scrapable at `/actuator/prometheus`.
- Hardened multi-stage Dockerfile; Compose (app + Postgres).
- **README** — opens with the headline result and the architecture diagram, then the results table,
  then links to the ADRs, then how to reproduce.
- `demo.sh` — double-submits a payment and shows it deduped, live.
- Optional: free-tier deploy for a live URL.

## Out of scope

- Multi-region.

## Design notes

The README's first screen is the entire recruiter-facing deliverable. Lead with the **measured
result**, not with "a payments ledger built with Spring Boot." The claim is the product; the stack
is an implementation detail.

`demo.sh` must show the *deduplication happening* — submit the same payment twice, print both
responses, show one transfer in the ledger and money conserved. Watching a double-charge *not*
happen is far more persuasive than reading a paragraph asserting that it cannot.

Never log raw monetary amounts or account identifiers at DEBUG in the production profile (NFR-23).

Commit `.claude/` itself. The guardrails, specs, and ADRs are visible evidence of the process, and
that is part of what is being demonstrated.

## Acceptance criteria (the measurable "done")

- [x] `docker compose up` + `make concurrency-test` reproduce the headline result on a clean machine.
- [x] `/actuator/prometheus` serves all the named metrics.
- [x] Logs are structured JSON and correlate by `requestId`.
- [x] README tells the whole story: headline, diagram, results table, ADRs, reproduction steps,
      and the SDD workflow.
- [x] `demo.sh` visibly demonstrates deduplication and conservation of money.
- [x] No monetary amounts or account IDs logged at DEBUG under the prod profile.
- [ ] (Optional, deferred) A live URL — DR-11 explicitly deferred per plan; out of scope for this
      pass.

## Test plan

- `MetricsIT` — every named metric is present and moves when it should.
- `LoggingIT` — output parses as JSON and carries `requestId`.
- Manual: clean-machine run of `docker compose up` + `make concurrency-test`, timed.
