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
# Pinned by digest (the multi-arch index, not a single-platform manifest) rather than the
# floating `21-jre-alpine` tag, so a build a year from now can't silently pick up a different
# JRE patch/Alpine base. Re-resolve with `docker buildx imagetools inspect eclipse-temurin:21-jre-alpine`.
FROM eclipse-temurin:21-jre-alpine@sha256:3f08b13888f595cc49edabea7250ba69499ba25602b267da591720769400e08c AS runtime

RUN addgroup -S ledger && adduser -S ledger -G ledger

WORKDIR /app

COPY target/payments-ledger-*.jar /app/ledger.jar

RUN chown -R ledger:ledger /app
USER ledger

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -qO- http://localhost:8080/health | grep -q '"status":"UP"' || exit 1

# MaxRAMPercentage over -Xmx: lets the heap scale with whatever memory limit the container is
# actually given (compose, k8s) instead of a value baked into the image. ExitOnOutOfMemoryError
# turns an OOM into a fast, visible container restart instead of a wedged, half-alive JVM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "/app/ledger.jar"]
