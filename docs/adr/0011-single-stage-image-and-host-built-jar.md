# ADR 0011 — Single-stage runtime image, host-built jar

Date: 2026-07-22
Status: accepted
Deciders: Project owner
Relates to: SPEC 0009 (DR-1), ADR 0002 (jOOQ codegen mechanism)

## Context

SPEC 0009's Dockerfile acceptance criterion is written against the usual "multi-stage build"
shape: a builder stage runs `mvn package` from source, a slim runtime stage copies only the
finished jar out of it. That shape gives a fully self-contained, reproducible image build — clone
the repo, `docker build`, done, no host toolchain required.

This project cannot do that. ADR 0002 established that jOOQ's generated code
(`org.ledger.db.generated`) is produced by `testcontainers-jooq-codegen-maven-plugin`, bound to
Maven's `generate-sources` phase: it starts a real `postgres:16-alpine` container, runs the actual
Flyway migrations against it, and introspects the live, migrated catalog. That means `mvn package`
needs a reachable Docker daemon *during the Maven build itself*, not just during test execution.

A Docker build stage (`RUN mvn package` inside a `docker build`) has no access to the host's
Docker socket unless Docker-in-Docker is set up — which this project does not do and CLAUDE.md's
jOOQ-codegen posture (regenerate from schema, never commit generated code) forecloses the
alternative of committing `org.ledger.db.generated` to sidestep the problem. Committing generated
code to make a build stage possible would violate "jOOQ generated code ... is not committed" for
the sole purpose of satisfying a Dockerfile shape the spec did not actually require by name — it
names an outcome (a hardened runtime image with the jar built somewhere), not a specific mechanism.

## Options considered

### Option A — Docker-in-Docker build stage
- **Pros:** Fully self-contained `docker build`; matches the spec's literal multi-stage wording.
- **Cons:** Requires either privileged containers or a Docker-in-Docker sidecar in every build
  environment (local dev, CI) that runs `docker build`. That is a strictly larger footprint than
  what this project already needs Docker for (Testcontainers, run as normal containers, no
  privilege escalation).

### Option B — Commit jOOQ generated code so a build stage never needs Docker
- **Pros:** Unlocks a conventional multi-stage build.
- **Cons:** Directly contradicts CLAUDE.md and ADR 0002's core guarantee — that generated code can
  never drift from the migrations that produced it, because it is regenerated from the real,
  migrated schema on every build. Committing it reintroduces exactly the drift risk ADR 0002 chose
  Testcontainers-based codegen to eliminate.

### Option C — Single-stage runtime image, jar built on the host beforehand (chosen)
- **Pros:** No new infrastructure dependency; the image build never touches Docker-in-Docker.
  `make jar` (`mvn -B clean package -DskipTests`) already runs on the host, where Testcontainers
  can already reach the host's Docker daemon directly, exactly like every other Maven invocation
  in this project. `make up` chains `jar` before `docker compose up -d --build`, so the common
  path is still one command.
- **Cons:** The image is not reproducible from a bare `docker build .` — a `target/` directory
  with a matching jar must already exist. CI must run `make jar` (or equivalent) before building
  the image, as an explicit prior step, not something the Dockerfile does for you.

## Decision

**Option C.** Keep the existing single-stage Dockerfile (already documented in its own header
comment) and harden it in place rather than restructuring it into a multi-stage build:

- Base image pinned by digest (`eclipse-temurin:21-jre-alpine@sha256:...`), not the floating tag.
- `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` so the JVM sizes its heap
  to whatever memory limit the container is actually given, and dies fast (and visibly) on OOM
  instead of degrading silently.
- Non-root `ledger` user, unchanged.
- `HEALTHCHECK` against `/health`, unchanged.
- `docker-compose.yml` adds `security_opt: [no-new-privileges:true]` and a `read_only` root
  filesystem with a `tmpfs` mount at `/tmp` for Tomcat's work directory — the app is stateless
  (Postgres is the only durable store), so nothing else needs a writable root.

The mitigation for lost build-time reproducibility: `make jar` is a single, explicit, always-run
prerequisite (`make up` already chains it), and CI is expected to run it before `docker build` in
the same way.

## Consequences

**Positive:**
- No Docker-in-Docker requirement anywhere in the build pipeline; the image build stays as simple
  as "copy a jar, run it."
- Consistent with ADR 0002: generated jOOQ code is never committed, never drifts from the schema.
- Base image is pinned and auditable; `JAVA_TOOL_OPTIONS` makes container-aware heap sizing and
  OOM behavior explicit rather than left to JVM defaults.

**Negative / accepted costs:**
- `docker build .` alone, from a fresh clone with no `target/`, fails — it is not a complete,
  self-contained build. This must be documented (README, this ADR) so it isn't rediscovered as a
  surprise.
- Build reproducibility now spans two steps (`make jar` then `docker build`/`docker compose up
  --build`) instead of one `docker build`. CI pipelines must encode this ordering explicitly.

**What would change our mind:**
- If this project ever adopts a codegen mechanism that does not need a live Postgres at build time
  (ADR 0002's Option A, jOOQ's DDL parser, revisited only if Docker becomes unavailable in some
  required build environment), a true multi-stage build becomes possible again and this ADR should
  be revisited.
