# SPEC 0002 — Transfer API

Status: verified
Depends on: 0001
Requirements: FR-7, FR-8, FR-9, FR-12, FR-13, NFR-22, DR-8

## Goal

Make the ledger reachable and demoable over HTTP. A thin end-to-end slice: curl a transfer, watch
balances move, confirm the total across both accounts is unchanged. This is the first point at
which the project is a *system* rather than a service class.

## In scope

- `POST /api/v1/accounts`, `GET /api/v1/accounts`, `GET /api/v1/accounts/{id}`,
  `GET /api/v1/accounts/{id}/balance`.
- `POST /api/v1/transfers`, `GET /api/v1/transfers/{id}`.
- Request validation (`@Valid`) — see `planning/05-api-design.md`.
- The `GlobalExceptionHandler` and the **full error-code catalogue** from
  `planning/05-api-design.md` §3.2.
- OpenAPI document served and rendering.
- Spring Security: API-key auth on all `/api/v1/*` endpoints (`Authorization: ApiKey <key>`).
- Cursor-based pagination for `GET /accounts` (`planning/05-api-design.md` §5).
- `demo.sh`.

## Out of scope

- **Idempotency semantics.** The `Idempotency-Key` header is *accepted and required* on mutating
  endpoints, but not yet enforced — a duplicate request will currently double-apply. SPEC 0003
  closes this. Requiring the header now means SPEC 0003 changes only behavior, not the contract.
- Advanced locking. (SPEC 0004)

## Design notes

The error-code catalogue in `planning/05-api-design.md` §3.2 is **stable and complete** — use it
verbatim. Clients switch on `errorCode`, so inventing new codes ad hoc is a breaking change.

Note the deliberate asymmetry in status codes: `422 INSUFFICIENT_FUNDS` (the request was well-formed
but the system state forbids it) versus `400 VALIDATION_ERROR` (the request itself is wrong). And
`availableBalance` in the insufficient-funds detail is `balance - minBalance` — the amount actually
spendable — not the raw balance.

API keys are compared with `MessageDigest.isEqual` (timing-safe) and are never logged.

## Acceptance criteria (the measurable "done")

- [x] `curl` a transfer end to end: balances move, and the total across both accounts is unchanged.
- [x] `GET /accounts/{id}/balance` reflects the transfer immediately after it returns 201.
- [x] `GET /transfers/{id}` returns the transfer with its status.
- [x] Every error in the §3.2 catalogue is reachable and returns its documented status + `errorCode`.
- [x] Unauthenticated request to any `/api/v1/*` endpoint → 401.
- [x] The OpenAPI document renders and matches the implemented surface.
- [x] `demo.sh` runs green (verified against `docker compose up postgres` + the app run locally --
      `docker compose up -d --build` cannot build the app image in this environment: the jOOQ
      codegen Maven plugin needs a Docker socket during `mvn package`, which a plain `docker build`
      does not provide. Pre-existing Dockerfile limitation, not introduced by this spec; see
      progress_report.md.

## Test plan

- `TransferApiIT` — full HTTP round trip through `TestRestTemplate` against Testcontainers.
- `ErrorModelIT` / `InternalErrorIT` / `GlobalExceptionHandlerUnitTest` — one test per error code in
  the catalogue; future-spec-only codes get a direct handler unit test.
- `SecurityIT` — 401 on missing/invalid key; 200 on valid; permitAll routes reachable without a key.
- `PaginationIT` — cursor stability across a concurrent insert (the reason we chose cursor over
  offset in the first place), limit clamping, empty/last-page shape, malformed cursor.
- `OpenApiIT` — served document lists the implemented `/api/v1` paths and the `ApiKeyAuth` scheme.
