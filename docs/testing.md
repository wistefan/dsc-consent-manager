# Testing

[README](../README.md) › [Documentation](README.md) › Testing

How the suite is organised, and which tests satisfy each acceptance criterion of ticket #83 and,
in [its own section](#participant-notifications-ticket-94), of ticket #94.
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
  role. A test that needs another principal calls `KeycloakTestResource.createPrincipal`, which
  adds a uniquely named realm user. The realm is otherwise read-only at runtime, so its import
  already sets the user profile's `unmanagedAttributePolicy` to `ADMIN_EDIT`. Without that,
  Keycloak would drop a created user's `participant_id`.
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

Every criterion is `met`. Quoted criteria are the ticket's wording. Unquoted ones are short forms
of it, so check the evidence against the ticket text, not against the short form.

| AC | Criterion | Status | Evidence / plan |
|---|---|---|---|
| 1 | Merged JaCoCo coverage report | met | `./mvnw verify` merges `target/jacoco.exec` (Surefire) and `target/jacoco-it.exec` (Failsafe) into `target/jacoco-merged.exec`, reports it to `target/site/jacoco/`, and `jacoco:check` fails the build below the BUNDLE minimums `jacoco.minimum.line-coverage` and `jacoco.minimum.branch-coverage`. Each is the measured ratio rounded down to 0.05 (2026-10-28: LINE 0.629 → 0.60, BRANCH 0.459 → 0.45); raise them when coverage grows. Generated API models and Micronaut's `$*Definition*`, `$*Introspection*` and `$*Intercepted*` classes are excluded from report and check, not from the agent. |
| 2 | Real PostgreSQL 16 in containers | met | `PostgresTestResource.POSTGRES_IMAGE` is `postgres:16`. Every database IT runs on it, apart from the two exceptions under Decisions, which use the same constant. |
| 3 | "Integration tests authenticate with real tokens from Testcontainers Keycloak" | met | `KeycloakAndPostgresTestResource` re-enables security and is the base of every authenticated IT, `PrivacyNoticeApiTestSupport` included. `KeycloakTestResource.accessToken(RealmPrincipal…)` obtains each token through a password grant against the imported realm. Per-test evidence: `KeycloakRoleMatrixIT.roleMatrixHolds`, `KeycloakKeyRotationIT`. |
| 4 | Containers shared across test classes | met | `PostgresTestResource` and `KeycloakTestResource` are static JVM-wide singletons. `FlywayConcurrentMigrationIT` and `DatasourceOutageReadinessIT` are the deliberate per-class exceptions listed under Decisions. |
| 5 | Database reset between test methods | met | `PostgresTestResource.resetSharedSchema` (`@AfterEach`, `final`) truncates every application table through `DatabaseReset`. `SchemaIsolationIT.nextMethodStartsEmpty` asserts that rows committed by one method are gone in the next. No IT on the shared bases runs `transactional = true`, and none seeds in `@BeforeAll`. |
| 6 | "No test makes a real outbound network call" | met (configuration) | `NoExternalHostIT.everyDialledUrlStaysLocal` walks every property source of a context built on `KeycloakAndPostgresTestResource` and asserts each absolute `http(s)`/`jdbc` URL names a loopback host or the Testcontainers Docker host. The criterion is about runtime traffic; this is a configuration check standing in for it. |
| 7 | Consent state-machine matrix | met | `ConsentStateMachineTest.everyPairIsPermittedOrRefusedAsTheTableSays`. |
| 8 | Endpoint × role (× ownership) authorization matrix | met | Endpoint × role: `EndpointRoleMatrixIT.roleMatrixHolds`, total through `everySpecifiedOperationIsCovered`; `KeycloakRoleMatrixIT.roleMatrixHolds`. Ownership (OWN/FOREIGN/ABSENT): `OwnershipMatrixIT.ownershipDecidesTheAnswer`, total over the id-addressed operations through `everyIdAddressedOperationHasARow`, and held to the documented statuses by `everyExpectedStatusIsDocumented`. |
| 9 | Token failure matrix covered by parameterized tests | met | `ConsentManagerTokenValidatorTest` has `@ParameterizedTest`s fed by the `refusedTokens` and `refusedHeaders` method sources, one case per failure; `TokenSignatureEnforcementTest` covers signature enforcement as well. Those cover no `kid`, embedded `jwk`/`jku`/`x5u` headers, an RS256 header over an EC key and an unrecognised `crit` against stubs. Against live Keycloak, `KeycloakTokenTamperIT.tamperedTokenIsUnauthorized` mutates a genuine realm token (payload claim, flipped signature bit, `alg: none`, HS256 keyed by the realm public key as DER and as PEM, `kid` removed or unpublished, `jwk`/`jku`/`x5u` headers) and expects a `401` problem each time. `PrincipalResolutionIT.unauthenticated` covers a USER token without its identifier claim over HTTP. |
| 10–12 | Concurrency: registration, grant, notice generation | met | Registration: `ParticipantUserRegistrationIT.eachStartingStateReachesItsOwnOutcome[CONCURRENT]` sends parallel HTTP POSTs for one identifier through `support/Race`. Every caller gets a 2xx and exactly one gets `CREATED`, and raw SQL (`support/RawSql`) finds one `users` row and one `user_participants` row. `UserServiceIT.concurrentFirstRequestsProvisionOnce` and `UserServiceIT.concurrentRegistrationsSettleOnOneRow` cover the same race at service level. Grant: `ConcurrentRegrantIT.racingGrantsLeaveOneConsentInForce` races both an already-`GRANTED` pair (supersede) and an ungranted pair. Raw SQL finds exactly one `GRANTED` row per (user, notice), each loser is a `409` or a clean supersede, and the event rows match the surviving consents. `ConsentGrantAtomicityIT` is retained. Notice generation: `PrivacyNoticeConcurrencyIT.concurrentGenerationYieldsExactlyOneActiveNotice` finds one `archived_at IS NULL` row through raw SQL. |
| 13 | "All eight security tests pass" | met | This row is the aggregate of the [Security tests](#security-tests) table and takes its status from that table. |
| 14 | Signing-key rotation at the identity provider | met | `KeycloakKeyRotationIT.rotatedSigningKeyIsPickedUpWithoutARestart`. |
| 15 | Flyway migrations "verified against an empty database once per suite" | met | The singleton `PostgresTestResource` container starts empty and is migrated at the first context start. `SchemaIsolationIT.everyMigrationAppliedOnce` asserts that its `flyway_schema_history` lists every file in `db/migration/` exactly once, and as successful. `DatabaseReset` never truncates that table. `FlywayConcurrentMigrationIT.concurrentReplicasApplyEachVersionOnce` additionally proves concurrent migration of an empty database, on its own containers. |
| 16 | No secret or personal data in logs | met | `LogLeakIT`, `KeyMaterialLeakIT`. `LogLeakIT` also exercises the bad-signature and unknown-issuer rejection paths. Participant identifiers are deliberately not forbidden, because `docs/security.md` ("Allowed as is") permits them and `ConsentStateChangeLog` logs them by design. |
| 17 | Branch coverage on security-critical classes | met | A second `jacoco:check` rule (`element` `CLASS`, `BRANCH` `MISSEDCOUNT` maximum 0) lists by `<includes>` `ConsentStateMachine`, `ConsentStatus`, `ClaimMapper`, `PrincipalResolutionFilter`, `PrincipalArgumentBinder(s)`, the three principals, `ConsentManagerTokenValidator`, `IssuerSignatureVerifier`, `UnsignedTokenRejector`, `CallerScope`, `CanonicalJsonSerializer`, `JwsConsentSigningService` and `SigningKeyStore`, each with its nested types. It fails `./mvnw verify` on one missed branch whatever the BUNDLE ratio, so deleting a test that alone covers a branch fails the build. No branch is excluded; log-level guards in `IssuerSignatureVerifier` were dropped instead (SLF4J's `{}` formatting is already lazy). The closing tests sit in `ClaimMapperTest`, `ConsentManagerTokenValidatorTest`, `IssuerSignatureVerifierTest` (no meter registry, absent issuer), `PrincipalResolutionIT` (acting role without its identifier, a foreign validator's authentication, a principal on an anonymous route, a token on a static resource) and `CallerScopeTest`. |
| 18 | Responses validated against the spec | met | `support/OpenApiResponseValidationFilter` checks every response the embedded server returns to a Micronaut test client against `target/openapi-bundle/openapi.yaml`: declared status, body schema and content type, `application/problem+json` included. Every operation declares a `default` error response, so a status below 400 must be named explicitly. A route or method the spec does not publish may only be refused with a 4xx, which the role-matrix and principal-resolution probes of unpublished routes rely on. Violations are collected in `ResponseViolations` and `PostgresTestResource.assertResponsesMatchSpec` (`@AfterEach`, `final`) fails the test. Requests are not validated. Exchanges with other hosts or ports (a loopback address counts as the server's own host), the test probe routes, and the service's non-spec origin routes (management endpoints, `*.yaml`, `/swagger-ui/**`) are skipped; `/.well-known/jwks.json` is validated. `OpenApiResponseValidationFilterTest` proves each rule, and `ResponseValidationWiringIT` proves real client exchanges reach the filter, error bodies included. The collector is a hard assertion, so a subclass outside a Micronaut context fails rather than skipping the check. See [Response validation coverage](#response-validation-coverage) for what is not covered. |
| 19 | Served spec equals the authored spec | met | `OpenApiServingIT.servesTheBundleUnchanged` compares the served document with `target/openapi-bundle/openapi.yaml`, the bundle of the authored spec. |
| 20 | Spec lint and breaking-change check as build steps | met | `SpecLintIT` (Spectral) and `SpecCompatibilityIT` (oasdiff) run inside `./mvnw verify`, which the `verify` job of `.github/workflows/test.yml` runs on every pull request (workflow `Test`) and on every push to `main` (called by workflow `Release`, `.github/workflows/release.yml`, before it publishes); a lint or breaking-change failure fails the job. Both start their tool as a pinned Docker image through Testcontainers and have no skip path, and the job's "Require the spec checks to have run" step additionally fails when either Failsafe report is missing or records a skipped case. The job uploads `target/site/jacoco/` as the `jacoco-report` artifact. |
| 21 | Spec and code agree on security | met | `SpecSecurityConsistencyTest`. |
| 22 | Deterministic suite | met | Ten consecutive `./mvnw -B clean verify` runs on one host (JDK 25, 2026-10-28) all passed with identical summaries: 2140 Surefire and 1263 Failsafe tests, no failures, errors or skips, and the same 3403 test-case names. Merged coverage was LINE 4920/7817 (0.629) and BRANCH 1350/2938 (0.4595) in nine runs. In one run, one more branch was covered (0.4598): the `closed` guard in `IdentityProviderRegistry.scheduleAttempt`, reached only when a discovery retry fires after the context closes. The swing is upward and one branch is worth 0.0003, so it cannot trip the 0.45 gate. The zero-miss classes did not vary. An earlier run of this step failed once on `ManagementEndpointSecurityIT` with `Address already in use`, because the probed management port was taken before Micronaut bound it. `Deployment.start` now re-probes a fresh port on a `BindException`, up to `MANAGEMENT_BIND_ATTEMPTS`, and the ten runs above include that fix. |

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
| 1 | met | `OwnershipMatrixIT.ownershipDecidesTheAnswer` over consents, receipts, lineage, lifecycle moves, listings, user lookup and participant-user links; a foreign record stays untouched and, where hidden, answers a body identical to the absent one. `EndpointRoleMatrixIT.aParticipantTokenNeverReachesAnotherRecord` for participant records. |
| 2 | met | `UserDirectoryIT`. |
| 3 | met | "Cannot read or act on another user's consent." Act: `ConsentLifecycleSurfaceIT.refusesAConsentBelongingToAnotherSubject`; `OwnershipMatrixIT` FOREIGN rows of revoke/refuse/re-confirm/resume. Read: `OwnershipMatrixIT` FOREIGN rows of `GET /consents/me/{id}` in every format; `ConsentQueryServiceIT.hidesAnotherSubjectsConsent` at service level. |
| 4 | met | `IdentitySubstitutionIT.substitutedIdentityIsRefusedOrIgnored`: every secured operation × admitted role, a foreign identity in the query or as an extra body member is refused or leaves the answer and the stored rows as without it. Each request without the substitution must first answer its pinned success status, the generation and contract-discovery rows included (a stubbed Contract Service serves their contracts), so no case passes on two matching failures; total through `everySecuredOperationHasARequest`. `EndpointRoleMatrixIT.noWriteRouteLetsTheCallerNameTheRecord`. |
| 5 | met | The "a key genuinely valid at another trusted issuer" case of `ConsentManagerTokenValidatorTest`'s `refusedTokens` source. Against live Keycloak, `CrossIssuerKeycloakIT.crossIssuerTokenIsUnauthorized` creates a second realm signing with a test-generated RSA key, trusts it as a second issuer, and refuses B's key and `kid` under A's `iss`. `ownIssuerIsAccepted` is the control. |
| 6 | met | `KeycloakKeyRotationIT`. |
| 7 | met | `IssuerSignatureVerifierTest.unknownKeyIdentifiersTriggerNoRefetch`. Against live Keycloak, `CrossIssuerKeycloakIT.crossIssuerTokensTriggerNoRefetch` fronts both realms' key sets with counting WireMock proxies, warms both caches (TTL pinned above the test's length), then sends repeated tokens claiming realm A under realm B's `kid`, which A does not publish. It asserts A's fetch count, the claimed issuer's, is unchanged. B's count is also unchanged, but B's warm cache bounds it, so it is a secondary check. |
| 8 | met | `LogLeakIT`, including the bad-signature and unknown-issuer rejection paths. |

### Matrices

| Matrix | Status | Evidence / plan |
|---|---|---|
| External service stubbing | met | `ContractServiceStub`, `CatalogStub`, `OidcDiscoveryStub`, each reset by its holder's `@AfterEach`. `ContractFixtureMatrixIT.yieldsTheExpectedOutcome` covers the six fixture cases (well-formed bilateral, well-formed ecosystem, missing optional sections, unresolvable offering, 500, timeout — the last split into ecosystem and bilateral) by status and resolution state. |
| Request validation | met | `RequestConstraintMatrixIT.answersAsTheSpecDeclares` crosses each constrained property of `GeneratePrivacyNotice`, `GenerateByContract`, `UserRegistration.email`, `ParticipantUserLinkUpdate`, `BulkUserRegistrationEntry`, `GrantConsent` and `ConsentRequest` with {valid, empty, oversized, malformed}. Bounds, patterns, formats and enums are read from the bundled spec. A valid body earns the route's success status, and a refusal is a `400` problem naming the property. The bulk route answers `207` for any well-formed batch, so its rows also read the entry's result: `BulkUserRegistrationEntry` drops `UserRegistration`'s meaning constraints and `UserService` enforces them per entry, so a value `UserRegistration` refuses (an empty `identifier` or `email`) must come back `REJECTED` with a reason naming the property, and any other accepted value must not. A kind the spec does not constrain is skipped. `ParticipantValidationIT` and `ParticipantUserRegistrationIT` cover the participant schemas and the `UserRegistration` lengths. `everyConstrainedRequestPropertyIsCovered` fails when a constrained property of any request-body schema has no row in the three. |
| Error mapping | met | `ProblemMappingTest.HandlerMatrix` drives each rendering site through its real handler: every `ApiException` subclass, `AuthorizationProblemHandler` (401 and 403), `ConstraintViolationProblemHandler` and `GlobalExceptionHandler`. Each row asserts the documented status and `ProblemType`. It is total over `ProblemType` (`everyProblemTypeIsProduced`) and over the compiled concrete `ApiException` subclasses (`everyApiExceptionHasARow`). Over HTTP, `ProblemResponseIT` adds the 502 shape and the 401 shape, the latter on a security-enabled server of its own. |
| Receipts | met | `ConsentReceiptServiceTest.refusesTamperingInEveryFormatAndAlgorithm` crosses {`receipt`, `receipt-kantara`} × {ES256, ES384, ES512, RS256, PS256} × every `SigningFixtures.Tamper`. Each cell renders the served body with a key of that algorithm and a retired key of the same algorithm published beside it. The JWS header must name the algorithm, and `ReceiptSignatures` must verify the untouched receipt. Then both the service's verifier and the outside verifier must refuse the tampered pair. The kid and alg tampers are re-signed by the active key over the untouched receipt, and the cell asserts that signature is genuine, so only the verifier's kid or alg binding refuses it; that covers RS256↔PS256 alg confusion. An EC key cannot sign under another curve's alg, so the EC alg cells only relabel the header. `JwsConsentSigningServiceTest.refusesTampering` runs the same `Tamper` list against the signer alone, and `ConsentReceiptIT` covers route × format over HTTP. |

## Participant notifications (ticket #94)

Every criterion is `met`. Criteria are short forms of the ticket's wording, so check the evidence
against the ticket text. The contract is recorded in ADR-0016 and the
[integrator guide](participant-notifications.md).

| AC | Criterion | Status | Evidence |
|---|---|---|---|
| 1 | A permission-changing move queues its notifications durably | met | `ParticipantNotificationEnqueueIT.enqueuesWhatTheMoveOwes` drives every write path (grant, a decision superseding a grant, revoke, terminate by each side, resume, request, refuse, re-confirm, cleanup, erasure, deregistration, `writer.move(…, EXPIRED, …)`) with no dispatcher running and asserts the exact `PENDING` rows. |
| 2 | The enqueue is atomic with the move | met | `ParticipantNotificationRollbackIT.receiptFailureRollsBackTheRows`: a replaced receipt issuer first sees the rows inside the transaction, then throws; afterwards no row exists and the consent is unchanged. |
| 3 | At-least-once delivery | met | `NotificationDispatcherIT.reclaimsAnExpiredLease` (a row whose holder died is re-claimed and delivered); `NotificationOutboxRepositoryIT.claimsOnlyDueRows` and `staleTokenIsFenced`. |
| 4 | Event types follow the move | met | `NotificationRecipientsTest.coversEveryPermittedMove`, `unattributed` and `attributed`, total over the state machine's permitted pairs plus creations; `ParticipantNotificationEnqueueIT.enqueuesWhatTheMoveOwes`; `ParticipantNotificationEnumParityTest.servedEnumMatchesDomain`. |
| 5 | Provider and consumer are notified, the terminator excepted | met | `NotificationRecipientsTest.attributed`, `sameParticipantOnBothSides`, `sameParticipantTerminatingItsOwn`; the recipient columns in `ParticipantNotificationEnqueueIT.enqueuesWhatTheMoveOwes`. |
| 6 | The payload is minimal | met | `ParticipantNotificationEnqueueIT.enqueuesWhatTheMoveOwes` asserts the payload keys and that no snapshot or notice content is present; `NotificationOutboxRepositoryIT.roundTripsThePayload`. |
| 7 | Notifications are signed and verifiable | met | `ParticipantWebhookClientTest.bodyIsTheCanonicalJsonTheSignatureCovers` and `signatureVerifiesOnlyAsItsOwnType` (`typ: consent-notification+jws`, refused as a receipt and the reverse); `ParticipantNotificationDeliveryIT.deliversAGrantAndARevokeAsTheRecipientAnswers` verifies against keys fetched from `GET /.well-known/jwks.json` with the independent `ReceiptSignatures`. |
| 8 | Failures retry with backoff | met | `NotificationDispatcherIT.serverErrorRetriesWithTheSameSignedBody` (same body and `eventId` on the retry); `NotificationBackoffTest.delayStaysInsideTheJitterWindow`. |
| 9 | Retries end in abandonment | met | `NotificationDispatcherIT.walksTheScheduleToAbandonment`; `NotificationBackoffTest.exhaustsAfterTheLastRetry`; `NotificationOutboxSweeperIT.abandonsAnAgedDueRow` and `NotificationOutboxRepositoryIT.agedRowsAreAbandonedNotClaimed` for the max-age backstop. |
| 10 | Responses are classified | met | `ParticipantWebhookClientTest.classifiesStatus` (`2xx` delivers, `3xx` and other `4xx` abandon, `408`, `429` and `5xx` retry), `refusedConnectionRetries`, `unansweredConnectRetriesAsConnectTimeout`, `slowResponseRetriesWithinTheReadTimeout`, `classifiesResolution`; `NotificationDispatcherIT.classifiesTheResponse` writes each class to the row. |
| 11 | No endpoint abandons without a call | met | `NotificationDispatcherIT.abandonsAnUnreachableRecipient` (no `consentNotification`, and a deregistered participant), asserting no HTTP call; `refusesAnInternalEndpoint` for an address the fence refuses. |
| 12 | A dead endpoint cannot starve others | met | `NotificationDispatcherIT.isolatesAHealthyParticipantFromStalledOnes`; `NotificationOutboxRepositoryIT.capsRowsPerParticipant`; `ParticipantWebhookClientTest.stalledHostHoldsOneResolverThread`; `NotificationConfigurationValidatorTest.refusesNamingTheSetting` (cap `<` concurrency, lease covers the batch). |
| 13 | Replicas never double-claim | met | `NotificationOutboxRepositoryIT.concurrentClaimsAreDisjoint` and `recheckDropsARowLeasedAfterTheSnapshot`; `NotificationReplicaConcurrencyIT.deliversEachEventToEachPartyExactlyOnce`. |
| 14 | Per-participant metrics | met | `NotificationDispatcherIT` asserts `NotificationMetrics` attempts and abandonment reasons per participant throughout, and `reportsQueueDepthPerParticipant` the queue-depth gauge. |
| 15 | Delivery history for the participant | met | `ParticipantNotificationHistoryIT` (`returnsOnlyTheCallersRowsNewestFirst`, `pagesThroughTheHistory`, `clampsAnOversizedPage`, `rendersOutcomesFromColumns`, `staysReadableAfterDeregistration`); the `NOTIFICATION_HISTORY` row of `EndpointRoleMatrixIT`. |
| 16 | End-to-end against a real recipient | met | `ParticipantNotificationDeliveryIT.deliversAGrantAndARevokeAsTheRecipientAnswers`, parameterized over recipient behaviour (200, 404, 503 then 200, timeout, no endpoint), triggered through the HTTP API; `NotificationSchedulingIT.scheduledTickDeliversAQueuedRow`. |
| 17 | Each event reaches each party exactly once across replicas | met | `NotificationReplicaConcurrencyIT.deliversEachEventToEachPartyExactlyOnce`; `NotificationOutboxRepositoryIT.staleTokenIsFenced` (a late outcome never overwrites another replica's). |

Erasure is covered beside these: `UserErasureIT.theOutboxForgetsTheErasedIdentifier` asserts no
payload keeps the erased identifier once rows turn terminal, and
`NotificationOutboxRepositoryIT.abandonForParticipantTouchesOnlyItsDueRows` and
`ParticipantNotificationEnqueueIT.deregistrationAbandonsTheDepartingParticipantsRows` the
deregistration sweep.

## Related documents

- [Security Architecture — Testing](security.md#testing): the security tests and what each pins.
- [API compatibility](api-compatibility.md): the breaking-change check against the published spec.
- [Participant notifications](participant-notifications.md): the contract the ticket #94 tests hold the service to.
- [ADR-0016](adr/0016-participant-notifications-use-a-transactional-outbox.md): the outbox design those tests exercise.
