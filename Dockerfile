# ---------------------------------------------------------------------------
# Runtime image only. The jar is built on the host beforehand (`make jar` /
# `mvn -B clean package -DskipTests`), NOT inside this build.
#
# Why: jOOQ code generation (generate-sources phase, see pom.xml and ADR 0002)
# spins up a real Postgres via Testcontainers, which needs a Docker daemon.
# A `docker build` build stage has no access to the host's Docker socket
# (no Docker-in-Docker here), so running `mvn package` inside the image build
# fails with "Could not find a valid Docker environment." Building the jar on
# the host — where Testcontainers can already reach Docker — and copying the
# finished artifact in sidesteps that entirely.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S ledger && adduser -S ledger -G ledger

WORKDIR /app

COPY target/payments-ledger-*.jar /app/ledger.jar

RUN chown -R ledger:ledger /app
USER ledger

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -qO- http://localhost:8080/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "/app/ledger.jar"]
