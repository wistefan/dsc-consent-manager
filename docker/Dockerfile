# =============================================================================
# Consent Manager — Multi-stage Docker Build
# =============================================================================
# Build stage:   Full JDK + Maven to compile and package the application.
# Runtime stage: Alpine JRE running the JAR as a numeric non-root user.
#
# Runtime contract:
#   - Runs as UID:GID ${APP_UID}:${APP_GID} (10001), so Kubernetes `runAsNonRoot`
#     can verify it.
#   - Works on a read-only root filesystem. Only /tmp (java.io.tmpdir) must be
#     writable, e.g. `--read-only --tmpfs /tmp` or an emptyDir in Kubernetes.
#   - The heap is sized from the container memory limit (MaxRAMPercentage); no -Xmx.
#   - The ENTRYPOINT is exec-form with no shell, so extra JVM flags go in
#     JAVA_TOOL_OPTIONS, which the JVM reads itself.
#   - Signing keys and other secrets are mounted at runtime, never baked in.
# =============================================================================

# ---------------------------------------------------------------------------
# Stage 1: Build
# ---------------------------------------------------------------------------
# Native to the builder: the JAR is architecture-independent, so a multi-arch build
# emulates only the runtime stage below, never the Maven build.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build

WORKDIR /app

# Copy Maven Wrapper and POM first for dependency caching
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Download dependencies (cached unless pom.xml changes)
RUN ./mvnw dependency:go-offline -B -q

# Copy the rest of the source code (src/test is excluded by .dockerignore)
COPY api/ api/
COPY src/ src/

# Build the application, skipping tests (tests run in CI, not in Docker build)
RUN ./mvnw package -DskipTests -B -q \
    && cp target/consent-manager-*.jar target/app.jar

# ---------------------------------------------------------------------------
# Stage 2: Runtime
# ---------------------------------------------------------------------------
# Alpine ships busybox wget, so the HEALTHCHECK needs no extra package layer.
FROM eclipse-temurin:25-jre-alpine

ARG APP_UID=10001
ARG APP_GID=10001

# Application metadata
LABEL maintainer="Seamware <dev@seamware.com>" \
      description="Consent Manager — consent lifecycle management service" \
      org.opencontainers.image.source="https://git.seamware.com/seamware/dsc-consent-manager"

# Non-root user with a fixed numeric identity and no home directory or login shell
RUN addgroup -S -g "${APP_GID}" appuser \
    && adduser -S -D -H -u "${APP_UID}" -G appuser -s /sbin/nologin appuser

WORKDIR /app

# Root-owned and read-only: the app user can run the JAR but never modify it
COPY --from=build --chown=root:root --chmod=0444 /app/target/app.jar app.jar

USER ${APP_UID}:${APP_GID}

# Expose the application port and management port
EXPOSE 8080 9090

# Liveness check against the management port, at its origin root. Liveness, not
# the aggregate /health, so an outage elsewhere never marks this container unhealthy.
# Uses MANAGEMENT_PORT (default 9090) so it follows a runtime override.
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD wget -qO- http://localhost:${MANAGEMENT_PORT:-9090}/health/liveness || exit 1

# JVM flags for container environments:
#   -XX:MaxRAMPercentage=75.0     — heap up to 75% of the container memory limit
#   -XX:+UseG1GC                  — pin G1; the JVM picks Serial on small containers
#   -XX:+ExitOnOutOfMemoryError   — exit so the orchestrator restarts a broken JVM
#   -Djava.io.tmpdir=/tmp         — the only writable path on a read-only root
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:+UseG1GC", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-Djava.io.tmpdir=/tmp", \
    "-jar", "app.jar"]
