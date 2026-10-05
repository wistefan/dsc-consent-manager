# Consent Manager

## Overview
A reimplemented Consent Manager service built on Java 25 + Micronaut 5 + PostgreSQL 16. It provides consent lifecycle management (creation, revocation, querying) as a stateless OAuth2 resource server with an API-first design where code is generated from an OpenAPI specification.

## Tech Stack
- Language: Java 25
- Build: Maven (with Maven Wrapper — `./mvnw`)
- Framework: Micronaut 5 (HTTP server via Netty, Data JDBC, Security JWT, Validation)
- Database: PostgreSQL 16
- Schema Management: Flyway
- Test: JUnit 5 + AssertJ + Testcontainers (PostgreSQL, Keycloak) + WireMock
- API: OpenAPI 3.x spec → generated server interfaces and models (`openapi-generator-maven-plugin`)
- Logging: Logback with logstash-logback-encoder (structured JSON to stdout)
- Metrics: Micrometer + Prometheus registry
- Formatting: Spotless

## Project Structure
```
api/
  openapi.yaml                    # Hand-authored OpenAPI spec (source of truth for API)
src/main/java/com/seamware/consentmanager/
  Application.java                # Micronaut entry point
  config/
    ConsentManagerConfiguration.java  # @ConfigurationProperties root
  domain/                         # Domain entities (future tickets)
  repository/                     # Micronaut Data JDBC repositories (future tickets)
  service/                        # Business logic services (future tickets)
  api/
    dto/                          # Hand-written DTOs if needed (future tickets)
  error/
    # NOTE: errors use the generated api.generated.model.ProblemDetail (RFC 7807);
    #       there is no hand-written ApiError class.
    BadRequestException.java      # 400
    NotFoundException.java        # 404
    ForbiddenException.java       # 403
    UpstreamServiceException.java # 502
    GlobalExceptionHandler.java   # Stub exception handler
src/main/resources/
  application.yml                 # Base configuration
  application-dev.yml             # Dev environment overrides
  logback.xml                     # Structured JSON logging config
  db/migration/                   # Flyway SQL migration files
src/test/java/com/seamware/consentmanager/
  support/
    PostgresTestResource.java     # Testcontainers PostgreSQL fixture
docker/
  Dockerfile                      # Multi-stage build (Maven build → JRE runtime)
compose.yaml                      # App + PostgreSQL for local development
.env.sample                       # Documents all environment variables
```

## Build & Test
```bash
# Full build: generate sources, compile, test, package
./mvnw verify

# Run locally with dev profile
./mvnw mn:run

# Unit tests only
./mvnw test

# Integration tests only
./mvnw verify -DskipUTs

# Format sources
./mvnw spotless:apply

# Check formatting
./mvnw spotless:check

# Generate API sources from OpenAPI spec
./mvnw generate-sources

# Build Docker image
./mvnw package -Dpackaging=docker
```

## Key Conventions
- **API-first**: The OpenAPI spec at `api/openapi.yaml` is the source of truth. Code is generated from it; never hand-write API interfaces or request/response DTOs.
- **Generated code**: Lives under `target/generated-sources/openapi/` — never committed to version control.
- **Package root**: `com.seamware.consentmanager`
- **Generated API package**: `com.seamware.consentmanager.api.generated`
- **Generated model package**: `com.seamware.consentmanager.api.generated.model`
- **Error handling**: RFC 7807 Problem Details; all exceptions extend a common pattern with HTTP status mapping.
- **No Kotlin**: Java only — no Kotlin source sets, plugins, or dependencies.
- **Annotation processors**: Declared in `maven-compiler-plugin`'s `annotationProcessorPaths`, not as runtime dependencies.
- **Stateless**: No sessions, no token issuance, no stored credentials. OAuth2 resource server only.
- **Environment variables**: All config is externalized via env vars (see `.env.sample`).
- **Logging**: Structured JSON to stdout, no file appenders. MDC correlation ID on all requests.
- **Testing**: Testcontainers for real PostgreSQL; WireMock for external service stubs; parameterized tests where applicable.

## Important Files
- `pom.xml` — Maven build configuration with all dependencies and plugins
- `api/openapi.yaml` — Hand-authored OpenAPI specification (source of truth)
- `src/main/resources/application.yml` — Base application configuration
- `src/main/resources/application-dev.yml` — Dev environment overrides
- `src/main/resources/logback.xml` — Logging configuration
- `compose.yaml` — Docker Compose for local development
- `docker/Dockerfile` — Multi-stage Docker build
- `.env.sample` — Environment variable documentation
