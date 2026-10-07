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
- API: OpenAPI 3.0.3 → `openapi-generator-maven-plugin` 7.12.0 (`java-micronaut-server`,
  `generateControllerAsAbstract=true`, `wrapInHttpResponse=true`, `reactive=false`,
  `micronaut_serde_jackson`)
- Logging: Logback + logstash-logback-encoder (structured JSON to stdout)
- Metrics: Micrometer + Prometheus; Formatting: Spotless; Coverage: JaCoCo (gated)

## Project Structure
```
api/
  openapi.yaml                     # Root spec. Paths are INLINE here; only schemas,
                                   # responses and securitySchemes are externalised.
  components/security.yaml         # bearerAuth scheme + token contract
  components/schemas/              # ApiStatus, ProblemDetail, User, UserRegistration,
                                   # UserRegistrationResult, UserSearch, ErasureSummary,
                                   # Participant, ParticipantEndpoints, ParticipantLegalPerson,
                                   # ParticipantRegistration, ParticipantUpdate,
                                   # DeregistrationSummary, ParticipantUserLink(+Update),
                                   # BulkUserRegistrationEntry, BulkRegistrationEntryResult,
                                   # BulkRegistrationSummary
  templates/server/controller.mustache   # Only override: emits the x-principal parameter
                                   # first, fully qualified, with no binding annotation
src/main/java/com/seamware/consentmanager/
  Application.java
  config/ConsentManagerConfiguration.java   # @ConfigurationProperties("consent-manager"),
                                   # nested Erasure / Users / Participants classes
  domain/                          # User, Participant, UserParticipant, Consent,
                                   # ConsentEvent, ConsentStatus, ConsentEventState,
                                   # ConsentSnapshot, PrivacyNotice, PrivacyNoticePayload,
                                   # UuidGenerator (UUIDv7), MicrosecondDateTimeProvider
  repository/                      # Micronaut Data JDBC repositories (User, Participant,
                                   # UserParticipant, Consent, ConsentEvent, PrivacyNotice)
  service/                         # UserService (registration, JIT provisioning, search,
                                   # links, ADR-0007 erasure), ParticipantService
                                   # (registration, self-service, ADR-0008 deregistration),
                                   # ErasureVerifier, CallerScope, and the service-layer
                                   # records (UserRegistration, ParticipantRegistration,
                                   # ParticipantUpdate, DeregistrationResult, ErasureResult,
                                   # ProvisionedUser, RegistrationResult, BulkEntryResult,
                                   # UserSearchCriteria)
  security/                        # IdentityProviderRegistry, ClaimMapper, token validator,
                                   # Role, sealed ConsentManagerPrincipal + UserPrincipal /
                                   # ParticipantPrincipal / CatalogPrincipal,
                                   # PrincipalResolutionFilter + argument binders
  error/                           # ApiException + BadRequest/NotFound/Forbidden/Conflict/
                                   # UpstreamService, ProblemType, ApiExceptionHandler,
                                   # GlobalExceptionHandler, AuthorizationProblemHandler,
                                   # ConstraintViolationProblemHandler,
                                   # ProblemErrorResponseProcessor
  api/                             # Concrete controllers extending generated Abstract*
                                   # (ApiStatus, User, Participant, ParticipantUser),
                                   # UserMapper, ParticipantMapper, JsonNullableSerde
src/main/resources/
  application.yml, application-dev.yml, logback.xml
  static/swagger-ui/index.html     # makes the bundled Swagger UI work
  db/migration/V0__baseline.sql, V1__initial_schema.sql, V2__user_erasure_verifier.sql,
               V3__participant_deregistration.sql
src/test/java/com/seamware/consentmanager/
  support/                         # PostgresTestResource, KeycloakTestResource,
                                   # KeycloakAndPostgresTestResource, OidcDiscoveryStub, Await
  api/                             # SpecSecurityConsistencyTest, SpecReferenceResolutionTest,
                                   # ParticipantSchemaParityTest,
                                   # ParticipantCredentialAbsenceTest — all build-failing
docs/security.md, docs/user-identifiers.md, docs/adr/   # ADRs 0001-0008
compose.yaml, docker/Dockerfile, .env.sample
```

## Build & Test
```bash
./mvnw verify            # generate, compile, unit (*Test) + integration (*IT) tests
./mvnw test              # unit tests only
./mvnw mn:run            # run locally (dev profile)
./mvnw spotless:apply    # format (run before committing)
./mvnw generate-sources  # regenerate API sources from api/openapi.yaml
```
There is **no integration-only flag.** Surefire and Failsafe are declared with no
`<configuration>` and share the `skipTests` property, and no property separating them is wired
in `pom.xml`. `-DskipUTs` is undefined; Maven ignores it silently and runs the whole suite.

## Key Conventions
- **API-first.** `api/openapi.yaml` is the source of truth. Never hand-write API interfaces or
  request/response models; author schemas under `api/components/schemas/` and implement the
  generated `Abstract*Controller` with a `@Controller`-annotated concrete subclass (without it
  the route 404s). The abstract class is named from the operation's `tags` value. Generated
  code lands in `target/generated-sources/openapi/` and is never committed.
- **Gotcha:** a schema whose `items:` `$ref`s another named model must be written *inline* in
  `openapi.yaml` — a cross-file `$ref` under `items` degrades to `List<Object>`. Object items
  declared inline *within* a single component file are unaffected.
- **Gotcha:** a `nullable: true` response property forces `JsonNullable` on the model, so
  response schemas declare no nullable properties — optional means absent. The PATCH tri-state
  pattern (`nullable: true` + `x-is-jackson-optional-nullable: true`) is used only on update
  bodies and needs `JsonNullableSerde`.
- **Security is declared in the spec, not in Java.** Each operation declares three things that
  must agree: `security: [{bearerAuth: [ROLE, ...]}]`, `x-roles: [ROLE, ...]` (→ `@Secured` on
  the *routed* method) and `x-principal: <PrincipalType>`. Public operations declare
  `security: []`. `SpecSecurityConsistencyTest` fails the build when an operation has no
  concrete implementation, when `@Secured` disagrees with the spec, when a secured operation
  declares no `ConsentManagerPrincipal`-typed parameter, when the declared principal type
  cannot hold every admitted role, when it omits the shared 401/403 response refs, or when the
  parsed operation count drifts from `SPECIFIED_OPERATION_COUNT` (**16** today). Raise that
  constant in the same commit that adds an operation. `SpecReferenceResolutionTest` fails the
  build when a relative `$ref` does not resolve in either the authored `api/` tree or the
  `target/classes/static/` copy, or when a **path item is externalised** — so never create
  `api/paths/*.yaml`.
- **Typed principals, and `ParticipantPrincipal` is a three-state contract.**
  `ConsentManagerPrincipal` is sealed over `UserPrincipal`, `ParticipantPrincipal` and
  `CatalogPrincipal`; `PrincipalResolutionFilter` resolves them on the blocking executor and
  argument binders inject them as the handler's first, unannotated parameter.
  `ParticipantPrincipal` may carry an identifier whose `participants` row is **absent** — that
  is what makes self-registration reachable, and `POST /participants` is the only route that
  accepts it. `requireRegistered()` admits any registered row and is what a route reading or
  writing the caller's *own* record uses (`ParticipantController`, and `CallerScope.of()`,
  which is why every participant-scoped read fails closed for an unregistered token).
  `requireActive()` is stricter by one condition — it also refuses a row carrying
  `deregistered_at` — and is what every route acting in the dataspace in the participant's name
  uses (`ParticipantUserController`), so a departed participant cannot re-create the
  affiliations deregistration removed while still reading its own record back.
- **Errors** are RFC 7807 problem details rendered as `application/problem+json` from the
  generated `ProblemDetail`; throw `NotFoundException`/`ConflictException`/… rather than
  returning an error response. `UpstreamServiceException` maps to `502`.
- **Transactions.** `jakarta.transaction.Transactional` on cascading writes. Create paths are
  deliberately *not* transactional: create-or-link catches the unique-constraint violation and
  re-reads, which Postgres forbids inside a transaction. Micronaut AOP does not apply to
  self-invocation, so a `@Transactional` method must be called across a bean boundary.
- **Pagination.** `GET /participants` clamps a `size` above
  `consent-manager.participants.page-max-size` and answers `200` rather than refusing, and the
  response reports the size it actually applied. The spec carries no `maximum` because the
  ceiling is configured per deployment and a YAML literal would drift from it.
- **Identifiers in paths.** A path variable never spans a `/`, so a URI-shaped identifier
  reaches `GET /users/{identifier}` or `GET /participants/{identifier}` only percent-encoded,
  and whether an encoded slash survives is a property of the deployment rather than of this
  service (this stack happens to route `%2F` through, because the variable is decoded after the
  route is matched). The *dependable* routes are the `identifier` query filter on
  `GET /participants` and the exact `identifier` criterion on `POST /users/search`. Separately,
  the literal identifier `me` is unreachable on `/participants/{identifier}` in every
  deployment, because `GET /participants/me` claims the path. See `docs/user-identifiers.md`.
- **Doc comments are terse.** One line on what a type or public method is and why, a second
  only for a contract the signature cannot show. No `@param`/`@return` blocks that echo the
  signature. Match the density of the file you are editing. Named constants, never magic values.
- **Stateless.** No sessions, no token issuance, no stored credentials. Java only, no Kotlin.
  Annotation processors go in `maven-compiler-plugin`'s `annotationProcessorPaths`.
- **Config** is externalized to env vars; document every new one in `.env.sample` **and**
  `application.yml`.
- **Testing.** Testcontainers for real PostgreSQL and Keycloak; WireMock for external stubs;
  parameterized tests where applicable. `*IT` runs under Failsafe. `application-test.yml`
  disables `micronaut.security.enabled`; an authenticated IT uses
  `@MicronautTest(transactional = false)` + `support/KeycloakAndPostgresTestResource` (which
  re-enables security) and gets a token from
  `KeycloakTestResource.accessToken(RealmPrincipal.USER | PARTICIPANT | CATALOG | OUTSIDER)`.

## Important Files
- `pom.xml` — build, generator config, Spotless, JaCoCo, the `copy-openapi-spec` execution that
  copies `api/**/*.yaml` to `target/classes/static/` so Swagger UI's relative `$ref`s resolve
- `api/openapi.yaml` — root specification, paths included
- `src/main/resources/db/migration/V1__initial_schema.sql` — full core schema. Note the FK
  policies: `user_participants` cascades, `consents` and `privacy_notices` restrict on
  participant/user deletion, `consent_events` cascades from its consent. `privacy_notices`
  also carries `uq_privacy_notices_active_contract (contract_uri, provider_id)
  WHERE archived_at IS NULL`, which is what makes concurrent notice generation safe.
- `src/main/resources/db/migration/V3__participant_deregistration.sql` — `deregistered_at`,
  nullable, null for every active participant, set only on deregistration's retain branch, with
  a partial index keeping the directory listing to active rows.
- `src/main/resources/application.yml` — base config, incl. the identity-provider trust list
- `docs/security.md`, `docs/adr/` — security model and recorded decisions
- `IMPLEMENTATION_PLAN.md` — the plan for the ticket currently in flight
