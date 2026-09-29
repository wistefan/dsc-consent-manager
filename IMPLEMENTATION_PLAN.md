# Implementation Plan: Consent Manager - Project Setup and Scaffolding

## Overview
Set up the foundational project structure for the reimplemented Consent Manager on Java 25 + Micronaut 5 + PostgreSQL 16 with Maven. This creates a running, health-checkable, database-connected service with no domain logic. The work is split into 6 sequential steps, each producing a self-contained, mergeable PR to the work branch.

## Steps

### Step 1: Maven Project Initialization and Core Dependencies

Initialize the Maven project with all required dependencies, the Maven Wrapper, and the application entry point.

**Files to create:**
- `pom.xml` — Full Maven POM inheriting from `io.micronaut.platform:micronaut-parent` (Micronaut 5.x BOM). Includes:
  - All compile dependencies: `micronaut-http-server-netty`, `micronaut-data-jdbc`, `micronaut-jdbc-hikari`, `postgresql` JDBC driver, `micronaut-flyway`, `micronaut-validation`, `micronaut-security-jwt`, `micronaut-http-client`, `micronaut-cache-caffeine`, `micronaut-management`, `micronaut-micrometer-registry-prometheus`, `micronaut-email-javamail`, `logback-classic`, `logstash-logback-encoder`, `micronaut-serde-jackson`
  - All test dependencies: `micronaut-test-junit5`, `junit-jupiter`, `junit-jupiter-params`, `assertj-core`, `testcontainers`, `testcontainers-postgresql`, `testcontainers-junit-jupiter`, Keycloak testcontainers module, `wiremock`
  - Maven plugins: `micronaut-maven-plugin`, `maven-compiler-plugin` (Java 25, annotation processor paths for Micronaut inject, validation, data, serde), `maven-surefire-plugin`, `maven-failsafe-plugin`
  - Note: `jacoco-maven-plugin` is fully configured in Step 5 and `spotless-maven-plugin` is fully configured in Step 4. Do NOT add them in this step.
  - Properties section with pinned versions for all non-BOM-managed dependencies
  - Java 25 source/target/release configuration
  - Annotation processors: `micronaut-inject-java`, `micronaut-validation-processor`, `micronaut-data-processor`, `micronaut-serde-processor` — declared in `annotationProcessorPaths`, NOT as compile dependencies
  - **Do NOT** include `micronaut-openapi` annotation processor
- `.mvn/wrapper/maven-wrapper.properties` — Maven Wrapper properties
- `mvnw` and `mvnw.cmd` — Maven Wrapper scripts (executable)
- `.gitignore` — Ignore `target/`, IDE files, `.env`, OS files, generated sources
- `src/main/java/com/seamware/consentmanager/Application.java` — Micronaut entry point with `Micronaut.run(Application.class, args)`

**Empty directories to create (with `.gitkeep`):**
- `src/main/java/com/seamware/consentmanager/config/`
- `src/main/java/com/seamware/consentmanager/domain/`
- `src/main/java/com/seamware/consentmanager/repository/`
- `src/main/java/com/seamware/consentmanager/service/`
- `src/main/java/com/seamware/consentmanager/api/dto/`
- `src/main/java/com/seamware/consentmanager/error/`
- `src/main/resources/db/migration/`
- `src/test/java/com/seamware/consentmanager/support/`

**Acceptance criteria:**
- `./mvnw compile` succeeds (dependencies resolve, annotation processors run)
- No Gradle files, Kotlin plugins, or Kotlin dependencies exist
- Maven Wrapper is committed and functional
- `.gitignore` excludes `target/`, `.env`, IDE files, and generated sources
- `Application.java` is a valid Micronaut entry point

---

### Step 2: Application Configuration and Environment Setup

Create the application configuration files, the `@ConfigurationProperties` root class, and the environment variable documentation.

**Files to create:**
- `src/main/resources/application.yml` — Base configuration:
  - `micronaut.application.name: consent-manager`
  - `micronaut.server.port: ${PORT:8080}`
  - `micronaut.server.context-path: ${API_PREFIX:/v1}`
  - Datasource configuration bound to `DB_URL`, `DB_USER`, `DB_PASSWORD` with HikariCP pool settings
  - `flyway.datasources.default.enabled: true`
  - `micronaut.metrics.enabled: true` with Prometheus registry enabled
  - `endpoints.health.details-visible: AUTHENTICATED` (restrictive by default; relaxed to `ANONYMOUS` in dev profile only)
  - Health and metrics endpoints served outside the API context path (using `endpoints.all.path` or similar)
  - Security configuration placeholder for JWT/JWKS (detailed in TICKET-003)
- `src/main/resources/application-dev.yml` — Dev overrides:
  - Relaxed security settings for local development
  - `endpoints.health.details-visible: ANONYMOUS`
  - Default datasource URL pointing to localhost PostgreSQL
- `src/main/java/com/seamware/consentmanager/config/ConsentManagerConfiguration.java` — `@ConfigurationProperties("consent-manager")` class with typed fields for:
  - `url` (bound to `CONSENT_MANAGER_URL`) — public base URL of this service
  - `contractServiceUrl` (bound to `CONTRACT_SERVICE_URL`) — Contract Service base URL
- `.env.sample` — Documents every environment variable:
  - `PORT`, `API_PREFIX`, `DB_URL`, `DB_USER`, `DB_PASSWORD`, `CONSENT_MANAGER_URL`, `CONTRACT_SERVICE_URL`, `IDP_*` placeholders, `CONSENT_SIGNING_KEY_PATH`, `SMTP_*` placeholders, `MICRONAUT_ENVIRONMENTS`
  - Each variable with a comment explaining its purpose, whether it is required, and its default value

**Acceptance criteria:**
- `application.yml` correctly binds all specified environment variables with defaults
- `ConsentManagerConfiguration` compiles and is a valid Micronaut configuration bean
- `.env.sample` documents all environment variables from the ticket's table
- Health and metrics endpoints are configured outside the API context path

---

### Step 3: API-First Setup — Stub OpenAPI Spec, Code Generation, and Error Model

Set up the API-first code generation pipeline and the RFC 7807 error model.

**Files to create:**
- `api/openapi.yaml` — Minimal OpenAPI 3.0 specification:
  - Info block with title "Consent Manager API", version "0.1.0"
  - A single `/health` path (or minimal placeholder) so the generator produces valid output
  - Schema for the RFC 7807 `ProblemDetail` error response
  - This is a stub; the full spec is authored in TICKET-015

- `pom.xml` updates — Add the `openapi-generator-maven-plugin` execution:
  - `generatorName: java-micronaut-server`
  - `inputSpec: ${project.basedir}/api/openapi.yaml`
  - `output: ${project.build.directory}/generated-sources/openapi`
  - `apiPackage: com.seamware.consentmanager.api.generated`
  - `modelPackage: com.seamware.consentmanager.api.generated.model`
  - `configOptions`: `useBeanValidation=true`, `serializationLibrary=micronaut_serde_jackson`, `generateControllerFromExamples=false`
  - Pinned generator version via `${openapi.generator.version}` property

- `src/main/java/com/seamware/consentmanager/error/ApiError.java` — RFC 7807 Problem Details representation:
  - Fields: `type` (URI), `title`, `status` (int), `detail`, `instance` (URI)
  - Implements `@Serdeable` for Micronaut serialization
  - Builder or factory methods for constructing from exceptions

- `src/main/java/com/seamware/consentmanager/error/BadRequestException.java` — Extends `RuntimeException`, HTTP 400
- `src/main/java/com/seamware/consentmanager/error/NotFoundException.java` — Extends `RuntimeException`, HTTP 404
- `src/main/java/com/seamware/consentmanager/error/ForbiddenException.java` — Extends `RuntimeException`, HTTP 403
- `src/main/java/com/seamware/consentmanager/error/UpstreamServiceException.java` — Extends `RuntimeException`, HTTP 502

- `src/main/java/com/seamware/consentmanager/error/GlobalExceptionHandler.java` — Stub exception handler:
  - Annotated with `@Singleton` and implements `ExceptionHandler<Exception, HttpResponse<ApiError>>`
  - Returns a generic 500 response with an `ApiError` body
  - Full implementation (per-exception-type handling, constraint violation mapping, production detail suppression) deferred to TICKET-015

**Acceptance criteria:**
- `./mvnw generate-sources` produces Java interfaces and models under `target/generated-sources/openapi/`
- Generated sources are on the compile classpath (verified by `./mvnw compile`)
- Generated sources are NOT committed (`.gitignore` covers `target/`)
- All four exception types compile and have appropriate constructors
- `GlobalExceptionHandler` is a valid Micronaut exception handler bean
- `ApiError` follows RFC 7807 structure

---

### Step 4: Logging, Health, Metrics, and Formatting

Configure structured JSON logging, management endpoints, and source formatting.

**Files to create/update:**
- `src/main/resources/logback.xml` — Logging configuration:
  - Single console appender writing structured JSON to stdout using `logstash-logback-encoder`
  - No file appenders, no rotation (container platform responsibility)
  - MDC-based correlation ID field (`correlationId` or `traceId`) included in every log line
  - Reasonable log levels: `INFO` for application, `WARN` for framework internals

- `src/main/java/com/seamware/consentmanager/config/CorrelationIdFilter.java` — HTTP server filter:
  - Reads `X-Correlation-ID` header (or generates a UUID if absent)
  - Sets the value into MDC for the duration of the request
  - Clears MDC after the request completes
  - Propagates the correlation ID in the response header

- `src/main/resources/application.yml` updates (if needed):
  - Ensure management endpoints (`/health`, `/health/readiness`, `/metrics`, `/prometheus`) are accessible
  - Configure health indicators to include datasource health
  - Ensure health and readiness endpoints work outside the API context path

- Spotless configuration in `pom.xml`:
  - Add `spotless-maven-plugin` (not added in Step 1 — this step owns the full configuration) with a Java formatter (e.g., Google Java Format or Palantir Java Format)
  - Bind `spotless:check` to the `verify` phase so CI fails on formatting violations
  - Ensure generated sources under `target/` are excluded from formatting checks

- Swagger UI configuration:
  - Enable `micronaut-openapi` Swagger UI serving (note: this is different from the annotation processor — serving the authored spec is allowed)
  - Configure `swagger-ui` to serve the hand-authored `api/openapi.yaml` at `/swagger-ui`
  - Alternatively, serve the spec via a simple static resource mapping if the Micronaut OpenAPI serving module is not suitable

**Acceptance criteria:**
- Logs are emitted as single-line JSON to stdout with a `correlationId` field
- `GET /health` returns `200 OK` with `UP` status including datasource health indicator
- `GET /health/readiness` reports `DOWN` when the database is unreachable
- `GET /metrics` exposes Prometheus-format metrics
- `GET /swagger-ui` serves the authored specification
- `./mvnw spotless:check` passes (or `spotless:apply` formats correctly)
- Spotless does not attempt to format generated sources

---

### Step 5: Test Infrastructure — Testcontainers and Smoke Test

Set up the testing infrastructure with Testcontainers for PostgreSQL and write a smoke test proving the service starts and connects to a real database.

**Files to create:**
- `src/test/java/com/seamware/consentmanager/support/PostgresTestResource.java` — Testcontainers PostgreSQL fixture:
  - Implements `TestResourceProvider` (Micronaut's test resource interface) or uses `@Testcontainers` JUnit 5 extension
  - Starts a `PostgreSQLContainer` with PostgreSQL 16 image
  - Provides datasource properties (`datasources.default.url`, `datasources.default.username`, `datasources.default.password`) to the test application context
  - Shared container instance across test classes for performance

- `src/test/java/com/seamware/consentmanager/ApplicationSmokeTest.java` — Integration test (or `ApplicationSmokeIT.java` if using Failsafe):
  - Uses `@MicronautTest` with the PostgreSQL test resource
  - Verifies the application context starts successfully
  - Verifies `GET /health` returns 200 with `UP` status
  - Verifies the datasource health indicator reports `UP`
  - Verifies Flyway has run (even with no migrations, the schema history table exists)

- `src/test/resources/application-test.yml` — Test-specific configuration overrides:
  - Disable security for smoke tests (or configure test credentials)
  - Any test-specific property overrides

- JaCoCo configuration in `pom.xml`:
  - Add `jacoco-maven-plugin` (not added in Step 1 — this step owns the full configuration)
  - Set a minimum coverage threshold (e.g., 50% for scaffolding, to be raised as domain logic is added)
  - Configure report generation bound to the `verify` phase

**Acceptance criteria:**
- `./mvnw test` runs unit tests (Surefire)
- `./mvnw verify` runs integration tests (Failsafe) with a real PostgreSQL via Testcontainers
- The smoke test proves the application starts, connects to PostgreSQL, and health check returns UP
- Flyway executes on startup (schema history table created even with no migrations)
- JaCoCo generates a coverage report
- Testcontainers fixture is reusable by future test classes

---

### Step 6: Docker, Compose, and Final Verification

Create the Docker multi-stage build, Docker Compose configuration for local development, and run final verification of all acceptance criteria.

**Files to create:**
- `docker/Dockerfile` — Multi-stage build:
  - **Build stage**: `eclipse-temurin:25-jdk` (or Maven image) — copies source, runs `./mvnw package -DskipTests`
  - **Runtime stage**: `eclipse-temurin:25-jre-alpine` (or distroless) — copies only the packaged JAR
  - Proper `ENTRYPOINT` with JVM flags for container environments (`-XX:MaxRAMPercentage=75.0`). Note: `-XX:+UseContainerSupport` is enabled by default since JDK 10 and should be omitted.
  - Non-root user for the runtime stage
  - Health check instruction using the `/health` endpoint

- `compose.yaml` — Docker Compose for local development:
  - `app` service: builds from `docker/Dockerfile`, exposes port 8080, depends on `db`, loads `.env` file
  - `db` service: `postgres:16` image with volume for data persistence, exposes port 5432, sets `POSTGRES_DB=consent_manager`, `POSTGRES_USER`, `POSTGRES_PASSWORD`
  - No replica set, no initialization scripts
  - Network for inter-service communication

- `.env.sample` updates (if needed): ensure all variables needed by `compose.yaml` are documented

- `.dockerignore` — Exclude `.git`, `target/`, `.env`, IDE files from Docker context

**Final verification (run all acceptance criteria):**
- [ ] `./mvnw verify` completes with no errors and no annotation processor warnings
- [ ] No Gradle files, Kotlin plugins, or Kotlin source sets exist
- [ ] Maven Wrapper is committed and functional
- [ ] Application starts and connects to PostgreSQL (via Testcontainers in tests)
- [ ] Flyway runs on startup
- [ ] `GET /health` returns 200 OK with UP status including datasource health
- [ ] `GET /health/readiness` reports DOWN when database is unreachable
- [ ] `GET /metrics` exposes Prometheus-format metrics
- [ ] `./mvnw generate-sources` produces API interfaces and models from `api/openapi.yaml`
- [ ] Generated sources compile and are excluded from version control
- [ ] `GET /swagger-ui` serves the authored specification
- [ ] All four exception types are defined and stub handler is registered
- [ ] Logs are emitted as single-line JSON to stdout with correlation ID
- [ ] `docker compose up` starts the application and PostgreSQL, health endpoint passes
- [ ] `.env.sample` documents every environment variable
- [ ] Testcontainers PostgreSQL fixture is available and smoke test passes

**Acceptance criteria:**
- `docker compose build` succeeds
- `docker compose up` starts both app and database containers
- Health endpoint returns UP after startup
- All 16 acceptance criteria from the ticket pass
- `./mvnw verify` is green end-to-end
