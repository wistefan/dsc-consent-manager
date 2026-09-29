# =============================================================================
# Consent Manager — Multi-stage Docker Build
# =============================================================================
# Build stage:  Full JDK + Maven to compile and package the application.
# Runtime stage: Minimal JRE to run the packaged JAR.
# =============================================================================

# ---------------------------------------------------------------------------
# Stage 1: Build
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build

WORKDIR /app

# Copy Maven Wrapper and POM first for dependency caching
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Download dependencies (cached unless pom.xml changes)
RUN ./mvnw dependency:go-offline -B -q

# Copy the rest of the source code
COPY api/ api/
COPY src/ src/

# Build the application, skipping tests (tests run in CI, not in Docker build)
RUN ./mvnw package -DskipTests -B -q \
    && cp target/consent-manager-*.jar target/app.jar

# ---------------------------------------------------------------------------
# Stage 2: Runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre

# Application metadata
LABEL maintainer="Seamware <dev@seamware.com>" \
      description="Consent Manager — consent lifecycle management service" \
      org.opencontainers.image.source="https://git.seamware.com/seamware/dsc-consent-manager"

# Install wget for container health checks, then clean up
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*

# Create a non-root user for running the application
RUN groupadd --system appuser \
    && useradd --system --gid appuser --no-create-home appuser

WORKDIR /app

# Copy the packaged JAR from the build stage
COPY --from=build /app/target/app.jar app.jar

# Ensure the non-root user owns the application directory
RUN chown -R appuser:appuser /app

# Switch to non-root user
USER appuser

# Expose the application port and management port
EXPOSE 8080 9090

# Health check against the management endpoint (port 9090 by default).
# The server context path (API_PREFIX, default /v1) applies to all endpoints
# including management endpoints on the dedicated port.
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD wget -qO- http://localhost:9090/v1/health || exit 1

# JVM flags for container environments:
#   -XX:MaxRAMPercentage=75.0  — use up to 75% of container memory for the heap
#   -Djava.security.egd        — faster startup via non-blocking entropy source
# Note: -XX:+UseContainerSupport is enabled by default since JDK 10.
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
