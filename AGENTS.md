# Consent Manager

## Overview
Reimplemented Consent Manager service: consent lifecycle management (creation, revocation,
querying) as a stateless OAuth2 **resource server**. API-first — code is generated from the
hand-authored OpenAPI specification.

## Tech Stack
- Language: Java 25
- Build: Maven (`./mvnw`)
- Framework: Micronaut 5 (Netty, Data JDBC, Security JWT, Validation)
- Database: PostgreSQL 16, schema via Flyway
- Test: JUnit 5 + AssertJ + Testcontainers (PostgreSQL, Keycloak) + WireMock
- API: OpenAPI 3.0.3 → `openapi-generator-maven-plugin` (`java-micronaut-server`,
  `generateControllerAsAbstract=true`)
- Logging: Logback + logstash-logback-encoder (structured JSON to stdout)
- Metrics: Micrometer + Prometheus; Formatting: Spotless; Coverage: JaCoCo (gated)

## Project Structure
```
api/
  openapi.yaml                     # Root spec; $refs into components/
  components/security.yaml         # bearerAuth scheme + token contract
  components/schemas/              # ApiStatus, ProblemDetail, ...
  components/responses/            # Unauthorized, Forbidden
src/main/java/com/seamware/consentmanager/
  Application.java
  config/ConsentManagerConfiguration.java   # @ConfigurationProperties("consent-manager")
  domain/                          # User, Participant, UserParticipant, Consent,
                                   # ConsentEvent, PrivacyNotice, enums, UuidGenerator (UUIDv7)
  repository/                      # Micronaut Data JDBC repositories
  service/UserProvisioningService.java      # JIT user provisioning from a USER token
  security/                        # IdentityProviderRegistry, ClaimMapper, token validator,
                                   # Role, typed principals, PrincipalResolutionFilter + binders
  error/                           # BadRequest/NotFound/Forbidden/UpstreamService exceptions,
                                   # GlobalExceptionHandler (catch-all 500),
                                   # AuthorizationProblemHandler (401/403 problem details)
  api/ApiStatusController.java     # Concrete controller extending the generated abstract one
src/main/resources/
  application.yml, application-dev.yml, logback.xml, db/migration/
src/test/java/com/seamware/consentmanager/
  support/                         # PostgresTestResource, KeycloakTestResource,
                                   # KeycloakAndPostgresTestResource, OidcDiscoveryStub, Await
  api/SpecSecurityConsistencyTest.java      # spec <-> @Secured consistency, build-failing
docs/security.md, docs/adr/        # Security model and ADRs 0001-0006
compose.yaml, docker/Dockerfile, .env.sample
```

## Build & Test
```bash
./mvnw verify            # generate, compile, unit (*Test) + integration (*IT) tests
./mvnw test              # unit tests only
./mvnw verify -DskipUTs  # integration tests only
./mvnw mn:run            # run locally (dev profile)
./mvnw spotless:apply    # format (run before committing)
./mvnw spotless:check
./mvnw generate-sources  # regenerate API sources from api/openapi.yaml
```

## Key Conventions
- **API-first.** `api/openapi.yaml` is the source of truth. Never hand-write API interfaces or
  request/response models; author schemas under `api/components/schemas/` and implement the
  generated `Abstract*Controller` with a `@Controller`-annotated concrete subclass (without it the
  route 404s). Generated code lands in `target/generated-sources/openapi/` and is never committed.
- **Security is declared in the spec, not in Java.** `security: [{bearerAuth: [ROLE, ...]}]` makes
  the generator emit `@Secured("ROLE", ...)` on the *routed* method; annotating the concrete
  subclass does not govern the route. Public operations declare `security: []`.
  `SpecSecurityConsistencyTest` fails the build when an operation has no implementation, when
  `@Secured` disagrees with the spec, when a secured operation declares no
  `ConsentManagerPrincipal`-typed parameter, or when it omits the shared 401/403 response refs.
- **Typed principals.** `ConsentManagerPrincipal` is sealed over `UserPrincipal`,
  `ParticipantPrincipal` (carries the resolved `Participant` row) and `CatalogPrincipal`;
  `PrincipalResolutionFilter` resolves them and argument binders inject them into handlers.
- **Errors** are RFC 7807 problem details rendered as `application/problem+json` from the generated
  `ProblemDetail`; there is no hand-written `ApiError`.
- **Doc comments are terse.** One line on what a type or public method is and why, a second only
  for a contract the signature cannot show. No `@param`/`@return` blocks that echo the signature.
  Match the density of the file you are editing. Named constants, never magic values.
- **Stateless.** No sessions, no token issuance, no stored credentials. Java only, no Kotlin.
  Annotation processors go in `maven-compiler-plugin`'s `annotationProcessorPaths`.
- **Config** is externalized to env vars; document every new one in `.env.sample`.
- **Testing.** Testcontainers for real PostgreSQL and Keycloak; WireMock for external stubs;
  parameterized tests where applicable. `*IT` runs under Failsafe. `application-test.yml` disables
  `micronaut.security.enabled`, so security-aware tests re-enable it from their own property source.

## Important Files
- `pom.xml` — build, generator config, Spotless, JaCoCo, the `copy-openapi-spec` execution that
  copies `api/**/*.yaml` to `target/classes/static/` so Swagger UI's relative `$ref`s resolve
- `api/openapi.yaml` — root specification
- `src/main/resources/db/migration/V1__initial_schema.sql` — full core schema
- `src/main/resources/application.yml` — base config, incl. the identity-provider trust list
- `docs/security.md`, `docs/adr/` — security model and recorded decisions
