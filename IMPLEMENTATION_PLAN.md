# Implementation Plan: Consent Manager - Reimplementation - API Docs and Cross-Cutting Infrastructure

## Overview
Most of the ticket is already on `main`: the hand-authored spec, generated `Abstract*Controller`s,
shared `ProblemDetail`/401/403, separate liveness and readiness groups, JSON logging, Prometheus,
and WireMock-only test doubles. This plan fills the remaining gaps. They are correlation IDs and
environment-aware problem details, log hygiene, consent-transition metrics plus Flyway readiness,
non-API routes at the origin root, a bundled spec served byte-for-byte, Spectral lint,
breaking-change detection, and local WireMock dev stubs.

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
- **Hashing helper.** Add `observability/LogSafe` with
  `static String hash(String identifier)`: SHA-256, hex, truncated to a named length constant,
  stable across restarts. Use it for correlation at INFO and above.
- **Fix the known offenders** (verify the list with a grep first):
  - `GlobalExceptionHandler`, `ApiExceptionHandler`, `ProblemErrorResponseProcessor`: these log
    `request.getUri()`, which contains path identifiers and query strings. Log the matched route
    template (`UriRouteMatch` / `HttpAttributes.URI_TEMPLATE`) and the method instead.
  - `UserService` bulk-entry failure (logs the external `identifier`): hash it, or move it to
    DEBUG.
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
    length ≥ a named constant), any private JWK member, the sentinel identifier, email, or name.
    Parameterize over the forbidden sentinels.
- **Acceptance.** AC 19 holds. Every offending statement is fixed, and `./mvnw verify` is green.

### Step 3: Consent-transition metrics and Flyway readiness
- **Transition counter.** Add `observability/ConsentMetrics`, wrapping the `MeterRegistry`, with
  a counter `consentmanager.consent.transitions` tagged `from` and `to` (`none` for creation). Tag
  values come from `ConsentStatus` names, and metric and tag names are constants.
- **Where to count.** Count a move only once its write has **committed**: call it after
  `ConsentWriter` returns, in its callers (`ConsentService`, `ConsentLifecycleService` including
  collateral moves). Never call it inside the transaction, and never on the lost-race 409 path.
- **Flyway readiness.** Add `observability/FlywayReadinessHealthIndicator` (`@Readiness`). It
  reports DOWN when the `Flyway` bean reports pending or failed migrations and UP otherwise. Keep
  it out of liveness.
- **Hikari metrics.** Confirm `hikaricp.*` meters are bound. If they are not, enable the
  Micronaut Micrometer JDBC/Hikari binder in `application.yml`.
- **Tests.**
  - `ConsentMetricsIT`: parameterized over every edge in `ConsentStateMachine`. Each committed
    move increments exactly its `(from,to)` counter, and a refused move increments nothing.
  - `MetricsExposureIT`: `/metrics` lists `jvm.memory.used`, `http.server.requests`, a
    `hikaricp.connections*` meter, `consentmanager.consent.transitions` and the existing
    token-rejection meter. `/prometheus` returns the scrape format.
  - `FlywayReadinessHealthIndicatorTest`, unit, parameterized over the migration states.
- **Acceptance.** ACs 15 and 17 hold.

### Step 4: Serve non-API routes at the origin root
Today every route, management included, sits under `micronaut.server.context-path` (`/v1`).
`OriginRootUriNamingStrategy` already prefixes controllers itself.
- **Prefix only controllers.** Move the prefix into a dedicated `consent-manager.api-prefix`
  property (still `${API_PREFIX:/v1}`), injected into `OriginRootUriNamingStrategy`. Leave the
  server context path empty.
- **Resulting routes.** Health, metrics and prometheus resolve at `:9090/health/liveness`,
  `/health/readiness`, `/metrics` and `/prometheus`. Swagger UI is at `/swagger-ui` (plus
  `/swagger-ui/**`) on the API port, and generated controllers stay under `/v1`.
- **Spike first.** Confirm the management port honours this, and that `@OriginRoot` JWKS still
  resolves.
- **Filters and security.** Filters on `/**` and the security filter now see every route,
  including JWKS and the correlation filter's coverage of it. Update the `OriginRoot` Javadoc and
  the `intercept-url-map`: absolute patterns, `/health/**` anonymous, `/metrics/**` and
  `/prometheus/**` authenticated (anonymous in dev), `/swagger-ui/**` anonymous. Keep the API
  routes' patterns correct under `/v1`.
- **Swagger UI.** Fix `static/swagger-ui/index.html` so it loads the spec from the new location
  (Step 5 serves `/openapi.yaml`; until then keep the static mapping working).
- **Probes.** Update the `compose.yaml` healthcheck to probe `/health/readiness`. Change the
  `docker/Dockerfile` `HEALTHCHECK` to a split route (`/health/liveness`), not the aggregate. Also
  update the comments in `application.yml` and `tracing.exclusions`.
- **Tests.** Rewrite the `ManagementEndpointSecurityIT.routingMatrix` rows:
  - management `/health/liveness` 200, `/health/readiness` 503 (as before), `/v1/health/liveness`
    404;
  - `/metrics` and `/prometheus` 401 for anonymous, and `/health` details hidden for anonymous in
    the base config (AC 16);
  - API `/swagger-ui/index.html` 200, `/v1/api-status` 200, JWKS 200.

  Run the full suite. Every IT using `context-path: /` in `application-test.yml` must still pass.
  Add one IT with a non-empty prefix if none exists.
- **Acceptance.** ACs 14 and 16 hold, and the routes match the ticket.

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
  one-paragraph `api/baseline/README.md`. Acknowledging a breaking change means regenerating the
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
- **Docs.** Update `docs/security.md` (correlation ID, log rules, 500 detail flag). Update
  AGENTS.md: routes now at the origin root, the `observability/` package, the spec bundle and
  lint/diff tests, the baseline workflow, and new env vars.
- **Final check.** Run `./mvnw verify` once more for the whole ticket.
- **Acceptance.** AC 26 holds, and every AC is checked off against the merged steps.
