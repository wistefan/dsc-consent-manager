# Implementation Plan: Consent Manager - Reimplementation - API Docs and Cross-Cutting Infrastructure

## Overview
Most of the ticket is already on `main`: the hand-authored spec, generated `Abstract*Controller`s,
shared `ProblemDetail`/401/403, separate liveness and readiness groups, JSON logging, Prometheus,
and WireMock-only test doubles. This plan fills the remaining gaps. They are correlation IDs and
environment-aware problem details, log hygiene, consent-transition metrics, non-API routes at
the origin root with switchable metrics authentication, a bundled spec served byte-for-byte,
Spectral lint, breaking-change detection, local WireMock dev stubs, and a final code-hygiene
pass (streams over loops, terse Javadoc).

### Where this plan departs from the ticket text (repo conventions win)
- **Paths stay inline in `api/openapi.yaml`.** `SpecReferenceResolutionTest` fails the build on an
  externalised path item, so there is no `api/paths/`. Shared parameters may go to
  `api/components/parameters/` if a step needs them.
- **Controllers extend generated `Abstract*Controller`s** instead of implementing interfaces. The
  compile-time drift guarantee is the same.
- **Package and model.** The package is `com.seamware.consentmanager`. `ProblemDetail` stays a
  generated model. New classes go in a new `observability/` package.
- **"Fails in CI" means "fails `./mvnw verify`".** The repo has no CI. Spectral and oasdiff run
  as Testcontainers tests, which already need Docker, so no Node toolchain enters the build.
- **"Production" is the base config.** `application.yml` is production-safe and the `dev`
  environment relaxes it, which matches the existing `details-visible` split.
- **`GET /metrics` is Micronaut's Micrometer endpoint** (meter names and values). The Prometheus
  scrape format stays at `GET /prometheus`. Both are on the management port.
- **The oasdiff baseline** is the bundled spec as it stands when Step 7 lands, committed under
  `api/baseline/`. There is no released spec to compare against yet.
- **No Flyway readiness indicator.** Migration runs synchronously at startup, so a failed or
  pending migration aborts the process. A running instance can never report it, and the existing
  JDBC indicator already covers the database half of readiness.
- **Metrics authentication is switchable in every environment.** It is not a dev-only
  relaxation. Micronaut 5.2.8 has one management port for all endpoints (`endpoints.all.port`)
  and no per-endpoint port, so health and metrics share `MANAGEMENT_PORT`. That port is already a
  separate listener from the API port.

## Steps

### Step 1: Correlation ID filter and environment-aware problem details
Add `observability/CorrelationIdFilter` (`@ServerFilter(Filter.MATCH_ALL_PATTERN)`), ordered ahead
of the security filter so 401/403 responses carry the ID too.
- **Taking or generating the ID.** Read the inbound `X-Request-ID`. Accept it only if it matches
  a named charset/length constant (this prevents log injection). Otherwise generate one with
  `UuidGenerator`.
- **Where the ID goes.** Store it in the request attributes and in the SLF4J MDC under
  `correlationId`. It must survive the hop to the blocking executor: verify Micronaut's
  `PropagatedContext`/MDC propagation, or install it explicitly. Echo it in the `X-Request-ID`
  response header and clear the MDC when the request ends.
- **Spec changes.** Add an optional `correlationId` (string) to
  `api/components/schemas/ProblemDetail.yaml`. Add an optional `errors` array of
  `{field, message}` (new `ProblemFieldError` schema). **Gotcha:** `items` that `$ref` a named model
  degrade to `List<Object>` across files, so declare `ProblemFieldError` where the generated type
  stays `List<ProblemFieldError>`. Assert that type in a test. Neither property is `nullable`.
- **Filling the new properties.** Set `correlationId` on every problem detail in one place
  (`ProblemErrorResponseProcessor`/`ProblemType.toProblemDetail` callers).
  `ConstraintViolationProblemHandler` fills `errors` with **every** violation, sorted. It also keeps
  the joined `detail`.
- **Exposing 500 messages outside production.** Add `consent-manager.errors.expose-exception-detail`
  (env `ERRORS_EXPOSE_EXCEPTION_DETAIL`). It defaults to `false` in `application.yml` and is `true`
  in `application-dev.yml`. Document the setting in `.env.sample` and `application.yml`.
  - **One helper, three sites.** Three places substitute `ProblemType.SERVER_ERROR_DETAIL` today:
    `GlobalExceptionHandler.handle`, the 5xx branch of `ApiExceptionHandler.handle` (which includes
    the 502 `UpstreamServiceException`), and `ProblemErrorResponseProcessor.processResponse`.
    Replace all three with one `error/ServerErrorDetail` singleton,
    `String detailFor(@Nullable Throwable)`.
  - **Behaviour.** When the flag is false, the helper returns `SERVER_ERROR_DETAIL`. When it is
    true, it returns the exception message, falling back to `SERVER_ERROR_DETAIL` when there is no
    message. The stack trace never reaches the response.
- **Tests.**
  - Unit `CorrelationIdFilterTest`, parameterized: absent, valid, too long, and illegal-charset
    header.
  - Extend `ProblemMappingTest`/`ProblemResponseIT`, parameterized over every exception type,
    asserting status, `correlationId` equal to the response header, and the inbound ID echoed.
  - A two-violation payload lists both fields in `errors`.
  - Both values of the expose flag produce the right body, parameterized over the three sites: an
    unhandled exception (500), a 502 `UpstreamServiceException`, and a framework-raised 500
    reaching `ProblemErrorResponseProcessor`.
  - A captured log line carries the same `correlationId` as the response.
- **Files.** `observability/CorrelationIdFilter.java`, `error/ServerErrorDetail.java` and the
  `error/*` handlers, `ProblemDetail.yaml`
  (+ `ProblemFieldError`), `ConsentManagerConfiguration` (nested `Errors`), the yml files,
  `.env.sample`, and the tests.
- **Acceptance.** ACs 18, 20, 22, 24, 25 hold. `./mvnw verify` is green.

### Step 2: Log hygiene: no tokens, key material, or personal identifiers above DEBUG
- **Pseudonymising helper.** Add the `observability/LogPseudonymizer` singleton with
  `String pseudonym(String identifier)`. It returns HMAC-SHA-256 under a configured secret, as
  hex, truncated to a named length constant. A plain SHA-256 is not enough: anyone with a list of
  candidate emails or DIDs could reverse it, and it would stay linkable after an ADR-0007 erasure.
  - **Key.** The key is `consent-manager.logging.pseudonym-key` (env `LOG_PSEUDONYM_KEY`). Startup
    fails when the key is missing or shorter than a named minimum. `application-dev.yml` and
    `application-test.yml` set fixed non-secret keys. Document it in `.env.sample` and
    `application.yml`. Pseudonyms stay stable across restarts as long as the key does.
  - **Use.** Use it for correlation at INFO and above. No message quotes the key.
- **Fix the known offenders** (verify the list with a grep first):
  - `GlobalExceptionHandler`, `ApiExceptionHandler`, `ProblemErrorResponseProcessor`: these log
    `request.getUri()`, which contains path identifiers and query strings. Log the matched route
    template (`UriRouteMatch` / `HttpAttributes.URI_TEMPLATE`) and the method instead.
  - The same three handlers also log the exception message and the throwable at ERROR.
    `ApiExceptionHandler` passes `exception.getMessage()` plus the exception, and the other two
    pass the root cause, whose message chain the encoder prints. Messages in this codebase are
    built from identifiers (`UNKNOWN_SUBJECT.formatted(userIdentifier, ...)` in `ConsentService`).
    An unhandled Postgres unique violation reads `Key (identifier)=(alice@example.com) already
    exists`. So the route-template change alone does not satisfy AC 19.
    - **At ERROR**, log only the method, the route template, the problem type or status, the
      exception class chain (class names only), and the top stack frame of the root cause as
      `class.method:line`. The `correlationId` arrives through the MDC. Build this summary in one
      `observability/FailureSummary` helper that all three handlers call.
    - **At DEBUG**, log the message and the full throwable under the same `correlationId`, so an
      operator who raises `com.seamware.consentmanager.error` to DEBUG gets the complete chain.
    - **Why not keep the throwable at ERROR.** Its message chain is free text from drivers and
      libraries, so no allow-list can vouch for it. The class chain and top frame are enough to
      find the failing code, and the correlation ID links the request.
  - `UserService` bulk-entry failure (logs the external `identifier`): pseudonymise it, or move
    it to DEBUG.
  - `SmtpUserNotificationService` failure line: it uses the opaque `userId`; keep it, but never
    log the address.
  - `IssuerSignatureVerifier` `kid` logging: a key ID is not key material. Keep it and note why.

  The opaque internal `userId` UUID and participant (legal-person) identifiers remain allowed,
  because ADR-0007's reasoning applies. Record that decision in `docs/security.md`.
- **Leak test.** Add a `LogLeakIT` modelled on `api/KeyMaterialLeakIT` (it attaches a
  `ListAppender` to the root logger and scans formatted message, arguments, MDC and throwable
  chain).
  - **Scenario:** an authenticated end-to-end run. Register a user with a sentinel identifier,
    email and name. Grant, revoke and erase a consent. Send a request with a malformed bearer
    token, and a token in a query string.
  - **Forced failures** come from a test-only `@Replaces` bean behind one route. It throws an
    unhandled `DataAccessException` whose message *and* cause both carry the sentinel identifier
    and email, which drives the throwable-chain scan against a personal identifier. A second case
    throws an `UpstreamServiceException` whose message carries the sentinel (5xx `ApiException`
    branch).
  - **Assertion:** no event at INFO or above contains the bearer token (whole, or any segment of
    length ≥ a named constant), any private JWK member, the pseudonym key, or the sentinel
    identifier, email or name. Parameterize over the forbidden sentinels. The test also asserts that
    each forced failure did produce its ERROR summary line, so a silenced handler cannot pass.
- **Tests.** Add `LogPseudonymizerTest`, parameterized:
  - the same key and input give the same pseudonym;
  - a different key gives a different pseudonym;
  - the output has the named length;
  - a missing or short key fails construction.
- **Acceptance.** AC 19 holds. Every offending statement is fixed, and `./mvnw verify` is green.

### Step 3: Consent-transition metrics
- **Transition counter.** Add `observability/ConsentMetrics`, wrapping the `MeterRegistry`, with
  a counter `consentmanager.consent.transitions` tagged `from` and `to` (`none` for creation). Tag
  values come from `ConsentStatus` names, and metric and tag names are constants.
- **Where to count.** Count in `ConsentStateChangeLog.recorded(held, moved, event)`, the one
  post-commit seam that every `ConsentLifecycleService` move passes through, collateral moves
  included. It already carries both statuses.
  - `ConsentService.request` and `ConsentService.decide` do not pass through that seam today, and
    re-wiring them is not enough. `ConsentWriter.decide` makes up to three kinds of move in one
    transaction:
    - `revokeOpenGrant` moves the open grant GRANTED → REVOKED, and only its id is kept;
    - `closeRedundant` moves extra PENDING rows to TERMINATED, and its result is discarded;
    - then it either answers the adopted PENDING row in place or creates a row.

    It returns a single `RecordedConsent`, which carries neither the collateral moves nor the
    status the answered row held. The following changes are required.
  - **Writer result.** Add a `service/ConsentWrite(RecordedConsent primary,
    Optional<ConsentStatus> held, List<ConsentMove> collateral)` record. Its compact constructor
    enforces `held.isEmpty() == primary.created()`. `ConsentWriter.decide` and `request` return it:
    - `decide` collects the revoked grant and every `closeRedundant` move into `collateral`, as
      `resume` already does, in the order they were written;
    - `decide` keeps answering through `answer(...)`, which sets `parentConsentId` to the
      superseded grant and freezes the new snapshot *before* `factory.transition`. Routing it
      through `moved(...)` would drop both. `decide` captures `held = unanswered.getStatus()`
      from the row read under the lock (always `PENDING`, since `unanswered` is filtered on it)
      and wraps the result as `new ConsentMove(held, answer(...))`;
    - `request` returns an empty `collateral` and an empty `held`.

    `ConsentService` unpacks `primary` exactly where it uses the `RecordedConsent` today, so the
    rendered responses and the notification call are unchanged.
  - **Seam for creations.** `recorded(@NonNull ConsentStatus held, ...)` keeps its signature for
    moves. A new `created(ConsentDetails, ConsentEvent)` covers creations, so no bare null is
    passed. Both delegate to one private method:
    - for a creation, the JSON line *omits* `heldStatus` rather than emitting `null`, and the
      message reads `none -> PENDING`;
    - the counter is incremented in that same private method, with `from=none`.
  - **One reporting path.** Lift `ConsentLifecycleService.report(ConsentMove)` into a shared
    `ConsentReporter` bean that both services call. It also gets
    `report(ConsentWrite)`, which reports the collateral moves first and then the primary. That
    keeps a single list of call sites.
  - **Bulk moves: erasure and deregistration.** `UserService.erase` moves GRANTED → REVOKED and
    PENDING/DRAFT → TERMINATED, and `ParticipantService.deregister` moves PENDING/DRAFT →
    TERMINATED. Both go through `ConsentFactory` and `consents.updateAll`, not through
    `ConsentWriter`, so neither reaches the seam today. Their private `close`/`terminateUnanswered`
    run inside the `@Transactional` method (self-invoked), so counting there would count moves
    that are then rolled back.
    - **Lock the rows first.** Today both read unlocked (`findByUserIdAndStatusIn`,
      `findByProviderIdAndStatusIn`/`findByConsumerIdAndStatusIn`). Deregistration locks only the
      participant row, and erasure locks nothing. `ConsentMove.held` is documented as read under
      the row lock, so add locking reads and use them instead:
      - `ConsentRepository.findByUserIdAndStatusInForUpdate` for erasure. `erase` takes **one**
        locked read over GRANTED ∪ `UNANSWERED_STATUSES` and partitions it by held status into
        revocations and terminations, still written as two batched updates. Two separate reads
        would let a concurrent `decide` grant a PENDING row between them and leave it standing.
      - `findNamingParticipantForUpdate` for deregistration: one native query,
        `(provider_id = :id OR consumer_id = :id) AND status IN (:statuses) ... FOR UPDATE`. A
        self-dealing consent comes back once, so `naming`'s de-duplication goes away.
      - Both lock in the order the writer's `findByUserIdAndPrivacyNoticeIdForUpdate` already uses
        (`ORDER BY created_at DESC, id DESC`), so all consent locks are taken in one order. A
        deadlock Postgres still detects rolls back one transaction, which then reports nothing.
      - Under READ COMMITTED, `FOR UPDATE` re-checks the status predicate on a row a concurrent
        transaction committed meanwhile. A row that a concurrent `decide` moved PENDING → GRANTED
        is therefore either read as GRANTED (erasure revokes it) or not returned at all
        (deregistration skips it). It is never overwritten from a stale PENDING. This also closes
        the lost-update race the unlocked reads have today. Rows *inserted* concurrently are not
        locked by either read; that race is pre-existing, unchanged, and out of scope.
    - `close` and `terminateUnanswered` return `List<ConsentMove>`, each with the status the row
      held under that lock, instead of an `int`. `ErasureResult` and `DeregistrationResult` gain
      a `List<ConsentMove> moves` component. Their existing counts become derived from it (or are
      checked against it in the compact constructor), so the published summary is unchanged.
    - `UserController.eraseCurrentUser` and `ParticipantController.deregisterCurrentParticipant`
      hand `result.moves()` to `ConsentReporter.report(List<ConsentMove>)` after the
      transactional call has returned, so only committed moves are counted and logged.
    - That reporter method resolves the provider and consumer identifiers once per distinct
      participant rather than per row, so a long-standing subject's erasure does not cost two
      lookups per consent. The log line still names the subject by opaque `userId` only, which
      ADR-0007 requires once the record is pseudonymised.
  - Never count inside the transaction. The lost-race 409 path counts nothing, because the
    rollback means no `ConsentWrite` is ever returned. A refused erasure or deregistration (for
    example, deregistration while a grant is open) returns no result and counts nothing.
- **Hikari metrics.** Confirm `hikaricp.*` meters are bound. If they are not, enable the
  Micronaut Micrometer JDBC/Hikari binder in `application.yml`.
- **Tests.**
  - `ConsentMetricsIT`: parameterized over every edge that some HTTP route can make, driven
    through that route. That covers creation (`request`, and `decide` with no open request), the
    decision path (`decide`), the lifecycle routes, erasure and deregistration, including the
    collateral moves. Each committed move increments exactly its `(from,to)` counter. A refused
    move and a lost race increment nothing.
    - **No row creates a DRAFT or an EXPIRED today.** Nothing in `src/main` creates a DRAFT row
      or moves a consent to EXPIRED, so edges out of DRAFT are driven on a row seeded through the
      repository, and only where a route accepts DRAFT (the lifecycle routes, erasure and
      deregistration).
    - **Edges no route makes** are listed in a named `NOT_YET_REACHABLE` constant in the IT. It
      holds GRANTED → EXPIRED only. DRAFT → REFUSED is reachable through the subject's refuse route
      on a seeded DRAFT (`ConsentLifecycleService.refuse` accepts PENDING and DRAFT), and
      GRANTED → TERMINATED through the participant terminate route (`terminate` accepts GRANTED and
      the unanswered statuses). Both get driven rows.
    - **Coverage guard.** A test in the same class asserts that the driven edges and
      `NOT_YET_REACHABLE` are disjoint and together equal every edge in the `ConsentStateMachine`
      table, plus the `none` creations from `CREATABLE`. This proves the table is covered. It does
      **not** prove a listed edge is unreachable: an edge wrongly listed is simply never driven.
      Keep the list to edges with no code path, and justify each entry in a comment.

    Explicit rows:
    - a superseding grant (a decision against an open grant): GRANTED→REVOKED once, plus the
      primary move;
    - a redundant-pending close: PENDING→TERMINATED once per extra row. Two open requests arise
      only from the race `ConsentWriter.request` documents, so the extra PENDING is seeded through
      the repository and the decision is then made over HTTP;
    - a creation through `request`: `none`→PENDING;
    - a refusal over the refuse route of a seeded DRAFT: DRAFT→REFUSED;
    - a termination over the participant terminate route of a grant: GRANTED→TERMINATED;
    - an erasure over HTTP for a subject holding a grant, a PENDING request and a seeded DRAFT:
      GRANTED→REVOKED, PENDING→TERMINATED and DRAFT→TERMINATED, once each;
    - a deregistration over HTTP for a participant named by a PENDING request and a seeded DRAFT:
      PENDING→TERMINATED and DRAFT→TERMINATED, once each. A deregistration refused while a grant
      is open counts nothing.
  - `ConsentStateChangeLogTest`: a creation's JSON line has no `heldStatus` field, and a move's line
    still has it.
  - `UserServiceTest`/`ParticipantServiceTest` (or the existing erasure and deregistration ITs):
    the returned `moves` match the published counts, and each carries the status it was read in.
    A concurrency IT (two racers released by a `CyclicBarrier`, as `PrivacyNoticeConcurrencyIT`
    does): erasure or deregistration races a `decide` that grants one of its PENDING rows.
    Whichever commits first, the counter shows exactly one move out of that PENDING row, and the
    stored status agrees with the last committed move.
  - `MetricsExposureIT`: `/metrics` lists `jvm.memory.used`, `http.server.requests`, a
    `hikaricp.connections*` meter, `consentmanager.consent.transitions` and the existing
    token-rejection meter. `/prometheus` returns the scrape format.
- **Acceptance.** AC 17 holds. AC 15 holds through the existing JDBC readiness indicator and
  synchronous migration (see the departures above).

### Step 4: Serve non-API routes at the origin root; switchable metrics auth
Today every route, management included, sits under `micronaut.server.context-path` (`/v1`).

**Why the naming strategy alone cannot carry the prefix (checked in Micronaut 5.2.8 source).**
`AbstractEndpointRouteBuilder.resolveUriByRouteId` builds every management route through
`UriNamingStrategy.resolveUri(String)` unless `endpoints.all.context-path` is set.
`HyphenatedUriNamingStrategy` prepends its constructor's context path in *all* of its
`resolveUri` overloads. So passing the API prefix to `super(...)` would leave `/health`,
`/metrics` and `/prometheus` under `/v1`. Static resources and `@ServerFilter` patterns take the
server context path separately, through `ServerContextPathProvider`.

- **Design.**
  - Leave `micronaut.server.context-path` unset in every environment.
  - Add `consent-manager.api-prefix` (`${API_PREFIX:/v1}`), injected into
    `OriginRootUriNamingStrategy`.
  - The strategy calls the no-argument `super()`. It adds the prefix itself, only in
    `resolveUri(BeanDefinition)` and `resolveUri(Class)`, and only for non-`@OriginRoot`
    controllers.
  - `resolveUri(String)`, which management endpoints use, stays unprefixed.
  - As a second guard, set `endpoints.all.context-path: /`, which bypasses the strategy for
    endpoints entirely.
- **Resulting routes.** Health, metrics and prometheus resolve at `:9090/health/liveness`,
  `/health/readiness`, `/metrics` and `/prometheus`. Swagger UI is at `/swagger-ui` (plus
  `/swagger-ui/**`) on the API port, and generated controllers stay under `/v1`.
- **Spike first.** Pass criterion: with the base config, `GET :9090/health/liveness` returns
  200, `GET :9090/v1/health/liveness` returns 404, and `@OriginRoot` JWKS answers at
  `/.well-known/jwks.json`.
- **Test configuration.** `application-test.yml` drops `micronaut.server.context-path: /` and
  sets `consent-manager.api-prefix: ""`, so the IT suite keeps addressing controllers
  unprefixed. `ManagementEndpointSecurityIT` (line 194) sets `consent-manager.api-prefix`
  instead of `micronaut.server.context-path`. Update the `ApiStatusControllerIT` Javadoc that
  names the old property.
- **Filters and security.** With no server context path, filters on `/**` and the security filter
  see every route, including JWKS. Update the `OriginRoot` Javadoc and the `intercept-url-map`
  comment, since patterns are now matched against the full path.
  - `/health` and `/health/**` stay anonymous, and so does `/swagger-ui/**`.
  - The intercept-url-map has no API-route entries, and none is added. Those routes stay governed
    by `@Secured` from `x-roles`, so no pattern needs the configurable prefix.
- **Metrics authentication, switchable in every environment.**
  - **Flag.** Add `consent-manager.management.metrics-authentication` (env
    `METRICS_AUTHENTICATION_ENABLED`, default `true`). Document it in `.env.sample` and
    `application.yml`.
  - **When true.** `/metrics/**` and `/prometheus/**` require a bearer token, as today, in every
    environment, dev included. `application-dev.yml` keeps its current policy.
  - **When false.** Both are anonymous. This is for a cluster that protects the management port
    by network policy, a service mesh or a scraping sidecar instead.
  - **Implementation.**
    - Bind `endpoints.metrics.sensitive` and `endpoints.prometheus.sensitive` to the same env var.
    - Replace the two hard-coded intercept entries with a narrow `MetricsAccessRule`, a
      `SecurityRule` ordered ahead of `ConfigurationInterceptUrlMapRule`. It answers only for
      those two path trees on the management port, returning `ALLOWED` when the flag is off and
      requiring authentication when it is on. It returns `UNKNOWN` for everything else, so it
      is not the catch-all rule the base config forbids.
  - **Port.** The alternative port is `MANAGEMENT_PORT`. Endpoint routes are bound to it
    (`exposedPort`) and are not routed on the API port. Document that operators expose it only
    inside the cluster.
- **Swagger UI.** Fix `static/swagger-ui/index.html` so it loads the spec from the new location
  (Step 5 serves `/openapi.yaml`; until then keep the static mapping working).
- **Probes.**
  - `compose.yaml` already probes `/health/readiness`. Drop `${API_PREFIX}` from it and from the
    header comment.
  - Change the `docker/Dockerfile` `HEALTHCHECK` from the aggregate `${API_PREFIX}/health` to
    `/health/liveness`.
  - Update the comments in `application.yml` and `tracing.exclusions`.
- **Tests.** Rewrite the `ManagementEndpointSecurityIT.routingMatrix` rows:
  - management `/health/liveness` 200, `/health/readiness` 503 (as before), `/v1/health/liveness`
    404;
  - `/metrics` and `/prometheus`:
    - 401 for anonymous with the flag on, and 200 for anonymous with it off (parameterized over
      the flag);
    - 404 on the API port either way;
  - `/health` details hidden for anonymous in the base config (AC 16);
  - API `/swagger-ui/index.html` 200, `/v1/api-status` 200, JWKS 200, all under a non-empty
    `api-prefix`.

  Run the full suite.
- **Acceptance.** ACs 14 and 16 hold. The routes match the ticket, and metrics authentication can
  be switched off without the dev profile.

### Step 5: Bundle the spec and serve it byte-for-byte at `GET /openapi.yaml`
- **Bundling.** Add a second `openapi-generator-maven-plugin` execution (generator `openapi-yaml`,
  output e.g. `target/openapi-bundle/openapi.yaml`). It bundles `api/openapi.yaml` into one
  document with every external `$ref` resolved. Verify the output keeps `x-roles`/`x-principal`,
  the security schemes and descriptions. If the generator rewrites the spec unacceptably, use a
  pinned `redocly/cli bundle` container instead and record why.
  *Done differently:* the `openapi-yaml` generator and swagger-parser both hoist inline schemas
  into invented components, reorder keys and leave nested relative refs dangling, and a Docker
  container in `generate-resources` would make every compile need Docker. The build instead runs
  `src/build/java/OpenApiBundler.java` (SnakeYAML, single-file source mode, via
  `exec-maven-plugin`), which inlines each file the root's `components` names and rewrites refs to
  internal pointers, keeping key order and component names as authored.
- **Packaging.** Copy the bundle to `target/classes/static/openapi.yaml` and serve it at
  `GET /openapi.yaml` on the API port, outside the prefix, through a static-resources mapping.
  Point Swagger UI at it.
- **Retire the old copy.** Remove the per-component `copy-openapi-spec` copy and the `/static/**`
  mapping and intercept entry once nothing needs them. Narrow `SpecReferenceResolutionTest` to the
  authored tree plus "the served bundle contains no external `$ref`".
- **Tests.** `OpenApiServingIT`: the bytes of `GET /openapi.yaml` equal the bytes of the build's
  bundle file. The response is anonymous, its content type is YAML, and the bundle parses.
  `SpecSecurityConsistencyTest` keeps passing against the authored spec.
- **Acceptance.** ACs 12 and 13 hold.

### Step 6: Spectral lint in the build, plus missing descriptions
- **Ruleset.** Add `api/.spectral.yaml`, extending `spectral:oas`, with custom rules. Every
  operation has an `operationId`, a `summary` and a `security` entry (`security: []` counts). Every
  operation `$ref`s the shared 401/403 responses (public ones excepted) and documents its 4xx
  responses through `components/responses`. Every schema property has a `description`. Path
  segments are kebab-case and properties camelCase. Turn off or justify any `spectral:oas` rule
  the repo conventions contradict.
- **Runner.** Add `api/SpecLintIT`, which runs a pinned `stoplight/spectral` image (tag as a named
  constant) via Testcontainers against the bundled spec. It fails on any error-severity result
  and prints the findings.
- **Descriptions.** Fill every missing property description, about 32 today: `ConsentReceipt`,
  `ConsentReceiptKantara`, `PrivacyNoticeResolution`, `Participant*`, `PrivacyNotice`,
  `UserRegistrationResult`. A `$ref`-only property gets its description via `allOf: [$ref]` or a
  sibling `description`, whichever the generator keeps type-stable. Assert the generated types are
  unchanged.
- **Proving a violation fails.** Add a parameterized test that lints small fixture specs under
  `src/test/resources/spectral/`, each breaking one rule, and expects a failure.
- **Acceptance.** AC 10 holds.

### Step 7: Breaking-change detection with oasdiff
- **Baseline.** Commit `api/baseline/openapi.yaml`, the bundled spec at this point, with a
  one-paragraph `api/baseline/README.md`. It must not be served. Step 5 removes the
  `api/**/*.yaml` copy into `target/classes/static/`. If that copy survives, exclude
  `baseline/**` from it and assert in `OpenApiServingIT` that `/static/baseline/openapi.yaml` is
  404. Acknowledging a breaking change means regenerating the
  baseline in the same PR, which makes the change visible in the diff. Refresh it on each release.
- **Runner.** Add `api/SpecCompatibilityIT`, which runs a pinned `tufin/oasdiff` image via
  Testcontainers (`oasdiff breaking --fail-on ERR baseline bundle`). It fails the build and prints
  the findings.
- **Proving detection.** Add a parameterized test against fixtures: a removed operation, a
  removed response property, and a newly required request property are detected; an added
  optional property is not.
- **Docs.** Document the acknowledgement workflow in `docs/api-compatibility.md`. The AGENTS.md
  entry lands with Step 8's AGENTS.md pass ("the lint/diff tests, and the baseline workflow"),
  because step agents do not edit AGENTS.md.
- **Acceptance.** AC 11 holds.

### Step 8: Local WireMock contract stubs, docs and AGENTS.md
- **Stubs.** Add `docker/wiremock/mappings/*.json` (and `__files/` if needed) stubbing the
  Contract Service endpoints `ContractServiceClient` calls, with at least one contract naming a
  provider and a consumer. Base them on the shapes the existing WireMock ITs use.
- **Compose.** Add a `wiremock` service (pinned `wiremock/wiremock` image) to `compose.yaml` that
  mounts the directory. Set `CONTRACT_SERVICE_URL=http://wiremock:8080` for the `app` service and
  document it in `.env.sample`.
- **Absence test.** Add `ProductionArtefactTest`: no class or resource under `target/classes`
  references WireMock or a simulated route.
- **Docs.**
  - Update `docs/security.md`: the correlation ID, the log rules and the pseudonym key, the 500
    detail flag, and the metrics authentication flag.
  - Update AGENTS.md:
    - routes now at the origin root, and `consent-manager.api-prefix` versus the empty context
      path;
    - the `observability/` package;
    - the spec bundle and the lint/diff tests, and the baseline workflow;
    - the new env vars (`ERRORS_EXPOSE_EXCEPTION_DETAIL`, `LOG_PSEUDONYM_KEY`,
      `METRICS_AUTHENTICATION_ENABLED`).
- **Final check.** Run `./mvnw verify` once more for the whole ticket.
- **Acceptance.** AC 26 holds, and every AC is checked off against the merged steps.
- *Done differently:* step agents may not edit AGENTS.md, so the AGENTS.md items above are left to
  a plan-mode agent (requested with `[plan-update]`). Apply them on the plan branch:
  - **Project Structure.** Add `observability/` (`CorrelationIdFilter`, `LogPseudonymizer`,
    `FailureSummary`, `ConsentMetrics`), `error/ServerErrorDetail`,
    `security/MetricsSensitiveEndpointRule`, `api/OriginRootUriNamingStrategy`,
    `src/build/java/OpenApiBundler.java`, `api/.spectral.yaml`, `api/baseline/` (oasdiff
    baseline, not served), `docker/wiremock/` (dev Contract Service stub) and the tests
    `SpecLintIT`, `SpecCompatibilityIT`, `OpenApiServingIT`, `ProductionArtefactTest` and
    `DevContractStubTest`.
  - **Important Files.** Replace the `copy-openapi-spec` entry with the `bundle-openapi-spec`
    execution: `OpenApiBundler` inlines the root's components into
    `target/openapi-bundle/openapi.yaml`, which is copied to `target/classes/static/openapi.yaml`.
  - **Management routes** (currently stale). `micronaut.server.context-path` is unset everywhere.
    Generated controllers are prefixed by `consent-manager.api-prefix` (`API_PREFIX`, default
    `/v1`; empty in tests) through `OriginRootUriNamingStrategy`. Health, `/metrics` and
    `/prometheus` are at the origin root of `MANAGEMENT_PORT`. `/openapi.yaml`, `/swagger-ui/**`
    and `@OriginRoot` JWKS are at the origin root of the API port.
  - **Conventions.** `SpecReferenceResolutionTest` checks the authored tree, and that the served
    bundle has no external `$ref` (no more `target/classes/static/` component copy).
    `SpecLintIT` runs pinned Spectral over the bundle and fails on an error-level finding.
    `SpecCompatibilityIT` runs pinned oasdiff against `api/baseline/openapi.yaml` and fails on
    `ERR`. A deliberate break is acknowledged by regenerating the baseline in the same PR
    (`./mvnw generate-resources && cp target/openapi-bundle/openapi.yaml api/baseline/`); see
    `docs/api-compatibility.md`. Every log line at INFO+ uses `LogPseudonymizer` for personal
    identifiers, and a failure logs only the `FailureSummary`.
  - **Config.** New env vars: `ERRORS_EXPOSE_EXCEPTION_DETAIL` (default false, true in dev),
    `LOG_PSEUDONYM_KEY` (required, at least 32 bytes), `METRICS_AUTHENTICATION_ENABLED` (default
    true, every environment), `API_PREFIX`, and `WIREMOCK_PORT` (compose only).
- **AC check-off.** AC 10: Step 6. AC 11: Step 7. ACs 12 and 13: Step 5. ACs 14 and 16: Step 4.
  ACs 15 and 17: Step 3. AC 19: Step 2. ACs 18, 20, 22, 24 and 25: Step 1. AC 26: this step.
  The remaining ACs were already met on `main` (see Overview).

### Step 9: Code hygiene: loops to streams
A behaviour-preserving refactor across `src/main/java` and `src/test/java`, including code added
by Steps 1-8.
- **Rewrite.** Rewrite every `for`/`while` loop that has a stream equivalent: mapping,
  filtering, collecting, `anyMatch`/`allMatch`/`noneMatch`, `findFirst`, grouping, joining, and
  `IntStream.range` for index loops whose body is a pure function of the index. About 24 loops in
  `src/main` today, concentrated in `IdentityProviderRegistryValidator`,
  `ContractDiscoveryService`, `PrincipalResolutionFilter`, `IdentityProviderRegistry`,
  `ClaimMapper` and `ContractGateway`. Use the same rules in tests.
- **Keep a loop only when a stream cannot express it without changing behaviour.** That means:
  - a body that throws a checked exception;
  - several accumulators mutated together;
  - labelled `break`/`continue`, or early exit with side effects;
  - retry and poll loops such as `support/Await`;
  - byte-level loops in `CanonicalJsonSerializer`.

  List every loop that stays, with a one-line reason, in the PR description.
- **Ordering.** Keep encounter order wherever the result is ordered or compared (use `toList()`,
  and `LinkedHashMap` collectors where a map's order is observed). Use no parallel streams.
- **Acceptance.** `./mvnw verify` is green and the JaCoCo gate holds. No public signature
  changes.

### Step 10: Code hygiene: concise Javadoc and comments
A comment-only pass across `src/main/java`, `src/test/java` and the `application*.yml` comment
blocks, including code added by Steps 1-9. About 18,000 Javadoc lines in `src/main` today.
- **Target.** One line saying what a type or public method is and why it exists. Add a second
  line only for a contract the signature cannot show: ownership, absent/error semantics, units,
  concurrency, side effects, invariants. Three lines at most, unless a security or concurrency
  invariant genuinely needs a short paragraph.
- **Remove:**
  - ticket and step references (`TICKET-0xx`, `ticket #78`, "step 3 of this ticket");
  - history ("previously", "was changed because");
  - prose walking through the body;
  - `@param`/`@return` that echo the signature;
  - inline comments that restate code.

  A design rationale worth keeping becomes a single pointer (`See ADR-00xx.`) rather than a
  paraphrase of the ADR.
- **Order of work.** Work package by package, heaviest first: `security/`, `config/`,
  `service/`, `domain/`, `repository/`, then the rest.
- **Guard.** Add `SourceHygieneTest`, which fails when a Java source or `application*.yml` under
  `src/` references a ticket (`TICKET-\d+`, `ticket #\d+`, `step \d+ of this ticket`).
  Parameterize it over the matched files.
- **AGENTS.md.** Replace the "Actual density" note under "Doc comments are terse" with the new
  rule, and drop the statement that service classes carry multi-paragraph Javadoc.
- **Commits.** The step lands as two commits, so the comment-only claim can be checked:
  1. the comment pass over existing files;
  2. `SourceHygieneTest` and the AGENTS.md edit.
- **Acceptance.**
  - Commit 1 touches comments only. Check it with `git diff -U0 <commit1>^ <commit1> -- src/main/java
    src/test/java 'src/**/application*.yml' | grep '^[+-]'`, filtered to non-comment lines. The
    result must be empty apart from Spotless reflow. Commit 2 is reviewed as ordinary code.
  - `./mvnw verify` is green.
