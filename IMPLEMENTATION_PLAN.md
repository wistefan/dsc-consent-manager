# Implementation Plan: Consent Manager - Reimplementation - Testing

## Overview
Earlier tickets already built most of the suite: singleton Postgres 16 and Keycloak containers, WireMock contract
stubs, the 7×7 state-machine matrix, the endpoint × role matrix, key rotation against live Keycloak, log-leak
scans, Spectral, the breaking-change check, and the served-spec identity check. This ticket therefore **closes
gaps** against the 22 acceptance criteria rather than rebuilding the suite.

Ground rules for every step:
- Keep the existing `com.seamware.consentmanager` package-per-module layout and the existing `*Test`/`*IT` names.
  The ticket's `eu/prometheusx/.../{unit,integration,concurrency}` tree is illustrative only. Never move or
  rename an existing test.
- Existing helpers stand in for the ticket's `TokenFactory` (`KeycloakTestResource.accessToken`),
  `ContractServiceStubs` (`ContractServiceStub`) and `TestDataFactory` (`ConsentFixtures`,
  `PrivacyNoticeFixtures`).

**Environment pitfalls** (see `AGENTS.md`):
- Install JDK 25. The container ships JDK 21.
- `sudo chmod 666 /var/run/docker.sock` before any IT.
- Run `./mvnw spotless:apply` before committing.

## Steps

### Step 1: Testing guide and acceptance-criteria traceability
Create `docs/testing.md`. It holds a table with one row per acceptance criterion (1–22), each naming the test
class(es) that satisfy it. A criterion not yet met reads `planned — Step N`.

Seed it from the audit:

| AC | Status | Evidence / plan |
|---|---|---|
| 2, 4 | met | `PostgresTestResource` and `KeycloakTestResource` are static singletons, and the image is `postgres:16`. Two deliberate per-class exceptions start their own `PostgreSQLContainer`: `FlywayConcurrentMigrationIT` (each replica count must migrate an empty database) and `DatasourceOutageReadinessIT` (it stops its database, which would break every other IT). |
| 6 | met by inspection | No outbound host is configured outside loopback. `NoExternalHostIT` (Step 4) turns this into a test. |
| 7 | met | `ConsentStateMachineTest.everyPairIsPermittedOrRefusedAsTheTableSays`. |
| 9 | met | `ConsentManagerTokenValidatorTest.refusedTokens` / `refusedHeaders`, `TokenSignatureEnforcementTest`. |
| 14 | met | `KeycloakKeyRotationIT`. |
| 15 | met | `FlywayConcurrentMigrationIT`. |
| 16 | met | `LogLeakIT`, `KeyMaterialLeakIT`. |
| 19 | met | `OpenApiServingIT`, which compares the served document with `target/openapi-bundle/openapi.yaml`, the bundle of the authored spec. |
| 20 | partial | `SpecLintIT` and `SpecCompatibilityIT` exist. There is no CI workflow yet. |
| 21 | met | `SpecSecurityConsistencyTest`. |
| Security #2, #3, #5, #6, #7, #8 | met | `UserDirectoryIT`, `ConsentLifecycleSurfaceIT`, the "a key genuinely valid at another trusted issuer" case of `ConsentManagerTokenValidatorTest.refusedTokens`, `KeycloakKeyRotationIT`, `IssuerSignatureVerifierTest.unknownKeyIdentifiersTriggerNoRefetch`, `LogLeakIT`. |

Cite evidence by test class, method or case name, never by line number: Steps 8 and 11 add cases to these
files and line numbers would drift. Grep each cited name before writing it down.

Name the AC 4 exceptions in the row so that a later grep for `new PostgreSQLContainer` does not read as a
regression.

Also add:
- The conventions a contributor needs:
  - `*Test` means no container and runs under Surefire; `*IT` runs under Failsafe.
  - The singleton containers.
  - How to get a token per role.
  - Which base class to extend.
- A recorded decision that there is **no `logback-test.xml`**. Tests run under the production `logback.xml`
  on purpose, so the log-leak scan (AC 16) covers the shipped configuration.

`AGENTS.md` already points at this file.

**Acceptance:** the file exists, every AC has a row, and no code changes.

### Step 2: Make JaCoCo measure Java 25 and gate on it
`pom.xml` pins `jacoco.version` 0.8.15 with `jacoco.minimum.coverage` 0.00. Its comment says 0.8.15 cannot
instrument class file version 69.

- **Version.**
  - Bump to 0.8.16 (on Central).
  - Run `./mvnw verify`.
  - Prove `target/jacoco.exec` and `target/jacoco-it.exec` are non-empty and `target/site/jacoco/index.html`
    reports non-zero coverage.
  - If coverage is still empty, find the real cause before anything else. Likely candidates: Surefire/Failsafe
    not receiving `@{argLine}`, or the Micronaut parent overriding `argLine`.
- **Report excludes.** Move the `excludes` to the plugin-level `<configuration>` so they apply to `report` as
  well as `check`. Exclude `com/seamware/consentmanager/api/generated/**` and Micronaut's generated
  `**/$*Definition*`, `**/$*Introspection*` and `**/$*Intercepted*` classes.
- **Thresholds.** Replace the 0.00 with BUNDLE `LINE` and `BRANCH` minimums. Set each to the measured value
  rounded **down** to the nearest 0.05, as named properties `jacoco.minimum.line-coverage` and
  `jacoco.minimum.branch-coverage`. Delete the stale comment.
- **Failsafe goals.** Check `./mvnw help:effective-pom` to confirm Failsafe's `integration-test` **and** `verify`
  goals are bound. Bind them explicitly if the Micronaut parent does not.

**Acceptance:** `./mvnw verify` produces a non-empty merged report (AC 1), and lowering coverage below the
threshold fails the build. Update `docs/testing.md` row 1 and `AGENTS.md` (Tech Stack line).

### Step 3: Central schema reset between test methods
There is no shared reset today. ITs hand-delete rows: `removeCommittedRows` in about ten classes, and raw
`DELETE ... LIKE` in `ConsentQueryIndexUsageIT`. Rows left behind by one class can leak into the next.

- **`support/DatabaseReset`.** Add a small JDBC helper that connects straight to the singleton container,
  independent of the Micronaut context. It lists the `public` tables from `pg_tables`, excluding
  `flyway_schema_history`, and issues **one** `TRUNCATE <all> RESTART IDENTITY`. One statement over every table
  is FK-safe without `CASCADE` ordering. Cache the table list per JVM.
- **Base class hook.** Call it from an `@AfterEach` in `PostgresTestResource`; subclasses inherit it. This needs
  the container's JDBC URL and credentials exposed package-privately.
- **`@BeforeAll` seeders.** Convert classes that seed in `@BeforeAll` (`ConsentQueryIndexUsageIT`, `LogLeakIT`,
  and any other the grep finds) to `@BeforeEach` seeding, or to a documented, named opt-out that the reset
  honours. Prefer converting.
- **Ad-hoc cleanup.** Delete the now-redundant `removeCommittedRows` / `deleteAll` cleanups in the ITs listed in
  the audit (`ConsentReceiptIT`, `ConsentLifecycleSurfaceIT`, `ConsentLineageIT`, `ConsentValidityIT`,
  `ConsentRequestSurfaceIT`, `ConsentGrantIT`, `ConsentMetricsIT`, `PrivacyNoticeConcurrencyIT`,
  `PrivacyNoticeApiTestSupport`, `KeyMaterialLeakIT`).
- **Transactional ITs.** `ProblemResponseIT`, `ApiStatusControllerIT` and `ApplicationSmokeIT` run on the
  `@MicronautTest` default `transactional = true` and extend `PostgresTestResource`. Micronaut Test rolls the test
  transaction back only *after* `@AfterEach`, so a `TRUNCATE` (ACCESS EXCLUSIVE) on the reset's separate
  connection would wait on that transaction's locks forever; Postgres cannot see the JVM side of the wait.
  Convert these three to `transactional = false`: they act through HTTP, whose writes commit on the server's
  own connections, so rollback never isolated them anyway.
- **Fail loudly, never hang.** The reset connection runs `SET lock_timeout` (a named constant, a few seconds)
  before the `TRUNCATE`. Any remaining lock holder, such as a still-open async post-commit task from the
  previous test or a future transactional IT, then fails the test with a lock-timeout error naming the reset.
- **Canary: `support/SchemaIsolationIT`.** With `@TestMethodOrder`, method 1 writes a user, participant, notice
  and consent; method 2 asserts every table is empty. A second method asserts that `flyway_schema_history` lists
  every file in `db/migration/` as successful, which shows the suite's migration ran once on the empty
  container (AC 15).

**Acceptance:** AC 5 met; full `./mvnw verify` green, with no IT left on `transactional = true` that extends
`PostgresTestResource`. Document the reset and the lock timeout in `docs/testing.md`.

### Step 4: WireMock isolation and contract-fixture completeness
Stubs are never reset today. `PrivacyNoticeApiTestSupport` keeps one `ContractServiceStub` per class, and
`PrivacyNoticeConcurrencyIT` holds a static one. Request-count assertions can therefore see earlier tests'
traffic.

- Add `ContractServiceStub.reset()`, which wraps `resetAll()`, and the same on `CatalogStub` and
  `OidcDiscoveryStub` where they are per-class. Call it from `@AfterEach` in every holder. Any per-class
  baseline stub (e.g. a discovery document) must be re-registered in `@BeforeEach`.
- **No external hosts (AC 6): `support/NoExternalHostIT`.** Extends `KeycloakAndPostgresTestResource` so every
  test property provider contributes. It walks the context's `Environment` property sources and asserts every
  property value that parses as an absolute `http(s)`/`jdbc` URL names a loopback host or the Testcontainers
  Docker host (`DockerClientFactory.instance().dockerHostIpAddress()`, which is not loopback when the daemon is
  a sidecar). Update row 6 of `docs/testing.md` to cite it.
- `serveSlowly` only delays the ecosystem path. Add a bilateral timeout variant and cover it, alongside the six
  ticket fixtures (well-formed bilateral, well-formed ecosystem, missing optional sections, unresolvable offering,
  500, timeout).
  - Add one parameterized `ContractFixtureMatrixIT` in `api/` over an enum of those six cases → expected
    privacy-notice outcome (`201` or partial resolution / `502`), reusing `src/test/resources/contracts/*.json`.
  - Do **not** duplicate assertions that existing ITs already pin; the matrix asserts status and resolution state
    only.

**Acceptance:** stubs reset after every test, all six fixture cases are in one parameterized source, and
`NoExternalHostIT` passes (AC 6). Update the External Service Stubbing row and row 6 in `docs/testing.md`.

### Step 5: Response-schema validation in integration tests
Nothing validates responses against the spec today (AC 18).

- **Dependency.** Add test-scoped `com.atlassian.oai:swagger-request-validator-core`, version as a pom property.
  It must support OAS 3.0.3.
- **Filter: `support/OpenApiResponseValidationFilter`.** A `@ClientFilter` with `@ResponseFilter`, under
  `@Requires(env = "test")`, plus `@Requires` on a property that defaults to enabled in `application-test.yml`.
  - **Scope it to this service.** A bare `@ClientFilter` matches every Micronaut `HttpClient` in the context,
    including the application's own outbound clients (`ContractServiceClient`, `SelfDescriptionClient`,
    `IdentityProviderRegistry`'s discovery and JWKS fetches). The filter validates a response only when the
    request's scheme, host and port equal `EmbeddedServer.getURL()`; every other exchange passes through
    untouched and is never a violation.
  - It loads the bundled spec from the `openapi.bundle.file` system property. That property is already passed to
    Failsafe; hoist it to a shared property if needed.
  - It validates **responses only**: status declared, body matches the declared schema, content type including
    `application/problem+json`. Many tests send invalid requests on purpose, so requests are not validated.
  - The test API prefix is `""`, so match paths against the spec without a server prefix.
- **Surfacing violations.** Record them in a per-context collector that an `@AfterEach` in
  `PostgresTestResource` asserts empty. Throwing inside a client filter gets wrapped into an
  `HttpClientException` and is too easy to swallow.
- **Unmatched paths.** In tests, `endpoints.all.port: ~` and `api-prefix: ""` put the management endpoints and
  static resources on the same origin as the API, so the embedded server answers non-spec paths of its own. Two
  named allow-list constants skip them:
  - test-only probe routes (`KeycloakRoleMatrixIT`, `ProblemResponseIT`);
  - the service's own non-spec origin routes: the management endpoints (`/health/**`, `/metrics/**`,
    `/prometheus`), the spec document (`/openapi.yaml` and the bundled component `*.yaml`), and `/swagger-ui/**`.
    Derive this list from `application.yml`'s `static-resources` mappings and the enabled management endpoints,
    not from guesses; `ManagementEndpointSecurityIT.routingMatrix` is the reference.

  Any other unmatched path on the embedded server is a violation. `/.well-known/jwks.json` is a spec operation
  and is validated, not allow-listed.
- **Coverage gap.** Two kinds of IT are not validated; list both in `docs/testing.md`:
  - ITs that use `java.net.http` directly;
  - ITs that do not extend `PostgresTestResource` and so never run the collector check (today
    `ContractCacheIT`, `SelfDescriptionCacheIT`, `SmtpUserNotificationServiceIT`,
    `ManagementEndpointSecurityIT`, `DatasourceOutageReadinessIT`; re-grep when writing the doc).
- **Drift.** Fix any drift the filter reveals with the spec as the source of truth. A code-side fix is preferred;
  a spec fix must stay non-breaking (`SpecCompatibilityIT`).
- **Proof test.** Add `support/OpenApiResponseValidationFilterTest`, a unit test showing that:
  - a non-conforming body and an undeclared status are each reported;
  - a path on either allow-list is skipped, and a path on neither list is still reported;
  - a response from a foreign host is not validated.

**Acceptance:** AC 18; full suite green with the filter on.

### Step 6: Complete the concurrency suites
Existing tests:
- `UserServiceIT.concurrentFirstRequestsProvisionOnce` races JIT provisioning, and
  `UserServiceIT.concurrentRegistrationsSettleOnOneRow` races `registerForParticipant` at service level.
- `ConsentGrantAtomicityIT` races two grants.
- `PrivacyNoticeConcurrencyIT` races notice generation.

To add:
- **`support/Race`.** A small helper that runs N callables behind a `CyclicBarrier` on a fixed pool and returns
  their outcomes. Use it in the new tests only; do not refactor the existing ones.
- **Concurrent registration over HTTP.** In `api/ParticipantUserRegistrationIT`, add a `CONCURRENT` member to
  the existing registration-scenario enum, so the parameterized source covers all four entry states (unknown,
  known unlinked, known linked, concurrent).
  - N parallel POSTs for one identifier (N a named constant ≥ 8).
  - Assert every caller gets a 2xx, exactly one says `CREATED`, and raw SQL shows one `users` row and one
    `user_participants` row.
- **Concurrent re-grant: `service/ConcurrentRegrantIT`.** Race N threads to re-grant a (user, notice) pair that
  is **already `GRANTED`** (the supersede path), and N threads to grant a pair that is not yet granted.
  - Assert with raw SQL that `count(*) WHERE status = 'GRANTED'` per (user, notice) equals 1, matching the
    `uq_consents_granted_per_user_notice` predicate.
  - Every loser gets `409` or a clean supersede, and the event rows are consistent with the surviving consents.
- **Notice generation.** Add a raw-SQL `archived_at IS NULL` count of 1 to `PrivacyNoticeConcurrencyIT`.

**Acceptance:** AC 10–12 are asserted against final database state through raw SQL.

### Step 7: Ownership dimension of the authorization matrix
`EndpointRoleMatrixIT` covers endpoint × role. Ownership is pinned only for participant records, so security
tests #1 and #4 are partial.

- **Keycloak helper.** Add `KeycloakTestResource.createPrincipal(RealmPrincipal role, String identifier)`. It
  uses the existing admin REST client to create a uniquely named realm user with the role and identifier
  attribute, and returns a token supplier. This creates new users; the imported realm's fixed users are never
  modified.
- **`api/OwnershipMatrixIT`.** A `@ParameterizedTest` over (endpoint × caller × ownership {OWN, FOREIGN,
  ABSENT}). It covers:
  - every route addressing a consent: get, receipt formats, lineage, validity, revoke, terminate, resume and the
    participant listings;
  - users: `GET /users/{identifier}` and participant-user links.

  Each row expects 200/204, 403 or 404, matching the status the spec documents for that operation. A
  participant probing an unlinked user must get a 404 body identical to the absent case.
- **Totality.** The test asserts every operation with an id-bearing path parameter has an ownership row, so a new
  route cannot skip the matrix.
- **The 403/404 asymmetry is documented and stays.** Terminating a consent that names the calling participant
  on neither side returns `403`, while a foreign *user* reading it gets `404`. The `terminateConsent`
  description in `api/openapi.yaml` specifies the `403` deliberately, with its rationale, and
  `ConsentLifecycleService.requireParty` implements it. `OwnershipMatrixIT` expects `403` for the FOREIGN
  terminate row. Do **not** change the code; add a short note to `docs/security.md` that points at the spec text.
- **`api/IdentitySubstitutionIT` (security #4).**
  - For every secured operation, inject a foreign user or participant identifier into the query string, and into
    the request body as an extra member (`userId`, `participantId`, `identifier`, `subject`).
  - Assert the request is refused (400), or the outcome is attributed to the token's principal: response body
    plus a DB read.
  - Drive the operation list from the parsed spec, the way `EndpointRoleMatrixIT` does.

**Acceptance:** AC 8 (with ownership), security tests #1–#4. Raise no operation count, since no operation is
added.

### Step 8: Live-IdP token-validation and log-leak completion
Today every negative token case is forged against WireMock stubs. Add the live-container counterparts and the
missing cases.

- **`security/KeycloakTokenTamperIT`.** Parameterized over a real Keycloak token mutated as follows:
  - payload claim changed;
  - signature byte flipped;
  - `alg` rewritten to `none`;
  - `alg` rewritten to `HS256` and re-signed with the realm's public key bytes;
  - `kid` removed;
  - `kid` set to an unpublished value;
  - an embedded `jwk`, `jku` or `x5u` header pointing at an attacker key.

  Each case expects `401` with the problem body.
- **Unit cases.** Add the missing cases to `ConsentManagerTokenValidatorTest.refusedTokens` / `refusedHeaders`:
  - no `kid`;
  - `jwk`/`jku`/`x5u` headers;
  - an RS256 header over an EC key;
  - an unrecognised `crit` header.

  Add to `PrincipalResolutionIT` the HTTP case of a USER token missing its identifier claim.
- **`security/CrossIssuerKeycloakIT`.**
  - In `getProperties()`, before the context starts, create a second realm with a unique name through the admin
    REST API. Import into it an RSA key pair generated by the test, so the test holds the private key, and
    register the realm as a second trusted issuer.
  - Assert three outcomes:
    - a token signed by realm B's key, carrying B's `kid` but claiming realm A's `iss`, gets `401`;
    - the same key and `kid` with B's `iss` gets `200`;
    - repeating the cross-issuer token M times (a named constant) leaves B's JWKS endpoint request count bounded
      (security #5 and #7 against a live IdP).

  If importing a key into Keycloak proves infeasible, document why in `docs/testing.md` and keep the stub-based
  coverage.
- **`LogLeakIT`.** Add the bad-signature and unknown-issuer rejection paths to the exercised scenarios.
  *(Plan change during implementation:* the participant identifier is **not** added to the forbidden
  values. `docs/security.md` ("Allowed as is") permits it, since it names an organisation, and
  `ConsentStateChangeLog`, the ADR-0015 notification seam, logs provider and consumer at INFO by design.)

**Acceptance:** AC 9 against both stubs and live Keycloak; security #5, #7 and #8 strengthened.

### Step 9: Request-validation and error-mapping matrices
- **`api/RequestConstraintMatrixIT`.**
  - One `@ParameterizedTest` over (constrained request property × {valid, empty, oversized, malformed}) → `201`/
    `200` or `400` with the field named in the problem body.
  - Rows cover the gaps the audit found: `GeneratePrivacyNotice` (`contractUri`, `dataProvider`, `dataConsumer`),
    `GenerateByContract`, the `UserRegistration` email format, the `ParticipantUserLinkUpdate.localIdentifier`
    maxLength, `GrantConsent` (uuid format, `decision` enum), the `ConsentRequest.privacyNoticeId` uuid, and the
    `BulkUserRegistrationEntry` email, firstName and lastName maxLengths.
  - Bounds come from the parsed spec (min/maxLength, pattern, format, enum), not literals, so they cannot drift.
  - A totality test asserts that every constrained property of every request-body schema in the spec has a row
    here or in an existing matrix (`ParticipantValidationIT`, `ParticipantUserRegistrationIT`). The covered set is
    a named constant listing those classes' fields.
- **`error/ProblemMappingTest`.** Add a parameterized row per handler: each `ApiException` subclass,
  `AuthorizationProblemHandler` (401 and 403), `ConstraintViolationProblemHandler` and `GlobalExceptionHandler`
  → documented status and `ProblemType`. Add a totality check that every `ProblemType` constant is produced by
  some row.
- **`error/ProblemResponseIT`.** Add the 401 and 502 problem shapes.

**Acceptance:** the Validation and Error-mapping matrices in the ticket are covered and total.

### Step 10: Receipt format × algorithm × tamper matrix
`JwsConsentSigningServiceTest` crosses algorithm × tamper, and `ConsentReceiptIT` crosses route × format on one
key. Nothing crosses all three.

- In `service/ConsentReceiptServiceTest`, add a unit `@ParameterizedTest` over {`receipt`, `receipt-kantara`}
  × {ES256, ES384, ES512, RS256, PS256} × the tamper cases already enumerated in `JwsConsentSigningServiceTest`.
  - Build a `JwsConsentSigningService` over a `SigningKeyStore` per algorithm via `signing/SigningFixtures`.
  - Render the receipt, verify it with `support/ReceiptSignatures`, then assert each tamper is refused.
  - Extract the tamper enum into `signing/SigningFixtures` so both tests share one list rather than copying it.
- No new IT: a context per algorithm would add cost without coverage.

**Acceptance:** the ticket's Receipts matrix row is met.

### Step 11: Branch-coverage obligations on the security-critical classes
Add per-class JaCoCo `check` rules (`element` `CLASS`, `BRANCH` `MISSEDCOUNT` maximum 0) on:

| Area | Classes |
|---|---|
| State machine | `service.ConsentStateMachine`, `domain.ConsentStatus` |
| Claim mapping | `security.ClaimMapper` |
| Authorization | `security.PrincipalResolutionFilter`, `security.PrincipalArgumentBinder(s)`, `security.ParticipantPrincipal`, `security.UserPrincipal`, `security.CatalogPrincipal`, `security.ConsentManagerTokenValidator`, `security.IssuerSignatureVerifier`, `security.UnsignedTokenRejector`, `service.CallerScope` |
| Signing | `signing.CanonicalJsonSerializer`, `signing.JwsConsentSigningService`, `signing.SigningKeyStore` |

- **Configuration.** Use `<includes>` per rule, so the obligation fails the build regardless of the bundle
  percentage.
- **Closing gaps.** Run `./mvnw verify` and read the per-class report. Close every missed branch with a
  (parameterized) test in the matching existing test class.
- **Unreachable branches.** If a branch is genuinely unreachable, simplify the code rather than exclude it. Any
  exclusion must be justified in a comment beside the rule.
- **Bundle thresholds.** Re-measure and raise the bundle thresholds from Step 2 to the new floor, rounded down
  to 0.05.

**Acceptance:** AC 17; deleting any test that covers one of these branches fails `./mvnw verify`.

### Step 12: CI build step and determinism run
- **CI workflow.** Add `.github/workflows/build.yml`: checkout, Temurin 25 via `actions/setup-java`, Maven cache,
  `./mvnw -B verify`, and upload of `target/site/jacoco/`. Spectral lint (`SpecLintIT`) and the breaking-change
  check (`SpecCompatibilityIT`) run inside `verify` and fail the job, which makes them build steps (AC 20).
  - If either IT skips itself when its tool is absent (check for `assumeTrue`), make the workflow provide the tool
    so the CI run cannot skip silently.
- **Determinism.** Run `./mvnw verify` repeatedly, at least ten times in total within the step's budget (AC 22),
  and diff the Surefire/Failsafe summaries. Fix any flaky test found:
  - prefer `support/Await` over sleeps;
  - use unique identifiers per test;
  - do not raise timeouts as a fix.

  Record the run count and outcome in `docs/testing.md`.
- **Finalise `docs/testing.md`.** Every AC row reads `met` with its evidence, and no `planned` rows remain.

**Acceptance:** all 22 acceptance criteria are traceable to passing tests or build steps.
