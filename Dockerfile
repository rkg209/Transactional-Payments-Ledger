# ---------------------------------------------------------------------------
# Stage 1 — build
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /build

RUN apk add --no-cache maven

# Dependencies first, so a source-only change does not re-download the world.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---------------------------------------------------------------------------
# Stage 2 — runtime
#
# JRE, not JDK. Non-root. Nothing from the build stage but the jar.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S ledger && adduser -S ledger -G ledger

WORKDIR /app

COPY --from=build /build/target/payments-ledger-*.jar /app/ledger.jar

RUN chown -R ledger:ledger /app
USER ledger

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -qO- http://localhost:8080/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "/app/ledger.jar"]
