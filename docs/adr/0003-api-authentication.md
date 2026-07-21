# ADR 0003 — API authentication scheme

Date: 2026-07-21
Status: accepted
Deciders: Project owner
Relates to: SPEC 0002, NFR-22

## Context

SPEC 0002 puts the ledger on HTTP for the first time. Every `/api/v1/*` endpoint needs to reject
callers who do not hold a shared secret, per `planning/05-api-design.md` §2. Two decisions have to
be made before any controller code lands: what the credential *is*, and what enforces it.

## Options considered

### Option A — JWT
- **Pros:** Industry-standard, supports expiry and claims, extends naturally to a multi-tenant or
  multi-role future.
- **Cons:** This system is single-service, single-tenant, with no user identity model and no role
  hierarchy. JWT adds a clock-skew failure mode (`exp`/`nbf` validation), a signing-key management
  problem, and a token-refresh flow that nothing in this project needs yet. Complexity with no
  corresponding requirement.

### Option B — Static API key over a bare `javax.servlet.Filter`
- **Pros:** Simplest possible enforcement; no framework dependency beyond servlet.
- **Cons:** NFR-22 names Spring Security literally. Rolling a bespoke filter chain re-implements
  what `SecurityFilterChain` already gives us (ordered filter registration, an
  `AuthenticationEntryPoint` hook for 401 rendering, a `SecurityContext` other code can inspect),
  and diverges from what `planning/05-api-design.md` §2.2 describes (a `OncePerRequestFilter`
  ahead of the idempotency filter).

### Option C — Static API key, `spring-boot-starter-security` filter chain (chosen)
- **Pros:** Matches NFR-22 and §2.2 exactly. `ApiKeyAuthFilter extends OncePerRequestFilter`,
  registered before `UsernamePasswordAuthenticationFilter`, gives ordered, declarative route
  matching (`permitAll` vs `authenticated()`) instead of hand-written path checks. A custom
  `AuthenticationEntryPoint` lets 401s still return the standard `ErrorResponse` JSON instead of
  Spring Security's default empty body. Operationally simple: no clock-skew mode, no key expiry,
  matches the trust model of a known caller holding a pre-shared secret.
- **Cons:** No token expiry or per-caller scoping — every valid key can do everything. Acceptable
  because there is exactly one caller class (test harness / demo script / upstream service) and no
  role hierarchy to enforce.

## Decision

Static API key(s), loaded from `ledger.api-keys` (`API_KEYS` env, comma-separated; `API_KEY`
singular also honored) into an immutable `Set<String>` at startup. `ApiKeyAuthFilter extends
OncePerRequestFilter`, inserted before `UsernamePasswordAuthenticationFilter` in a
`SecurityFilterChain`:

- `Authorization` header absent or not prefixed `ApiKey ` → 401 `MISSING_CREDENTIALS`.
- Present but not in the configured key set → 401 `INVALID_CREDENTIALS`.
- Valid → an authenticated token is set on the `SecurityContext`.

Key comparison uses `MessageDigest.isEqual` over UTF-8 bytes, iterating the *entire* configured key
set on every comparison (not short-circuiting on first match) so that response timing does not leak
which key, or how many keys, are valid. The header value is never logged.

`CSRF` is disabled (no browser session, no cookies) and `SessionCreationPolicy.STATELESS` is set —
there is no session to fixate or protect. `/health`, `/actuator/**`, `/v3/api-docs/**`, and
`/swagger-ui/**` are `permitAll`; everything else is `authenticated()`, and `anyRequest()` ends in
`denyAll()` so a new endpoint added outside `/api/v1/**` fails closed rather than open.

A dev key ships in `application.yml` so `make run` and `docker compose up` work out of the box —
the same key `demo.sh` uses. It is a dev-only default, not a production credential.

## Consequences

**Positive:** Satisfies NFR-22 and §2.2 with off-the-shelf Spring Security machinery rather than a
parallel hand-rolled mechanism. `SecurityIT` can assert the whole chain (401 shapes, permitted
paths, valid-key success) through the same `SecurityFilterChain` that runs in production. Key
rotation is a config change, not a deploy.

**Negative / accepted costs:** No per-key scoping or expiry — a leaked key is valid until rotated
out of `API_KEYS` and the service restarted. JWT remains available as `AUTH_SCHEME=jwt` per
§2.1 for deployments that need it, but is not implemented in this spec.

**What would change our mind:** A multi-tenant deployment, or a requirement for per-caller
audit/scoping, would justify moving to JWT or a database-backed key table with per-key metadata.
