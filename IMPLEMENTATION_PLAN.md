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
  in `application-dev.yml`. When it is false, `GlobalExceptionHandler` keeps
  `ProblemType.SERVER_ERROR_DETAIL`. When it is true, `detail` carries the exception message. The
  stack trace never reaches the response. Document the setting in `.env.sample` and
  `application.yml`.
- **Tests.**
  - Unit `CorrelationIdFilterTest`, parameterized: absent, valid, too long, and illegal-charset
    header.
  - Extend `ProblemMappingTest`/`ProblemResponseIT`, parameterized over every exception type,
    asserting status, `correlationId` equal to the response header, and the inbound ID echoed.
  - A two-violation payload lists both fields in `errors`.
  - Both values of the expose flag produce the right 500 body.
  - A captured log line carries the same `correlationId` as the response.
- **Files.** `observability/CorrelationIdFilter.java`, `error/*` handlers, `ProblemDetail.yaml`
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
    token, a token in a query string, and a forced 500.
  - **Assertion:** no event at INFO or above contains the bearer token (whole, or any segment of
    length ≥ a named constant), any private JWK member, the pseudonym key, or the sentinel
    identifier, email or name.
    Parameterize over the forbidden sentinels.
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
  - `ConsentService.request` and `ConsentService.decide` do not pass through that seam today.
    Route their committed writes through it (with `held` absent for a creation) rather than adding
    a second list of call sites.
  - Never count inside the transaction, and never on the lost-race 409 path.
- **Hikari metrics.** Confirm `hikaricp.*` meters are bound. If they are not, enable the
  Micronaut Micrometer JDBC/Hikari binder in `application.yml`.
- **Tests.**
  - `ConsentMetricsIT`: parameterized over every edge in `ConsentStateMachine`, driven through
    the HTTP routes that make each move. That covers creation (`request`), the decision path
    (`decide`) and the lifecycle routes, including a collateral move. Each committed move
    increments exactly its `(from,to)` counter. A refused move and a lost race increment nothing.
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
- **Docs.** Document the acknowledgement workflow in `docs/` and in AGENTS.md.
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
- **Acceptance.**
  - `git diff --stat` touches comments only. Check it with `git diff -U0 | grep '^[+-]'` filtered
    to non-comment lines, which must be empty apart from Spotless reflow.
  - `./mvnw verify` is green.
