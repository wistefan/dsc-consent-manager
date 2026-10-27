# Testing

How the suite is organised, and which tests satisfy each acceptance criterion of ticket #83.
Evidence is cited by test class, method or parameter-source name, never by line number, so it
survives edits to the cited files. Grep a name before citing it.

## Conventions

- **`*Test` vs `*IT`.** A `*Test` starts no container and runs under Surefire (`./mvnw test`).
  In-process WireMock is allowed. A `*IT` runs under Failsafe in `./mvnw verify`. Run a single IT
  with `./mvnw verify -Dit.test=FooIT -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false`.
- **Singleton containers.** `support/PostgresTestResource` (image `POSTGRES_IMAGE`, `postgres:16`)
  and `support/KeycloakTestResource` (pinned `quay.io/keycloak/keycloak` image, realm imported from
  `src/test/resources/keycloak/`) each start one container per JVM in a static initialiser. Ryuk
  reaps them at JVM exit. They are not Micronaut test-resources.
- **Which base class to extend.**

  | The IT needs | Extend | Annotate |
  |---|---|---|
  | A database, security off (`application-test.yml` default) | `PostgresTestResource` | `@MicronautTest` |
  | A database and real tokens | `KeycloakAndPostgresTestResource`, which re-enables `micronaut.security.enabled` | `@MicronautTest(transactional = false)` |
  | The privacy-notice API with a Contract Service stub | `PrivacyNoticeApiTestSupport` | `@MicronautTest(transactional = false)` |

  An authenticated IT must be `transactional = false`. Its writes go through HTTP and commit on the
  server's own connections, so a test transaction would isolate nothing.
- **A token per role.** `KeycloakTestResource.accessToken(RealmPrincipal.USER | PARTICIPANT |
  CATALOG | OUTSIDER)` issues a real token for the matching imported realm user. A `PARTICIPANT`
  token carries `KeycloakTestResource.PARTICIPANT_IDENTIFIER`. Commit a `participants` row with
  that identifier first, or every participant-scoped route answers `403`. `OUTSIDER` maps to no
  role.
- **Stubs and fixtures.** `ContractServiceStub`, `CatalogStub` and `OidcDiscoveryStub` wrap
  WireMock on a dynamic port, reached through `localhost`. Contract bodies live in
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
| 1 | Merged JaCoCo coverage report | planned — Step 2 | `pom.xml` pins JaCoCo 0.8.15 with a `0.00` minimum, because 0.8.15 cannot instrument Java 25 class files. |
| 2 | Real PostgreSQL 16 in containers | met | `PostgresTestResource.POSTGRES_IMAGE` is `postgres:16`. Every database IT runs on it, apart from the two exceptions under Decisions, which use the same constant. |
| 3 | "Integration tests authenticate with real tokens from Testcontainers Keycloak" | met | `KeycloakAndPostgresTestResource` re-enables security and is the base of every authenticated IT, `PrivacyNoticeApiTestSupport` included. `KeycloakTestResource.accessToken(RealmPrincipal…)` obtains each token through a password grant against the imported realm. Per-test evidence: `KeycloakRoleMatrixIT.roleMatrixHolds`, `KeycloakKeyRotationIT`. |
| 4 | Containers shared across test classes | met | `PostgresTestResource` and `KeycloakTestResource` are static JVM-wide singletons. `FlywayConcurrentMigrationIT` and `DatasourceOutageReadinessIT` are the deliberate per-class exceptions listed under Decisions. |
| 5 | Database reset between test methods | planned — Step 3 | Today each IT deletes its own rows (`removeCommittedRows` and similar). |
| 6 | "No test makes a real outbound network call" | partial — Step 4 | The criterion is about runtime traffic. Today it is met by inspection only: every configured outbound target is a WireMock stub on `localhost` or a Testcontainers container. Step 4's `NoExternalHostIT` asserts that over every property source of the test context. That is still a configuration check, standing in for runtime traffic. |
| 7 | Consent state-machine matrix | met | `ConsentStateMachineTest.everyPairIsPermittedOrRefusedAsTheTableSays`. |
| 8 | Endpoint × role (× ownership) authorization matrix | partial | Endpoint × role: `EndpointRoleMatrixIT.roleMatrixHolds`, total through `everySpecifiedOperationIsCovered`; `KeycloakRoleMatrixIT.roleMatrixHolds`. The ownership dimension is planned — Step 7 (`OwnershipMatrixIT`). |
| 9 | Token failure matrix covered by parameterized tests | met | `ConsentManagerTokenValidatorTest` has `@ParameterizedTest`s fed by the `refusedTokens` and `refusedHeaders` method sources, one case per failure; `TokenSignatureEnforcementTest` covers signature enforcement as well. These run against stubs. Step 8 adds missing cases to both sources, plus the parameterized live-Keycloak `KeycloakTokenTamperIT`. |
| 10–12 | Concurrency: registration, grant, notice generation | partial | `UserServiceIT.concurrentFirstRequestsProvisionOnce`, `UserServiceIT.concurrentRegistrationsSettleOnOneRow`, `ConsentGrantAtomicityIT`, `PrivacyNoticeConcurrencyIT.concurrentGenerationYieldsExactlyOneActiveNotice`. Step 6 adds HTTP-level registration, re-grant races and raw-SQL final-state assertions. |
| 13 | "All eight security tests pass" | partial | This row is the aggregate of the [Security tests](#security-tests) table and takes its status from that table. #1, #3 and #4 are partial until Step 7. |
| 14 | Signing-key rotation at the identity provider | met | `KeycloakKeyRotationIT.rotatedSigningKeyIsPickedUpWithoutARestart`. |
| 15 | Flyway migrations "verified against an empty database once per suite" | partial — Step 3 | Today this rests on the singleton `PostgresTestResource`: its empty container is migrated once, at the first context start, and nothing asserts that. `FlywayConcurrentMigrationIT.concurrentReplicasApplyEachVersionOnce` proves migration on an empty database, but on its own per-class containers, not the suite's. Step 3's `SchemaIsolationIT` asserts that the shared container's `flyway_schema_history` lists every migration as successful. |
| 16 | No secret or personal data in logs | met | `LogLeakIT`, `KeyMaterialLeakIT`. Step 8 widens the forbidden values and scenarios. |
| 17 | Branch coverage on security-critical classes | planned — Step 11 | |
| 18 | Responses validated against the spec | planned — Step 5 | Nothing validates responses against the spec today. |
| 19 | Served spec equals the authored spec | met | `OpenApiServingIT.servesTheBundleUnchanged` compares the served document with `target/openapi-bundle/openapi.yaml`, the bundle of the authored spec. |
| 20 | Spec lint and breaking-change check as build steps | partial | `SpecLintIT` and `SpecCompatibilityIT` run inside `./mvnw verify`. There is no CI workflow yet; planned — Step 12. |
| 21 | Spec and code agree on security | met | `SpecSecurityConsistencyTest`. |
| 22 | Deterministic suite | planned — Step 12 | Repeated `./mvnw verify` runs, with the run count and outcome recorded here. |

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
| External service stubbing | partial | `ContractServiceStub`, `CatalogStub`, `OidcDiscoveryStub`. Stub reset after each test and the six-case `ContractFixtureMatrixIT` are planned — Step 4. |
| Request validation | partial | `ParticipantValidationIT`, `ParticipantUserRegistrationIT`. The remaining request bodies are planned — Step 9. |
| Error mapping | partial | `ProblemMappingTest`, `ProblemResponseIT`. Per-handler rows, the totality check, and the 401/502 shapes are planned — Step 9. |
| Receipts | partial | `JwsConsentSigningServiceTest` covers algorithm × tamper, and `ConsentReceiptIT` covers route × format. The full format × algorithm × tamper cross is planned — Step 10. |
