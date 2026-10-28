# Testing

How the suite is organised, and which tests satisfy each acceptance criterion of ticket #83.
Evidence is cited by test class, method or parameter-source name, never by line number, so it
survives edits to the cited files. Grep a name before citing it.

## Conventions

- **`*Test` vs `*IT`.** A `*Test` starts no container and runs under Surefire (`./mvnw test`).
  In-process WireMock is allowed. A `*IT` runs under Failsafe in `./mvnw verify`. Run a single IT
  with `./mvnw verify -Dit.test=FooIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false
  -Djacoco.skip=true`. Without `-Djacoco.skip=true` the coverage gate measures that one IT and
  fails the build after the test has passed.
- **Coverage data accumulates.** The JaCoCo agent appends to `target/jacoco.exec` and
  `target/jacoco-it.exec`, so a `verify` without `clean` can pass the gate on data from tests that
  no longer exist. Run `./mvnw clean verify` before trusting a local coverage figure.
- **Singleton containers.** `support/PostgresTestResource` (image `POSTGRES_IMAGE`, `postgres:16`)
  and `support/KeycloakTestResource` (pinned `quay.io/keycloak/keycloak` image, realm imported from
  `src/test/resources/keycloak/`) each start one container per JVM in a static initialiser. Ryuk
  reaps them at JVM exit. They are not Micronaut test-resources.
- **Which base class to extend.**

  | The IT needs | Extend | Annotate |
  |---|---|---|
  | A database, security off (`application-test.yml` default) | `PostgresTestResource` | `@MicronautTest(transactional = false)` |
  | A database and real tokens | `KeycloakAndPostgresTestResource`, which re-enables `micronaut.security.enabled` | `@MicronautTest(transactional = false)` |
  | The privacy-notice API with a Contract Service stub | `PrivacyNoticeApiTestSupport` | `@MicronautTest(transactional = false)` |

  Every IT on these bases must be `transactional = false`. An HTTP write commits on the server's own
  connections, so a test transaction would isolate nothing. Worse, Micronaut Test rolls it back
  only after `@AfterEach`, so its locks would block the schema reset below. Read the database
  outside a transaction through `DataSourceResolver.resolve(dataSource)`, because the injected
  proxy refuses a connection outside one.
- **Schema reset after every method.** `PostgresTestResource.resetSharedSchema` is a `final`
  `@AfterEach`. It calls `support/DatabaseReset`, which issues one
  `TRUNCATE … RESTART IDENTITY` over every `public` table except `flyway_schema_history`. It runs
  on its own JDBC connection, outside the Micronaut context. A subclass's own `@AfterEach` runs
  first. Seed in `@BeforeEach`, never in `@BeforeAll` or a per-class cache: rows from method 1 are
  gone by method 2. An in-memory result computed once per class may be kept, as `LogLeakIT` does.
  Never delete rows by hand. The reset sets `lock_timeout` (`DatabaseReset.LOCK_TIMEOUT`, 5 s)
  before truncating. A test that still holds a lock, such as an open transaction or a post-commit
  task still running, therefore fails with a lock-timeout error from `DatabaseReset` instead of
  hanging the suite. `support/SchemaIsolationIT` is the canary.
- **A token per role.** `KeycloakTestResource.accessToken(RealmPrincipal.USER | PARTICIPANT |
  CATALOG | OUTSIDER)` issues a real token for the matching imported realm user. A `PARTICIPANT`
  token carries `KeycloakTestResource.PARTICIPANT_IDENTIFIER`. Commit a `participants` row with
  that identifier first, or every participant-scoped route answers `403`. `OUTSIDER` maps to no
  role.
- **Stubs and fixtures.** `ContractServiceStub`, `CatalogStub` and `OidcDiscoveryStub` wrap
  WireMock on a dynamic port, reached through `localhost`. Each holder calls `reset()` in
  `@AfterEach` and re-registers any baseline stub in `@BeforeEach`. Contract bodies live in
  `src/test/resources/contracts/`. `ConsentFixtures` and `PrivacyNoticeFixtures` build domain rows. `Await` replaces sleeps.
- **ITs outside the shared base classes.** These ITs extend neither `PostgresTestResource` nor
  `KeycloakAndPostgresTestResource`: `ContractCacheIT`, `SelfDescriptionCacheIT`,
  `SmtpUserNotificationServiceIT`, `ManagementEndpointSecurityIT`, `DatasourceOutageReadinessIT`,
  `FlywayConcurrentMigrationIT`, `SpecLintIT` and `SpecCompatibilityIT`.

## Decisions

- **No `logback-test.xml`.** Tests run under the production `src/main/resources/logback.xml` on
  purpose, so the log-leak scans (AC 16) cover the configuration that ships. `LogCapture` attaches
  to the root logger and survives Logback's reset at context start. Quiet noisy loggers through
  `logger.levels` in `application-test.yml`, never through a test Logback file.
- **Two per-class PostgreSQL containers.** Each of these ITs starts its own
  `new PostgreSQLContainer`, and neither is a regression of AC 4:
  - `FlywayConcurrentMigrationIT`: each replica count must migrate an empty database.
  - `DatasourceOutageReadinessIT`: it stops its database, which would break every other IT on the
    shared container.

## Acceptance-criteria traceability

Status is `met`, `partial` or `planned — Step N`, where N is a step of the ticket's
`IMPLEMENTATION_PLAN.md`. Quoted criteria are the ticket's wording. Unquoted ones are short forms
of it, so check the evidence against the ticket text, not against the short form.

| AC | Criterion | Status | Evidence / plan |
|---|---|---|---|
| 1 | Merged JaCoCo coverage report | met | `./mvnw verify` merges `target/jacoco.exec` (Surefire) and `target/jacoco-it.exec` (Failsafe) into `target/jacoco-merged.exec`, reports it to `target/site/jacoco/`, and `jacoco:check` fails the build below the BUNDLE minimums `jacoco.minimum.line-coverage` and `jacoco.minimum.branch-coverage`. Each is the measured ratio rounded down to 0.05 (2026-10-27: LINE 0.627 → 0.60, BRANCH 0.454 → 0.45); raise them when coverage grows. Generated API models and Micronaut's `$*Definition*`, `$*Introspection*` and `$*Intercepted*` classes are excluded from report and check, not from the agent. |
| 2 | Real PostgreSQL 16 in containers | met | `PostgresTestResource.POSTGRES_IMAGE` is `postgres:16`. Every database IT runs on it, apart from the two exceptions under Decisions, which use the same constant. |
| 3 | "Integration tests authenticate with real tokens from Testcontainers Keycloak" | met | `KeycloakAndPostgresTestResource` re-enables security and is the base of every authenticated IT, `PrivacyNoticeApiTestSupport` included. `KeycloakTestResource.accessToken(RealmPrincipal…)` obtains each token through a password grant against the imported realm. Per-test evidence: `KeycloakRoleMatrixIT.roleMatrixHolds`, `KeycloakKeyRotationIT`. |
| 4 | Containers shared across test classes | met | `PostgresTestResource` and `KeycloakTestResource` are static JVM-wide singletons. `FlywayConcurrentMigrationIT` and `DatasourceOutageReadinessIT` are the deliberate per-class exceptions listed under Decisions. |
| 5 | Database reset between test methods | met | `PostgresTestResource.resetSharedSchema` (`@AfterEach`, `final`) truncates every application table through `DatabaseReset`. `SchemaIsolationIT.nextMethodStartsEmpty` asserts that rows committed by one method are gone in the next. No IT on the shared bases runs `transactional = true`, and none seeds in `@BeforeAll`. |
| 6 | "No test makes a real outbound network call" | met (configuration) | `NoExternalHostIT.everyDialledUrlStaysLocal` walks every property source of a context built on `KeycloakAndPostgresTestResource` and asserts each absolute `http(s)`/`jdbc` URL names a loopback host or the Testcontainers Docker host. The criterion is about runtime traffic; this is a configuration check standing in for it. |
| 7 | Consent state-machine matrix | met | `ConsentStateMachineTest.everyPairIsPermittedOrRefusedAsTheTableSays`. |
| 8 | Endpoint × role (× ownership) authorization matrix | partial | Endpoint × role: `EndpointRoleMatrixIT.roleMatrixHolds`, total through `everySpecifiedOperationIsCovered`; `KeycloakRoleMatrixIT.roleMatrixHolds`. The ownership dimension is planned — Step 7 (`OwnershipMatrixIT`). |
| 9 | Token failure matrix covered by parameterized tests | met | `ConsentManagerTokenValidatorTest` has `@ParameterizedTest`s fed by the `refusedTokens` and `refusedHeaders` method sources, one case per failure; `TokenSignatureEnforcementTest` covers signature enforcement as well. These run against stubs. Step 8 adds missing cases to both sources, plus the parameterized live-Keycloak `KeycloakTokenTamperIT`. |
| 10–12 | Concurrency: registration, grant, notice generation | partial | `UserServiceIT.concurrentFirstRequestsProvisionOnce`, `UserServiceIT.concurrentRegistrationsSettleOnOneRow`, `ConsentGrantAtomicityIT`, `PrivacyNoticeConcurrencyIT.concurrentGenerationYieldsExactlyOneActiveNotice`. Step 6 adds HTTP-level registration, re-grant races and raw-SQL final-state assertions. |
| 13 | "All eight security tests pass" | partial | This row is the aggregate of the [Security tests](#security-tests) table and takes its status from that table. #1, #3 and #4 are partial until Step 7. |
| 14 | Signing-key rotation at the identity provider | met | `KeycloakKeyRotationIT.rotatedSigningKeyIsPickedUpWithoutARestart`. |
| 15 | Flyway migrations "verified against an empty database once per suite" | met | The singleton `PostgresTestResource` container starts empty and is migrated at the first context start. `SchemaIsolationIT.everyMigrationAppliedOnce` asserts that its `flyway_schema_history` lists every file in `db/migration/` exactly once, and as successful. `DatabaseReset` never truncates that table. `FlywayConcurrentMigrationIT.concurrentReplicasApplyEachVersionOnce` additionally proves concurrent migration of an empty database, on its own containers. |
| 16 | No secret or personal data in logs | met | `LogLeakIT`, `KeyMaterialLeakIT`. Step 8 widens the forbidden values and scenarios. |
| 17 | Branch coverage on security-critical classes | planned — Step 11 | |
| 18 | Responses validated against the spec | met | `support/OpenApiResponseValidationFilter` checks every response the embedded server returns to a Micronaut test client against `target/openapi-bundle/openapi.yaml`: declared status, body schema and content type, `application/problem+json` included. Every operation declares a `default` error response, so a status below 400 must be named explicitly. A route or method the spec does not publish may only be refused with a 4xx, which the role-matrix and principal-resolution probes of unpublished routes rely on. Violations are collected in `ResponseViolations` and `PostgresTestResource.assertResponsesMatchSpec` (`@AfterEach`, `final`) fails the test. Requests are not validated. Exchanges with other hosts or ports (a loopback address counts as the server's own host), the test probe routes, and the service's non-spec origin routes (management endpoints, `*.yaml`, `/swagger-ui/**`) are skipped; `/.well-known/jwks.json` is validated. `OpenApiResponseValidationFilterTest` proves each rule, and `ResponseValidationWiringIT` proves real client exchanges reach the filter, error bodies included. The collector is a hard assertion, so a subclass outside a Micronaut context fails rather than skipping the check. See [Response validation coverage](#response-validation-coverage) for what is not covered. |
| 19 | Served spec equals the authored spec | met | `OpenApiServingIT.servesTheBundleUnchanged` compares the served document with `target/openapi-bundle/openapi.yaml`, the bundle of the authored spec. |
| 20 | Spec lint and breaking-change check as build steps | partial | `SpecLintIT` and `SpecCompatibilityIT` run inside `./mvnw verify`. There is no CI workflow yet; planned — Step 12. |
| 21 | Spec and code agree on security | met | `SpecSecurityConsistencyTest`. |
| 22 | Deterministic suite | planned — Step 12 | Repeated `./mvnw clean verify` runs, with the run count and outcome recorded here. Compare the merged BRANCH ratio across runs as well as the test summaries: the gate's margin is 0.004 (0.454 against 0.45), so a run-to-run branch swing would make the gate itself flaky. |

### Response validation coverage

The response check reaches only Micronaut `HttpClient` exchanges with the embedded server's API
port, and only a test on `PostgresTestResource` asserts the collector. Not covered:

- Tests that call the service through `java.net.http`. None does today;
  `KeycloakTestResource` uses it only to fetch tokens from Keycloak.
- ITs that do not extend `PostgresTestResource`: `ContractCacheIT`, `SelfDescriptionCacheIT`,
  `SmtpUserNotificationServiceIT`, `ManagementEndpointSecurityIT`, `DatasourceOutageReadinessIT`,
  `FlywayConcurrentMigrationIT`, `SpecLintIT` and `SpecCompatibilityIT`. Of these, only
  `ManagementEndpointSecurityIT` and `DatasourceOutageReadinessIT` call the service over HTTP, and
  only its management routes, which are outside the spec. `TokenSignatureEnforcementTest` starts
  its own servers and is likewise unchecked.

### Security tests

| # | Status | Evidence / plan |
|---|---|---|
| 1 | partial | Ownership is pinned only for participant records (`EndpointRoleMatrixIT.aParticipantTokenNeverReachesAnotherRecord`). Planned — Step 7 (`OwnershipMatrixIT`). |
| 2 | met | `UserDirectoryIT`. |
| 3 | partial | "Cannot read or act on another user's consent." Act: `ConsentLifecycleSurfaceIT.refusesAConsentBelongingToAnotherSubject` (HTTP). Read: `ConsentQueryServiceIT.hidesAnotherSubjectsConsent`, at service level only. The HTTP read path is planned — Step 7 (`OwnershipMatrixIT`). |
| 4 | partial | `EndpointRoleMatrixIT.noWriteRouteLetsTheCallerNameTheRecord`. Planned — Step 7 (`IdentitySubstitutionIT`). |
| 5 | met | The "a key genuinely valid at another trusted issuer" case of `ConsentManagerTokenValidatorTest`'s `refusedTokens` source. Step 8 adds `CrossIssuerKeycloakIT`. |
| 6 | met | `KeycloakKeyRotationIT`. |
| 7 | met | `IssuerSignatureVerifierTest.unknownKeyIdentifiersTriggerNoRefetch`. Step 8 adds the live-IdP check. |
| 8 | met | `LogLeakIT`. |

### Matrices

| Matrix | Status | Evidence / plan |
|---|---|---|
| External service stubbing | met | `ContractServiceStub`, `CatalogStub`, `OidcDiscoveryStub`, each reset by its holder's `@AfterEach`. `ContractFixtureMatrixIT.yieldsTheExpectedOutcome` covers the six fixture cases (well-formed bilateral, well-formed ecosystem, missing optional sections, unresolvable offering, 500, timeout — the last split into ecosystem and bilateral) by status and resolution state. |
| Request validation | partial | `ParticipantValidationIT`, `ParticipantUserRegistrationIT`. The remaining request bodies are planned — Step 9. |
| Error mapping | partial | `ProblemMappingTest`, `ProblemResponseIT`. Per-handler rows, the totality check, and the 401/502 shapes are planned — Step 9. |
| Receipts | partial | `JwsConsentSigningServiceTest` covers algorithm × tamper, and `ConsentReceiptIT` covers route × format. The full format × algorithm × tamper cross is planned — Step 10. |
