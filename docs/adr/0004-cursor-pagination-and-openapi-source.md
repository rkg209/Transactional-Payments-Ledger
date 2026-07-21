# ADR 0004 — Cursor pagination and the OpenAPI source of truth

Date: 2026-07-21
Status: accepted
Deciders: Project owner
Relates to: SPEC 0002, FR-13, NFR-22

## Context

Two independent decisions land in the same spec because both stem from the same planning-doc
conflict: `planning/05-openapi.yaml` (OpenAPI 3.0.3, offset pagination, no `paths:` section) is
stale against `planning/05-api-design.md` prose (cursor pagination, §5) and its own embedded §7
OpenAPI block (3.1.0, truncated mid-schema). Both planning documents are read-only; neither is
edited by this spec. Something has to be built, and it has to match §5 and §3.2 exactly since those
are cited by name as authoritative.

### Pagination

`GET /api/v1/accounts` is the only collection endpoint. §5.1 gives two concrete reasons to prefer
cursor over offset: offset pagination skips or duplicates rows under concurrent insert, and
`OFFSET k` forces the database to scan and discard `k` rows on every deep page.

### OpenAPI source

The service needs a served OpenAPI document (FR-13) that "matches the implemented surface" per the
SPEC 0002 acceptance criteria. `planning/05-openapi.yaml` cannot be that document as written: it
has no `paths:` section at all, so it describes zero endpoints.

## Options considered — pagination

### Option A — Offset (`LIMIT`/`OFFSET`), matching the stale yaml's `PageMetadata`
- **Pros:** Simpler client mental model (`page`, `size`); matches the (stale) yaml component schema.
- **Cons:** Directly contradicts the authoritative §5 prose. Makes the headline pagination test
  (page 1, insert a row that lands inside page 1's range, fetch page 2, assert no skip/duplicate)
  fail by construction — offset pagination *cannot* pass that test, which is exactly why §5.1 rejects
  it.

### Option B — Cursor, §5.4 envelope (chosen)
- **Pros:** Stable under concurrent insert; O(1) range scan via an indexed `(created_at, id) > (?,
  ?)` predicate regardless of page depth; matches the authoritative prose exactly, including the
  `{data, pagination:{limit,nextCursor,hasMore}}` envelope shape.
- **Cons:** Opaque cursor is a worse client ergonomic than a bare page number; requires a composite
  `(created_at, id)` index that does not exist yet (the PK is on `id` alone — §5.5's claim that the
  PK index already supports the keyset sort is wrong).

## Options considered — OpenAPI source

### Option A — Hand-maintain a corrected copy of the yaml
- **Pros:** No new dependency; full control over wording.
- **Cons:** Reintroduces exactly the failure mode that produced the stale file: a hand-maintained
  document drifts from the real controllers the first time someone changes a DTO field and forgets
  the yaml. "Matches the implemented surface" would then be a claim, not something enforceable.

### Option B — springdoc-generated document from the real controllers (chosen)
- **Pros:** `springdoc-openapi-starter-webmvc-ui` derives `/v3/api-docs` and Swagger UI from the
  actual `@RestController` classes and DTOs, so it cannot drift from the implemented surface by
  construction. `OpenApiIT` can assert this directly: fetch `/v3/api-docs`, list the paths, compare
  against the six implemented endpoints.
- **Cons:** New runtime dependency; needs an explicit `<version>` since it is not in the Spring Boot
  BOM (2.6.x tracks Boot 3.3.x).

## Decision

**Pagination:** cursor-based, per §5.4 exactly — `{data, pagination:{limit,nextCursor,hasMore}}`,
sort `created_at ASC, id ASC`, opaque base64url-encoded `{createdAt, id}` cursor. A new migration,
`V3__accounts_pagination_index.sql`, adds `CREATE INDEX idx_accounts_created_at_id ON accounts
(created_at, id)` — V1/V2 are immutable and are not touched. The repository query uses PostgreSQL
row-value comparison (`WHERE (created_at, id) > (?, ?)`), not the `OR`-expanded form, because the
row form is what lets the planner use the new index as a single range scan.

**OpenAPI:** `springdoc-openapi-starter-webmvc-ui`, generated from the real controllers/DTOs, served
at `/v3/api-docs` with Swagger UI at `/swagger-ui.html`. `planning/05-openapi.yaml` is superseded as
the API's source of truth; it remains in `planning/` as a historical, read-only artifact.

## Consequences

**Positive:** The pagination test that actually matters (stability under concurrent insert) is
possible to write and pass. The OpenAPI document cannot silently drift from the implemented
endpoints, because it is generated from them.

**Negative / accepted costs:** Clients cannot jump to an arbitrary page number — only forward
traversal from a cursor is supported, per §5.5 (no configurable sort in v1). The generated OpenAPI
document's prose (`info.description`, per-field descriptions) needs deliberate `@Operation`/
`@Schema` annotation to reach the quality of the hand-written §7 block; it is not free.

**What would change our mind:** A future requirement for arbitrary-offset jump-to-page navigation
would require a second, explicitly offset-based endpoint rather than reverting this one — cursor
pagination's concurrent-insert stability is not optional for `GET /accounts` once it is user-facing.
