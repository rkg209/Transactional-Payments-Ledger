# api-design.md — Transactional Payments Ledger

Version: 1.0 | Derived from: architecture.md v1.0, system-design.md v1.0, database-design.md v1.0 | Status: Authoritative

---

## Table of Contents

1. [API Overview](#1-api-overview)
2. [Authentication](#2-authentication)
3. [Error Handling](#3-error-handling)
4. [Idempotency](#4-idempotency)
5. [Pagination](#5-pagination)
6. [Versioning Policy](#6-versioning-policy)
7. [OpenAPI Specification](#7-openapi-specification)
8. [Design Notes and Rationale](#8-design-notes-and-rationale)

---

## 1. API Overview

The Transactional Payments Ledger exposes a single REST API over HTTP/1.1. All request and response bodies are JSON (`application/json`). The API is synchronous: every response reflects the committed database state at the time the response is sent. There are no webhooks, no streaming endpoints, and no long-polling endpoints (the idempotency polling loop is internal to the server and invisible to the client).

**Base URL:** `http://{host}:8080`

**API prefix:** `/api/v1`

All application endpoints are mounted under `/api/v1`. Infrastructure endpoints (`/actuator/*`) are mounted at the root and are not part of the versioned API surface.

**Endpoint inventory:**

| Method | Path | Description | Auth Required | Idempotency-Key Required |
|---|---|---|---|---|
| `POST` | `/api/v1/accounts` | Create account | Yes | Yes |
| `GET` | `/api/v1/accounts` | List accounts | Yes | No |
| `GET` | `/api/v1/accounts/{accountId}` | Get account | Yes | No |
| `GET` | `/api/v1/accounts/{accountId}/balance` | Get balance | Yes | No |
| `POST` | `/api/v1/transfers` | Execute transfer | Yes | Yes |
| `GET` | `/api/v1/transfers/{transferId}` | Get transfer | Yes | No |
| `POST` | `/api/v1/transfers/saga` | Execute saga transfer | Yes | Yes |
| `GET` | `/api/v1/sagas/{sagaId}` | Get saga | Yes | No |
| `GET` | `/api/v1/reconciliation/report` | Get latest reconciliation report | Yes | No |
| `POST` | `/api/v1/reconciliation/run` | Trigger reconciliation run | Yes | Yes |
| `GET` | `/actuator/health` | Health check | No | No |
| `GET` | `/actuator/prometheus` | Prometheus metrics | No | No |

---

## 2. Authentication

### 2.1 Scheme: API Key

All `/api/v1/*` endpoints require an API key passed in the `Authorization` header using the `ApiKey` scheme:

```
Authorization: ApiKey <key>
```

The key is a static secret configured at startup via the `API_KEY` environment variable. In production deployments, multiple keys may be configured via `API_KEYS` (comma-separated), allowing key rotation without downtime. The `SecurityConfig` component loads the valid key set at startup and holds it in memory as a `Set<String>`.

**Why API key rather than JWT for this system:**

The system is a single-service, single-tenant ledger. There is no user identity model, no role hierarchy, and no need for token expiry or refresh flows. API key authentication is operationally simpler, has no clock-skew failure mode, and is sufficient for the trust model: the caller is a known system (a test harness, a demo script, or an upstream service) that holds a pre-shared secret. JWT is available as a configuration option (`AUTH_SCHEME=jwt`) for deployments that require it, but API key is the default and the scheme documented here.

### 2.2 Security Filter Behavior

`SecurityFilter` (a Spring `OncePerRequestFilter`) runs before `IdempotencyFilter` on every request to `/api/v1/*`. Its behavior:

1. Extract the `Authorization` header.
2. If absent or not prefixed with `ApiKey `: respond `401 Unauthorized` with error code `MISSING_CREDENTIALS`.
3. Extract the key value after `ApiKey `.
4. If the key is not in the valid key set: respond `401 Unauthorized` with error code `INVALID_CREDENTIALS`.
5. If valid: set a `SecurityContext` and pass the request to the next filter.

Actuator endpoints (`/actuator/*`) bypass `SecurityFilter`. They are protected at the network level (not exposed outside the Docker network in production) rather than at the application level, consistent with the principle that operational endpoints should not require the same credentials as the business API.

### 2.3 Request and Response Examples

**Valid request:**
```http
POST /api/v1/transfers HTTP/1.1
Authorization: ApiKey secret-dev-key-001
Idempotency-Key: txn-2024-001
Content-Type: application/json

{ "fromAccountId": "...", "toAccountId": "...", "amount": 1000, "currency": "USD" }
```

**Missing header:**
```http
HTTP/1.1 401 Unauthorized
Content-Type: application/json

{
  "errorCode": "MISSING_CREDENTIALS",
  "message": "Authorization header is required.",
  "requestId": "req-7f3a1b",
  "timestamp": "2024-01-15T10:30:00Z"
}
```

**Invalid key:**
```http
HTTP/1.1 401 Unauthorized
Content-Type: application/json

{
  "errorCode": "INVALID_CREDENTIALS",
  "message": "The provided API key is not valid.",
  "requestId": "req-7f3a1b",
  "timestamp": "2024-01-15T10:30:00Z"
}
```

### 2.4 Security Considerations

- API keys are never logged. `SecurityFilter` explicitly excludes the `Authorization` header from structured log output.
- API keys are never returned in any response body or error message.
- The `request_fingerprint` stored in `idempotency_keys` is a hash of the request body only; it does not include the `Authorization` header, so key rotation does not invalidate stored idempotency records.
- Timing-safe comparison (`MessageDigest.isEqual`) is used when validating keys to prevent timing oracle attacks.

---

## 3. Error Handling

### 3.1 Error Response Schema

Every error response, regardless of HTTP status code, uses the same JSON structure:

```json
{
  "errorCode": "string",
  "message": "string",
  "requestId": "string",
  "timestamp": "string (ISO 8601)",
  "details": {}
}
```

| Field | Type | Always Present | Description |
|---|---|---|---|
| `errorCode` | `string` | Yes | Machine-readable error identifier. Stable across versions; clients may switch on this value. |
| `message` | `string` | Yes | Human-readable description of the error. Not stable; do not parse. |
| `requestId` | `string` | Yes | Unique identifier for this HTTP request, generated by the server. Correlates with server-side structured logs. |
| `timestamp` | `string` | Yes | ISO 8601 UTC timestamp of when the error was generated. |
| `details` | `object` | No | Additional structured context, present only for specific error codes. Schema varies by `errorCode`; documented per error code below. |

The `requestId` is generated by `SecurityFilter` at the start of every request and stored in the `MDC` (Mapped Diagnostic Context) for log correlation. It is returned in the response `X-Request-Id` header as well as in the error body.

### 3.2 Error Code Catalogue

The following table is the complete, stable set of error codes. HTTP status codes are fixed per error code and will not change across minor versions.

| HTTP Status | `errorCode` | Trigger | `details` fields |
|---|---|---|---|
| `400` | `VALIDATION_ERROR` | Bean validation failure (`@Valid`, `@NotNull`, etc.) | `violations`: array of `{ field, message }` |
| `400` | `MISSING_IDEMPOTENCY_KEY` | `POST`/`PUT`/`PATCH` request missing `Idempotency-Key` header | — |
| `400` | `MALFORMED_REQUEST` | Unparseable JSON body or wrong `Content-Type` | — |
| `401` | `MISSING_CREDENTIALS` | `Authorization` header absent | — |
| `401` | `INVALID_CREDENTIALS` | API key not recognized | — |
| `404` | `ACCOUNT_NOT_FOUND` | Account UUID does not exist | `accountId`: the UUID that was not found |
| `404` | `TRANSFER_NOT_FOUND` | Transfer UUID does not exist | `transferId`: the UUID that was not found |
| `404` | `SAGA_NOT_FOUND` | Saga UUID does not exist | `sagaId`: the UUID that was not found |
| `409` | `CONFLICT_RETRY_EXHAUSTED` | Optimistic lock retry limit reached | `attempts`: number of attempts made |
| `422` | `INSUFFICIENT_FUNDS` | Source account balance would fall below `minBalance` | `accountId`, `availableBalance`, `requiredAmount` |
| `422` | `CURRENCY_MISMATCH` | Source and destination accounts have different currencies | `fromCurrency`, `toCurrency` |
| `422` | `SAME_ACCOUNT_TRANSFER` | `fromAccountId` equals `toAccountId` | — |
| `422` | `IDEMPOTENCY_KEY_REUSE` | Same key submitted with a different request body | `key` |
| `422` | `SAGA_COMPENSATED` | Saga completed but was rolled back via compensation | `sagaId`, `compensatedSteps`: count |
| `422` | `INVALID_AMOUNT` | Amount is zero, negative, or exceeds system maximum | `amount` |
| `503` | `IDEMPOTENCY_TIMEOUT` | Concurrent duplicate request did not resolve within 1 second | `key` |
| `500` | `INTERNAL_ERROR` | Unhandled exception | — |

### 3.3 Validation Error Detail

For `VALIDATION_ERROR`, the `details` object contains a `violations` array:

```json
{
  "errorCode": "VALIDATION_ERROR",
  "message": "Request validation failed.",
  "requestId": "req-abc123",
  "timestamp": "2024-01-15T10:30:00Z",
  "details": {
    "violations": [
      {
        "field": "amount",
        "message": "must be greater than 0"
      },
      {
        "field": "toAccountId",
        "message": "must not be null"
      }
    ]
  }
}
```

### 3.4 Insufficient Funds Error Detail

```json
{
  "errorCode": "INSUFFICIENT_FUNDS",
  "message": "The source account does not have sufficient funds for this transfer.",
  "requestId": "req-abc123",
  "timestamp": "2024-01-15T10:30:00Z",
  "details": {
    "accountId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "availableBalance": 500,
    "requiredAmount": 1000,
    "currency": "USD"
  }
}
```

Note: `availableBalance` is `balance - minBalance`, not the raw `balance`. This is the amount the account can actually spend, which is the operationally meaningful figure.

### 3.5 GlobalExceptionHandler

`GlobalExceptionHandler` is a `@RestControllerAdvice` that intercepts all exceptions thrown from controllers and service layers and maps them to the standard error response structure. The mapping is:

```
InsufficientFundsException          → 422 INSUFFICIENT_FUNDS
AccountNotFoundException            → 404 ACCOUNT_NOT_FOUND
TransferNotFoundException           → 404 TRANSFER_NOT_FOUND
SagaNotFoundException               → 404 SAGA_NOT_FOUND
OptimisticLockException (exhausted) → 409 CONFLICT_RETRY_EXHAUSTED
IdempotencyFingerprintMismatch      → 422 IDEMPOTENCY_KEY_REUSE
SagaCompensatedException            → 422 SAGA_COMPENSATED
CurrencyMismatchException           → 422 CURRENCY_MISMATCH
SameAccountException                → 422 SAME_ACCOUNT_TRANSFER
InvalidAmountException              → 422 INVALID_AMOUNT
ConstraintViolationException        → 400 VALIDATION_ERROR
MethodArgumentNotValidException     → 400 VALIDATION_ERROR
HttpMessageNotReadableException     → 400 MALFORMED_REQUEST
MissingRequestHeaderException       → 400 MISSING_IDEMPOTENCY_KEY (if header is Idempotency-Key)
IdempotencyTimeoutException         → 503 IDEMPOTENCY_TIMEOUT
Throwable (catch-all)               → 500 INTERNAL_ERROR
```

For `INTERNAL_ERROR`, the exception stack trace is logged at `ERROR` level with the `requestId` in the MDC, but the stack trace is never included in the response body. The response body contains only the `errorCode`, a generic `message`, and the `requestId` so the client can report it to support.

### 3.6 HTTP Status Code Policy

- `2xx`: The request was accepted and the operation committed to the database.
- `400`: The request was malformed or failed validation. The client must fix the request before retrying. Retrying with the same body will produce the same error.
- `401`: Authentication failed. The client must provide valid credentials.
- `404`: The referenced resource does not exist.
- `409`: A transient conflict occurred. The client may retry with the same idempotency key.
- `422`: The request was well-formed but semantically invalid given the current system state. The client must change the request (or wait for state to change) before retrying.
- `500`: An unexpected server error occurred. The client should retry with exponential backoff. The idempotency key, if provided, will be in `FAILED` state and may be reused on retry.
- `503`: A transient server-side timeout. The client should retry after a short delay.

---

## 4. Idempotency

### 4.1 Overview

All state-mutating endpoints (`POST`, `PUT`, `PATCH`) require an `Idempotency-Key` header. The key is a client-generated string (UUID v4 recommended, but any string up to 255 characters is accepted) that uniquely identifies the logical operation. If the server receives two requests with the same key, it executes the operation exactly once and returns the same response to both requests.

This guarantee holds across:
- Network retries (client did not receive the response)
- Application-level retries (client received a `5xx` and is retrying)
- Concurrent duplicate submissions (two requests with the same key arrive simultaneously)

### 4.2 Client Contract

**Clients MUST:**
- Generate a new, unique key for each distinct logical operation.
- Reuse the same key when retrying a request that did not receive a successful response.
- Send the same request body when reusing a key. Sending a different body with the same key returns `422 IDEMPOTENCY_KEY_REUSE`.

**Clients MUST NOT:**
- Reuse a key for a different operation (e.g., reusing a transfer key for an account creation).
- Use predictable or sequential keys (UUIDs are recommended to prevent collision).

### 4.3 Key Lifecycle

```
Client sends request with key K
         │
         ▼
Key K not in DB ──► INSERT (status=IN_PROGRESS) ──► Execute operation ──► UPDATE (status=COMPLETED, snapshot=response)
                                                                      │
                                                                      └──► On failure: UPDATE (status=FAILED)

Key K in DB, status=COMPLETED ──► Return stored response (HTTP 200 + original body)
Key K in DB, status=FAILED    ──► Treat as new request (allow retry)
Key K in DB, status=IN_PROGRESS ──► Poll until COMPLETED or FAILED (max 1 second)
Key K in DB, different fingerprint ──► 422 IDEMPOTENCY_KEY_REUSE
```

### 4.4 Response Replay Behavior

When a completed key is replayed:
- The HTTP status code returned is `200 OK`, regardless of the original response status code (which may have been `201 Created`).
- The response body is identical to the original response body, byte-for-byte.
- The `X-Idempotent-Replayed: true` header is added to indicate this is a replay.
- The `X-Request-Id` header contains a new request ID for the replay request, not the original.

Rationale for returning `200` on replay rather than the original status code: the original `201 Created` implies a resource was just created. On replay, the resource already exists. `200 OK` is semantically accurate for "here is the resource you previously created." Clients that need to distinguish creation from replay should check the `X-Idempotent-Replayed` header.

### 4.5 Key Expiry

Idempotency keys do not expire in this version. The `idempotency_keys` table grows monotonically. A future migration may add a `expires_at` column and a scheduled cleanup job, but this is out of scope for v1. Operators may manually purge old `COMPLETED` and `FAILED` records without affecting system correctness, as long as they do not purge `IN_PROGRESS` records.

---

## 5. Pagination

### 5.1 Strategy: Cursor-Based Pagination

The `GET /api/v1/accounts` endpoint (the only collection endpoint in v1) uses cursor-based pagination rather than offset-based pagination.

**Rationale for cursor over offset:**

Offset pagination (`LIMIT n OFFSET k`) has two problems for this system:
1. **Correctness under concurrent writes:** If a new account is inserted between page 1 and page 2 requests, offset pagination will skip or duplicate an account. Cursor pagination is stable: the cursor encodes a position in the ordered result set that is unaffected by concurrent inserts.
2. **Performance at scale:** `OFFSET k` requires the database to scan and discard `k` rows. Cursor pagination uses an indexed range scan (`WHERE created_at < cursor_value`) that is O(1) regardless of page depth.

### 5.2 Cursor Encoding

The cursor is an opaque, base64url-encoded string. Internally it encodes the `created_at` timestamp and `id` of the last record on the previous page, forming a composite cursor that is stable even when multiple records share the same `created_at` value.

Internal cursor structure (not part of the public API contract; may change without notice):
```json
{ "createdAt": "2024-01-15T10:30:00.000000Z", "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6" }
```

Base64url-encoded: `eyJjcmVhdGVkQXQiOiIyMDI0LTAxLTE1VDEwOjMwOjAwLjAwMDAwMFoiLCJpZCI6IjNmYTg1ZjY0LTU3MTctNDU2Mi1iM2ZjLTJjOTYzZjY2YWZhNiJ9`

Clients treat the cursor as an opaque string. Constructing or parsing cursors is not supported and the internal format is not guaranteed.

### 5.3 Request Parameters

| Parameter | Type | Default | Max | Description |
|---|---|---|---|---|
| `limit` | `integer` | `20` | `100` | Number of records to return per page. |
| `cursor` | `string` | — | — | Cursor from the previous page's `nextCursor` field. Absent on the first request. |

### 5.4 Response Structure

All paginated responses wrap the data array in a standard envelope:

```json
{
  "data": [ ... ],
  "pagination": {
    "limit": 20,
    "nextCursor": "eyJjcmVhdGVkQXQi...",
    "hasMore": true
  }
}
```

| Field | Type | Description |
|---|---|---|
| `data` | `array` | The page of results. |
| `pagination.limit` | `integer` | The limit that was applied. |
| `pagination.nextCursor` | `string` \| `null` | Cursor to pass as `cursor` on the next request. `null` when `hasMore` is `false`. |
| `pagination.hasMore` | `boolean` | `true` if there are more records beyond this page. |

### 5.5 Sort Order

The default and only supported sort order for `GET /api/v1/accounts` is `created_at ASC, id ASC`. This order is stable (no two rows have the same `(created_at, id)` pair because `id` is a UUID primary key) and is supported by the primary key index without an additional sort index.

Configurable sort order is not supported in v1. Adding `sort` and `order` query parameters is a non-breaking addition reserved for a future minor version.

### 5.6 Empty and Single-Page Results

When the result set is empty:
```json
{
  "data": [],
  "pagination": {
    "limit": 20,
    "nextCursor": null,
    "hasMore": false
  }
}
```

When all results fit on one page, `hasMore` is `false` and `nextCursor` is `null`. Clients must check `hasMore` rather than checking whether `data` is empty, because a page may be non-empty and still be the last page.

---

## 6. Versioning Policy

### 6.1 URL Path Versioning

The API version is encoded in the URL path as `/api/v{N}`. The current version is `v1`. This is the only versioning mechanism; there are no `Accept` header versions, no query parameter versions, and no date-based versions.

**Rationale for path versioning over header versioning:**

Path versioning is visible in logs, browser history, and curl commands without special tooling. It is unambiguous: the version is part of the resource identifier, not a negotiation hint. For a system where the primary clients are automated (test harnesses, upstream services), operational clarity outweighs the theoretical purity of content negotiation.

### 6.2 Compatibility Policy

**Within a major version (v1):**

- **Non-breaking changes** are made without notice and do not require a version increment:
  - Adding new optional fields to response bodies.
  - Adding new optional query parameters.
  - Adding new endpoints.
  - Adding new values to the `errorCode` catalogue (clients must handle unknown error codes gracefully by treating them as `INTERNAL_ERROR`).
  - Relaxing validation constraints (accepting a wider range of inputs).

- **Breaking changes** require a new major version (`v2`):
  - Removing or renaming fields in request or response bodies.
  - Changing the type of an existing field.
  - Removing endpoints.
  - Changing the HTTP method of an endpoint.
  - Tightening validation constraints (rejecting inputs that were previously accepted).
  - Changing the semantics of an existing field or endpoint.
  - Changing fixed HTTP status codes for existing error codes.

### 6.3 Deprecation Process

When a breaking change is required:

1. The new version (`v2`) is deployed alongside `v1`. Both versions are served by the same process.
2. A `Deprecation` response header is added to all `v1` responses: `Deprecation: true`.
3. A `Sunset` response header is added indicating the planned removal date: `Sunset: Sat, 01 Jan 2026 00:00:00 GMT`.
4. The deprecation is announced in the changelog and communicated to all known API consumers.
5. After the sunset date, `v1` endpoints return `410 Gone` with error code `API_VERSION_DEPRECATED`.

### 6.4 Current Version Status

| Version | Status | Sunset Date |
|---|---|---|
| `v1` | **Current** | Not scheduled |

### 6.5 Version Discovery

Clients can discover the available API versions and their status via:

```http
GET /api/versions HTTP/1.1

HTTP/1.1 200 OK
Content-Type: application/json

{
  "versions": [
    {
      "version": "v1",
      "status": "current",
      "baseUrl": "/api/v1",
      "sunsetDate": null
    }
  ]
}
```

This endpoint is unauthenticated and is not subject to the idempotency requirement.

---

## 7. OpenAPI Specification

```yaml
openapi: "3.1.0"

info:
  title: Transactional Payments Ledger API
  version: "1.0.0"
  description: |
    A double-entry bookkeeping ledger API. Every transfer produces a balanced
    set of immutable ledger entries that sum to zero. All account balances are
    derived from that append-only entry log.

    ## Authentication
    All `/api/v1/*` endpoints require an API key in the `Authorization` header:
    ```
    Authorization: ApiKey <your-key>
    ```

    ## Idempotency
    All state-mutating endpoints (`POST`, `PUT`, `PATCH`) require an
    `Idempotency-Key` header. Generate a unique key (UUID v4 recommended) for
    each distinct logical operation. Reuse the same key when retrying.

    ## Amounts
    All monetary amounts are in **minor units** (e.g., cents for USD).
    An `amount` of `1000` with `currency` `USD` represents $10.00.

    ## Error Handling
    All errors use a consistent JSON structure with a stable `errorCode` field.
    Clients should switch on `errorCode`, not on `message`.
  contact:
    name: Ledger API Support
  license:
    name: MIT

servers:
  - url: http://localhost:8080
    description: Local development

tags:
  - name: Accounts
    description: Account lifecycle and balance management
  - name: Transfers
    description: Atomic value movement between accounts
  - name: Sagas
    description: Multi-step transfer orchestration
  - name: Reconciliation
    description: Invariant verification and drift reporting
  - name: Infrastructure
    description: Health and observability endpoints

# ─────────────────────────────────────────────────────────────────────────────
# Security Schemes
# ─────────────────────────────────────────────────────────────────────────────

components:
  securitySchemes:
    ApiKeyAuth:
      type: apiKey
      in: header
      name: Authorization
      description: |
        Pass your API key as `ApiKey <key>`.
        Example: `Authorization: ApiKey secret-dev-key-001`

  # ───────────────────────────────────────────────────────────────────────────
  # Shared Parameters
  # ───────────────────────────────────────────────────────────────────────────

  parameters:
    IdempotencyKey:
      name: Idempotency-Key
      in: header
      required: true
      schema:
        type: string
        minLength: 1
        maxLength: 255
      description: |
        Client-generated unique key for this logical operation.
        UUID v4 format is recommended. Reuse this key when retrying
        a request that did not receive a successful response.
      example: "550e8400-e29b-41d4-a716-446655440000"

    AccountId:
      name: accountId
      in: path
      required: true
      schema:
        type: string
        format: uuid
      description: The UUID of the account.
      example: "3fa85f64-5717-4562-b3fc-2c963f66afa6"

    TransferId:
      name: transferId
      in: path
      required: true
      schema:
        type: string
        format: uuid
      description: The UUID of the transfer.
      example: "7c9e6679-7425-40de-944b-e07fc1f90ae7"

    SagaId:
      name: sagaId
      in: path
      required: true
      schema:
        type: string
        format: uuid
      description: The UUID of the saga.
      example: "a1b2c3d4-e5f6-7890-abcd-ef1234567890"

    LimitParam:
      name: limit
      in: query
      required: false
      schema:
        type: integer
        minimum: 1
        maximum: 100
        default: 20
      description: Maximum number of records to return per page.

    CursorParam:
      name: cursor
      in: query
      required: false
      schema:
        type: string
      description: |
        Opaque pagination cursor from the previous page's `nextCursor` field.
        Omit on the first request.

  # ───────────────────────────────────────────────────────────────────────────
  # Shared Headers
  # ───────────────────────────────────────────────────────────────────────────

  headers:
    X-Request-Id:
      description: Server-generated unique identifier for this request. Use for log correlation.
      schema:
        type: string
      example: "req-7f3a1b9c"

    X-Idempotent-Replayed:
      description: Present and set to `true` when the response is a replay of a previously completed request.
      schema:
        type: boolean
      example: true

    Deprecation:
      description: Present when the API version is deprecated.
      schema:
        type: boolean

    Sunset:
      description: The date after which the deprecated API version will be removed.
      schema:
        type: string
        format: date-time

  # ───────────────────────────────────────────────────────────────────────────
  # Schemas
  # ───────────────────────────────────────────────────────────────────────────

  schemas:

    # ── Error ────────────────────────────────────────────────────────────────

    ErrorResponse:
      type: object
      required:
        - errorCode
        - message
        - requestId
        - timestamp
      properties:
        errorCode:
          type: string
          description: |
            Machine-readable error identifier. Stable across minor versions.
            Clients should switch on this value.
          example: "INSUFFICIENT_FUNDS"
        message:
          type: string
          description: Human-readable description. Not stable; do not parse.
          example: "The source account does not have sufficient funds for this transfer."
        requestId:
          type: string
          description: Server-generated request identifier for log correlation.
          example: "req-7f3a1b9c"
        timestamp:
          type: string
          format: date-time
          description: ISO 8601 UTC timestamp of when the error was generated.
          example: "2024-01-15T10:30:00Z"
        details:
          type: object
          description: Additional structured context. Schema varies by errorCode.
          additionalProperties: true
          nullable: true

    # ── Pagination ───────────────────────────────────────────────────────────

    PaginationMeta:
      type: object
      required:
        - limit
        - hasMore
      properties:
        limit:
          type: integer
          description: The limit that was applied to this page.
          example: 20
        nextCursor:
          type: string
          nullable: true
          description: Opaque cursor to pass as `cursor` on the next request. Null when `hasMore` is false.
          example: "eyJjcmVhdGVkQXQiOiIyMDI0LTAxLTE1VDEwOjMwOjAwLjAwMDAwMFoi