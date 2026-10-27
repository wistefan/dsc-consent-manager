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
`IMPLEMENTATION_PLAN.md`. The criterion column paraphrases each criterion as the plan cites it.

| AC | Criterion | Status | Evidence / plan |
|---|---|---|---|
| 1 | Merged JaCoCo coverage report | planned — Step 2 | `pom.xml` pins JaCoCo 0.8.15 with a `0.00` minimum, because 0.8.15 cannot instrument Java 25 class files. |
| 2 | Real PostgreSQL 16 in containers | met | `PostgresTestResource.POSTGRES_IMAGE` is `postgres:16`. Every database IT runs on it, apart from the two exceptions under Decisions, which use the same constant. |
| 3 | *Not cited in the plan* | to confirm | The plan names no gap for AC 3. Record its criterion text and evidence here. |
| 4 | Containers shared across test classes | met | `PostgresTestResource` and `KeycloakTestResource` are static JVM-wide singletons. `FlywayConcurrentMigrationIT` and `DatasourceOutageReadinessIT` are the deliberate per-class exceptions listed under Decisions. |
| 5 | Database reset between test methods | planned — Step 3 | Today each IT deletes its own rows (`removeCommittedRows` and similar). |
| 6 | No calls to external hosts | met by inspection | No outbound host is configured outside loopback: WireMock is reached through `localhost`, and the containers through Testcontainers. `NoExternalHostIT` (Step 4) turns this into a test. |
| 7 | Consent state-machine matrix | met | `ConsentStateMachineTest.everyPairIsPermittedOrRefusedAsTheTableSays`. |
| 8 | Endpoint × role (× ownership) authorization matrix | partial | Endpoint × role: `EndpointRoleMatrixIT.roleMatrixHolds`, total through `everySpecifiedOperationIsCovered`; `KeycloakRoleMatrixIT.roleMatrixHolds`. The ownership dimension is planned — Step 7 (`OwnershipMatrixIT`). |
| 9 | Token validation refuses every invalid token | met | The `refusedTokens` and `refusedHeaders` sources of `ConsentManagerTokenValidatorTest`, and `TokenSignatureEnforcementTest`. These run against stubs; Step 8 adds the live-Keycloak counterparts. |
| 10–12 | Concurrency: registration, grant, notice generation | partial | `UserServiceIT.concurrentFirstRequestsProvisionOnce`, `UserServiceIT.concurrentRegistrationsSettleOnOneRow`, `ConsentGrantAtomicityIT`, `PrivacyNoticeConcurrencyIT.concurrentGenerationYieldsExactlyOneActiveNotice`. Step 6 adds HTTP-level registration, re-grant races and raw-SQL final-state assertions. |
| 13 | *Not cited in the plan* | to confirm | The plan names no gap for AC 13. Record its criterion text and evidence here. |
| 14 | Signing-key rotation at the identity provider | met | `KeycloakKeyRotationIT.rotatedSigningKeyIsPickedUpWithoutARestart`. |
| 15 | Flyway migrations, including concurrent replicas | met | `FlywayConcurrentMigrationIT.concurrentReplicasApplyEachVersionOnce`. Step 3's `SchemaIsolationIT` adds the suite-level migration check. |
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
| 3 | met | `ConsentLifecycleSurfaceIT`. |
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
