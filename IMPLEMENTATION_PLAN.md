# Implementation Plan: Consent Manager - Reimplementation - IAM (TICKET-003)

## Overview

Turn the Consent Manager into a stateless OAuth2 **resource server**: it issues no tokens, stores no
credentials and holds no sessions. Every authenticated request carries a bearer JWT from an
explicitly trusted OpenID Connect provider drawn from a trust list that is loaded from configuration
at startup and is immutable for the life of the process. This plan delivers the token
contract in the API specification, a validated static IDP registry with OIDC discovery and JWKS
caching, the full token validation pipeline, typed principals behind `@Secured` role checks, and
just-in-time user provisioning — verified end-to-end against a Testcontainers Keycloak.

## Conventions and Deviations from the Ticket

These decisions apply to every step below and were settled during planning:

1. **Package root.** The ticket's "Files to Create" list uses `eu.prometheusx.consentmanager`. This
   repository and `AGENTS.md` use `com.seamware.consentmanager`. All new code goes under
   `com.seamware.consentmanager.security` and `com.seamware.consentmanager.service`. The ticket's
   paths are treated as structural guidance only.
2. **Spec layout.** `api/openapi.yaml` is currently a single self-contained file. AC 18 requires the
   token contract in `api/components/security.yaml`. TICKET-015 mandates the target layout —
   `api/openapi.yaml` as the root, `api/paths/*.yaml`, and
   `api/components/{schemas,responses,parameters}/` plus `api/components/security.yaml` — so this
   ticket introduces exactly the slice of that structure it needs and must not invent a conflicting
   one. The `copy-openapi-spec` execution of `maven-resources-plugin` in `pom.xml` currently copies
   only `openapi.yaml`; it must be widened to copy the whole `api/` tree so relative `$ref`s resolve
   for Swagger UI.
3. **Scope of AC 19.** Only `/api-status` exists today. AC 19 is satisfied by *establishing the
   mechanism* — an automated test asserting every spec operation's `security` requirement matches
   the `@Secured` annotation on its implementing controller — and applying it to current endpoints.
   Because no concrete controller exists yet, Step 1 also creates `ApiStatusController` so the test
   has something real to reflect over, and the test fails rather than skips when an operation has no
   implementation. Later endpoint tickets inherit the rule for free.
4. **Error bodies.** There is no `ApiError` class — the stale codebase-context entry claiming one
   was already corrected in the commit that introduced this plan. Errors use
   the generated `com.seamware.consentmanager.api.generated.model.ProblemDetail` via
   `GlobalExceptionHandler`, consistent with the existing code.
5. **The trust list is immutable at runtime.** The set of trusted issuers is read **once, at
   startup**, from configuration (environment variables / `application.yml`) and is then fixed for
   the lifetime of the process. There is no runtime reload, no admin endpoint, no database-backed
   provider table, no auto-registration and no discovery of issuers that were not configured.
   Adding, removing or re-pointing a provider requires a configuration change and a restart. The
   only state that changes after startup is *per-provider metadata for that fixed set* — the
   discovered `jwks_uri` and the cached signing keys — which is what makes key rotation (AC 13)
   work without a restart. No type in this plan may expose a method that adds, removes or replaces
   a registry entry; the collection is published as an unmodifiable view.
6. **Custom validation, not declarative JWKS.** The `micronaut.security.token.jwt.signatures.jwks.keycloak`
   block in `application.yml` is a TICKET-001 placeholder for a *single* static issuer. The registry
   holds a configurable *number* of providers — variable in size across deployments, fixed at
   startup within one (convention 5) — so the per-issuer validators are constructed
   programmatically and that placeholder block is removed.
7. **No magic constants.** Every default (`sub`, `1h` JWKS TTL, `30s` clock skew, refetch cooldown,
   backoff bounds) is a named `static final` constant with a JavaDoc comment.
8. **Documentation.** Every public type and method gets JavaDoc. Tests are parameterized
   (`@ParameterizedTest` + `@MethodSource`/`@CsvSource`) wherever a matrix of inputs applies.
9. **Ignore the pre-revision user stories.** The linked `reimplementation/08-user-stories.md` is a
   *pre-revision* document: its US-UM-002 still demands email/password login with bcrypt and
   server-side sessions, and US-PM-002 demands a client-id/secret login returning a self-issued JWT.
   Both are explicitly superseded ("Rewrite") by `00-revision-proposal.md` §7.2/7.3/7.5, and
   US-SM-010 is promoted to Must-have. There are no written acceptance criteria for US-ID-001…008
   anywhere in the reference repository — this ticket's own 19 acceptance criteria are the contract.
   Do not implement anything from `08-user-stories.md` without checking that disposition table.
10. **Error identity.** Problem `type` URIs use the namespace
    `https://consent-manager.example/problems/<slug>` and carry a `correlationId` alongside the
    RFC 7807 fields, per TICKET-015. Reuse that shape for the 401/403 bodies added here.

## Steps

### Step 1: Token contract and security schemes in the API specification

Author the token contract as a reviewable design artefact **before** any identity code exists, per
US-ID-003 and AC 18.

Split the specification into a referenced component layout:

- Create `api/components/security.yaml` containing a `securitySchemes` section with a `bearerAuth`
  scheme (`type: http`, `scheme: bearer`, `bearerFormat: JWT`) whose `description` enumerates the
  **complete** token contract from the ticket: the User Access Token table (`iss`, `aud`, `exp`,
  `iat`, `sub`, the configurable user-identifier claim, the roles claim, and the optional `email`,
  `email_verified`, `name`, `given_name`, `family_name`, `nbf`, `jti`), the Participant Access Token
  table (including the configurable participant-identifier claim and optional `azp`/`client_id`),
  and the Catalog Token as a participant token bearing the `CATALOG` role. State explicitly that
  identifier claims are treated as opaque (email, DID, URI or EUDI Wallet PID hash), that claim
  names are configurable per IDP, and that `alg: none` and symmetric algorithms are rejected.
- Move the existing `ApiStatus` and `ProblemDetail` schemas into `api/components/schemas/` and
  `$ref` them from `api/openapi.yaml`, matching TICKET-015's mandated directory layout so security is
  not a lone special case and later endpoint tickets extend the same structure. Create
  `api/components/responses/` for the shared error responses below. Do not create `api/paths/` yet —
  there is only one stub operation, and TICKET-015 owns that split.
- Add a top-level `security: [{ bearerAuth: [] }]` default in `api/openapi.yaml`, with `/api-status`
  overriding it to `security: []` (it is a public reachability probe). Document the three roles and
  the 401-vs-403 distinction in the spec `description`.
- Add `401` and `403` responses (media type `application/problem+json`, schema `ProblemDetail`) to
  the shared responses component and reference them from **every secured operation**. `/api-status`
  is declared `security: []` two bullets above, so it must *not* advertise 401 or 403 — it is the
  only operation that exists today, so the shared responses component is established here for the
  endpoint tickets that follow rather than for a current consumer.
- **Create the concrete controller for `getApiStatus`.** `generateControllerAsAbstract=true` and
  `generateSupportingFiles=false` (`pom.xml`) mean the generator emits only an *abstract*
  controller (`StatusController`, named from the `Status` tag) which is not a bean — so `/api-status`
  is currently not routed at all. Add
  `src/main/java/com/seamware/consentmanager/api/ApiStatusController.java` extending the generated
  abstract controller and returning the `ApiStatus` payload. Step 6 then annotates this class to
  match the spec's `security: []`, and the AC 19 consistency test in Step 6 gets a real controller
  to reflect over instead of passing vacuously.

Update `pom.xml`: change the `copy-openapi-spec` `maven-resources-plugin` execution to copy the
entire `api/` directory (preserving the `components/` subdirectory) into
`${project.build.outputDirectory}/static`, and confirm the `openapi-generator-maven-plugin`
still resolves the `$ref`s from `${project.basedir}/api/openapi.yaml`.

**Acceptance:** `./mvnw generate-sources` resolves all `$ref`s and regenerates the same API and model
types as before (verify the generated `ProblemDetail` and `ApiStatus` classes keep their shape, since
`GlobalExceptionHandler` already imports `ProblemDetail`); `./mvnw verify` passes; the spec served at
`/static/openapi.yaml` renders in Swagger UI with the security scheme and its full claim
documentation visible; and a test asserts `GET /api-status` (under whatever context path the active
environment sets — `application-test.yml` clears it) now returns 200 with the `ApiStatus` body.
Covers AC 18.

### Step 2: `Role` enum and validated IDP registry configuration

Introduce the static trust list and make a malformed or empty registry a **startup failure**.

Create in `com.seamware.consentmanager.security`:

- `Role.java` — enum `USER`, `PARTICIPANT`, `CATALOG`, with JavaDoc stating each role's scope from
  the ticket's authorization table.
- `IdentityProviderConfiguration.java` — bound with
  `@EachProperty(value = "consent-manager.identity-providers", list = true)` so each YAML list entry
  becomes its own bean. Fields: `issuer` (`@NotBlank`, valid absolute URL), `discoveryUrl`
  (`@NotBlank`, valid absolute URL), `audience` (`@NotBlank`), `jwksCacheTtl` (`Duration`, default
  `DEFAULT_JWKS_CACHE_TTL = 1h`), `clockSkew` (`Duration`, default `DEFAULT_CLOCK_SKEW = 30s`), plus
  nested `@ConfigurationProperties` classes for `claims` (`userIdentifier` defaulting to
  `DEFAULT_USER_IDENTIFIER_CLAIM = "sub"`, `participantIdentifier`, `roles` — all `@NotBlank`) and
  `roleMapping`. **Bind `roleMapping` as `Map<String, String>`, not `Map<Role, String>`:** Micronaut
  normalizes configuration keys to lowercase-kebab, so the ticket's YAML key `USER` reaches the
  binder as `user` and enum-key conversion is not dependable. Convert to `Role` explicitly in a
  `@PostConstruct` using a case-insensitive lookup that fails startup naming the unrecognised role
  string — which also yields a far better message than a converter failure — and require the
  resulting map to be non-empty.
- A `@Context`-scoped `IdentityProviderRegistryValidator` (or an `ApplicationStartupEvent` listener)
  that fails startup with a clear message when zero providers are configured — Bean Validation on
  `@EachProperty` beans cannot itself catch an *absent* list. This check is **unconditional**: it
  fires during context startup regardless of `micronaut.security.enabled`, because a resource server
  with no trust list is misconfigured whatever that toggle says. That makes it a breaking change for
  the existing test suite — see the `application-test.yml` requirement below.
- Per convention 5 the bound providers are **read once and never mutated**: the registry publishes
  an unmodifiable collection and offers no setter, reload hook or refresh endpoint.

Wire configuration: add a documented `consent-manager.identity-providers` block to
`application.yml` driven by environment variables; add a local Keycloak-shaped entry to
`application-dev.yml`; remove the obsolete
`micronaut.security.token.jwt.signatures.jwks.keycloak` placeholder from `application.yml` and its
`application-dev.yml` override. Document every new environment variable in `.env.sample` **and
delete the now-obsolete `IDP_JWKS_URL`, `IDP_ISSUER_URL`, `IDP_CLIENT_ID` and `IDP_CLIENT_SECRET`
entries** (`.env.sample` lines 52-62): all four are superseded by the registry, and a resource
server holds no client secret at all, so leaving `IDP_CLIENT_SECRET` advertised contradicts the
ticket's "stores no credentials" mandate. Add the IDP service to `compose.yaml` if a local provider
is needed for `./mvnw mn:run`.

**Also update `src/test/resources/application-test.yml`** with a dummy single-provider registry
entry (an unreachable `http://localhost:1/realms/test`-shaped issuer and discovery URL is
sufficient — nothing resolves it, and Step 3 makes an unresolvable provider non-fatal). Without it
the unconditional fail-fast check above turns the currently-green `ApplicationSmokeIT` red the
moment this step lands, because that test starts a full application context and
`application-test.yml` has no `consent-manager.identity-providers` block.

**Files:** `src/main/java/com/seamware/consentmanager/security/{Role,IdentityProviderConfiguration}.java`,
`src/main/resources/application.yml`, `application-dev.yml`, `.env.sample`, `compose.yaml`,
`src/test/resources/application-test.yml`,
`src/test/java/com/seamware/consentmanager/security/IdentityProviderConfigurationTest.java`.

**Acceptance:** Parameterized tests using `ApplicationContext.run(Map.of(...))` assert that a valid
registry binds correctly (including `Duration` parsing and nested claim paths) and that each
malformed variant — empty list, blank issuer, blank audience, missing roles claim, empty role
mapping, unknown role name in the role mapping, non-URL discovery URL — fails context startup with a
message naming the offending property. The pre-existing `ApplicationSmokeIT` must still pass, which
is the check that the `application-test.yml` update above was not forgotten. Covers AC 1, and the
configuration surface for AC 7 and AC 8.

### Step 3: OIDC discovery, issuer verification, and readiness reporting

Resolve each provider's metadata at startup **without** letting an IDP outage prevent the service
from starting (AC 3).

Create `IdentityProviderRegistry.java` in `com.seamware.consentmanager.security`:

- Holds one resolved entry per configured provider: the configuration plus the discovered `jwks_uri`
  and a resolution state (`PENDING`, `RESOLVED`, `FAILED` with the last error).
- On startup, for each entry, `GET` the `discovery-url` with a Micronaut `HttpClient` and extract
  `issuer` and `jwks_uri` from the OpenID Provider Metadata document.
- **Reject** the entry if the discovered `issuer` is not byte-for-byte equal to the configured
  `issuer`. Treat this as a permanent, non-retryable failure and log at ERROR — it is a
  misconfiguration, not an outage.
- On a transport or 5xx failure, log at ERROR and schedule a retry with **exponential backoff**
  between named `MIN_DISCOVERY_RETRY_DELAY` and `MAX_DISCOVERY_RETRY_DELAY` constants, using
  Micronaut's `@Scheduled` or `TaskScheduler`. Startup must never block or fail on this.
- The registry's **membership is fixed at construction** from the Step 2 configuration (convention
  5). Discovery only fills in per-entry metadata and resolution state; no code path adds, removes or
  replaces an entry, and an unknown issuer is never learned at runtime.
- Expose `Optional<ResolvedIdentityProvider> findByIssuer(String issuer)` for the validator, and an
  aggregate readiness view. **`findByIssuer` returns `RESOLVED` entries only.** A token from a
  provider that is configured but still `PENDING`, or permanently `FAILED`, is therefore rejected
  with exactly the same generic 401 as a token from an unregistered issuer: the caller learns
  nothing about which issuers are configured or about their internal resolution state (US-ID-008),
  and readiness (below) is already `DOWN` so an orchestrator drains the instance rather than letting
  it serve misleading 401s. Never hand an unresolved entry — which has no `jwks_uri` — to the key
  lookup.

Create `IdentityProviderHealthIndicator.java` annotated with
`io.micronaut.management.health.indicator.annotation.Readiness`, reporting `DOWN` (with per-issuer
detail) while any provider is unresolved and `UP` once all are resolved. Liveness is untouched and
stays `UP`. Configure `endpoints.health` in `application.yml` so `/health/readiness` and
`/health/liveness` are exposed separately.

**Acceptance:** WireMock-backed tests (WireMock is already a test dependency) assert: discovery
resolves `jwks_uri` from a well-formed metadata document; an issuer mismatch marks the provider
permanently failed and is never retried; a discovery outage leaves the application context started,
`/health/liveness` `UP` and `/health/readiness` `DOWN`; readiness flips to `UP` after the stub
recovers and a retry succeeds; and `findByIssuer` returns empty for a configured-but-unresolved
issuer (its 401 is asserted in Step 5). Covers AC 2 and AC 3.

### Step 4: JWKS caching, key selection, and rate-limited refetch

Add signing-key resolution on top of the resolved registry, sized for rotation without restarts.

**Build on Nimbus; do not hand-roll the cache.** Dropping the *declarative* single-issuer YAML block
(convention 6) does not mean writing a cache from scratch. `micronaut-security-jwt` already brings
`nimbus-jose-jwt`, whose `JWKSourceBuilder` supplies almost all of this step, battle-tested:
`.cache(ttl, refreshTimeout)` for the per-issuer TTL, `.rateLimited(cooldown)` for the AC 14
unknown-`kid` refetch limit, `.retrying(...)`/`.outageTolerant(...)` for IDP flakiness, and
single-flight refresh under concurrency. This is the one place in the plan where a subtle
concurrency bug is a security bug, so a bespoke cache may only be written if Nimbus provably cannot
express a requirement — and then the reason belongs in the commit message.

Create `JwksKeySource.java` in `com.seamware.consentmanager.security`:

- Builds **one `JWKSource` per resolved issuer**, memoized in a `ConcurrentHashMap` keyed by issuer.
  They are built lazily rather than as startup beans because the `jwks_uri` only becomes known when
  Step 3's asynchronous discovery resolves. The map is populated solely for issuers already present
  in the fixed registry — it is a memoization cache, not a second trust list (convention 5).
- Configure each source with that provider's `jwks-cache-ttl` and a named `JWKS_REFETCH_COOLDOWN`
  constant for the rate limiter, so a flood of forged tokens bearing random `kid` values cannot
  trigger a refetch storm against the IDP (AC 14).
- `Optional<JWK> selectKey(String issuer, String kid)` resolves through the source with a
  `JWKSelector`/`JWKMatcher` on `kid`, returning empty when the key is still unknown after at most
  one rate-limited refetch — never loop.
- Register a Micrometer counter for JWKS fetches so the rate limit is observable in production and
  assertable in tests.

**Acceptance:** WireMock tests assert: a key is resolved and served from cache without a second
fetch within the TTL; rotating the stubbed JWKS to a new `kid` causes exactly one refetch and the
new key resolves without restart; N consecutive requests for an unknown `kid` within the cooldown
window trigger at most one refetch (parameterized over N); and concurrent first-time resolutions
issue a single fetch. Covers AC 14 and the caching half of AC 13.

### Step 5: Claim mapping and the token validation pipeline

Create `ClaimMapper.java` and `ConsentManagerTokenValidator.java` in
`com.seamware.consentmanager.security`.

`ClaimMapper` resolves **configurable, dot-separated nested claim paths** (e.g. `realm_access.roles`)
against a `JWTClaimsSet`, returning a typed value or empty. It maps raw role strings to `Role`
constants through the provider's `role-mapping`, ignoring unmapped strings. Both the identifier
paths and the roles path are per-provider.

`ConsentManagerTokenValidator` implements Micronaut Security's `TokenValidator` (reactive signature:
`Publisher<Authentication> validateToken(String token, HttpRequest<?> request)`) and executes the
pipeline exactly in the ticket's order:

1. Parse the JWS header and claims **without trusting them** (`SignedJWT.parse`).
2. Read `iss` and look it up with `IdentityProviderRegistry.findByIssuer`. If no **resolved**
   registered provider matches, reject — a configured provider whose discovery has not succeeded
   counts as no match and is indistinguishable from an unknown issuer (Step 3). The rejection
   message must **not** echo the received issuer back (US-ID-008) — use a fixed, generic message and
   log the issuer at DEBUG only.
3. Resolve the key by `kid` through `JwksKeySource` (Step 4).
4. Verify the signature. **Reject `alg: none` and every symmetric algorithm** (`HS*`) before key
   lookup, via an explicit allow-list of asymmetric algorithms (`RS*`, `PS*`, `ES*`) — a named
   constant set — so this holds regardless of the key material present.
5. Validate `exp`, `iat`, and `nbf` when present, applying the provider's configured `clock-skew`.
6. Validate that `aud` contains that provider's configured audience.
7. Extract and map roles. A token whose roles claim is **absent**, empty, or contains only strings
   the provider's `role-mapping` does not cover still **authenticates** — it simply carries no
   authorities, and is then refused by the `@Secured` check in Step 6 with **403**. "Claim absent"
   and "claim present but unmappable" are deliberately *not* distinguished: both mean "no role", and
   both are 403, never 401 (AC 9).
8. Extract the identifier claim. A `USER`-role token with no resolvable user identifier, or a
   `PARTICIPANT`-role token with no participant identifier, fails validation (401). A token carrying
   no mapped role skips this check — there is no role-specific identifier to demand of it.

An invalid token yields an empty `Publisher`, which Micronaut renders as **401**. The validated
`Authentication` carries the mapped role names as authorities plus the raw claims as attributes, for
the principal resolution in Step 6. Also ensure the Micronaut-provided `JwtTokenValidator` does not
run in parallel with this one against a now-absent declarative signature configuration.

**Acceptance:** Parameterized unit tests over a locally generated RSA/EC key pair (no container
needed) cover the full rejection matrix: unregistered issuer (and an assertion that the response body
and message contain no substring of the submitted issuer), bad signature, expired `exp`, `nbf` in the
future, wrong `aud`, `alg: none`, `HS256` signed with the JWKS modulus as an HMAC secret, unknown
`kid`, **a `kid` valid at issuer A presented in a token claiming issuer B** (cross-issuer key
confusion), a token whose issuer is configured but not yet resolved, and `USER`/`PARTICIPANT` tokens
missing their identifier claim. Positive cases cover nested claim paths, each role mapping, and —
explicitly — that a token with an absent or wholly unmappable roles claim **validates successfully
with zero authorities**; its 403 is asserted in Step 6, not here. Covers AC 4, 5, 6, 7, 8.

### Step 6: Typed principals, principal resolution filter, and `@Secured` enforcement

Give handlers a typed caller identity and make the 401/403 distinction explicit.

Create in `com.seamware.consentmanager.security`:

- `UserPrincipal.java` — global identifier, optional `email`, `emailVerified`, `name`, `givenName`,
  `familyName`, and the resolved `User` entity (populated in Step 7).
- `ParticipantPrincipal.java` — global identifier and the resolved `Participant` entity.
- `CatalogPrincipal.java` — issuer and subject only.
- A sealed `ConsentManagerPrincipal` interface (or common supertype) these three implement, exposing
  the granted `Role`.
- `PrincipalResolutionFilter.java` — a Micronaut `@ServerFilter` that converts the validated
  `Authentication` into the correct typed principal and attaches it to the request attributes. For
  `PARTICIPANT` tokens it resolves the `Participant` via `ParticipantRepository.findByIdentifier`;
  an unknown participant is **403**, not auto-created — participants are created explicitly in
  TICKET-005. Note for later endpoint tickets: once resource-level checks exist, a participant
  probing a user it is *not* linked to must receive **404**, not 403, so the authorization boundary
  does not become an existence oracle (TICKET-019).
- A `@RequestBean`/argument-binder so controllers can declare a typed principal parameter directly,
  making it structurally natural to take identity from the token and never from a path parameter or
  request body (AC 15).

Enforce "no implicit access": add a catch-all `intercept-url-map` entry denying unauthenticated
access to everything not explicitly allowed, keeping the existing anonymous exemptions for
`/health/**`, `/swagger-ui/**` and `/static/**`. Annotate the concrete `ApiStatusController` created
in Step 1 with `@Secured(SecurityRule.IS_ANONYMOUS)` to match its `security: []` declaration. Extend `GlobalExceptionHandler` (or add `AuthorizationExceptionHandler`)
so 401 and 403 are rendered as RFC 7807 `ProblemDetail` bodies with `application/problem+json`, and
so 401 responses carry a `WWW-Authenticate: Bearer` header and leak no issuer detail.

Add an **AC 19 consistency test**: parse `api/openapi.yaml` (and its `$ref`ed components), and for
each operation assert that its `security` requirement matches the `@Secured` annotation on the
implementing controller method, discovered by reflection over the generated API interfaces. If an
operation in the spec has **no** implementing controller class or method, the test must **fail**,
not skip — otherwise it passes vacuously and quietly stops covering AC 19 as endpoints are added.
This test is the durable mechanism later endpoint tickets inherit.

**Acceptance:** Tests assert that a valid token whose roles claim is **absent**, and one whose roles
are **present but unmappable**, both receive **403** (not 401) on a secured endpoint, while a
missing or invalid token receives **401**; each role resolves to its
correct typed principal; a `PARTICIPANT` token for an unregistered participant identifier receives
403; and the spec-vs-`@Secured` consistency test passes. Covers AC 9, 15, 19 and the 401/403 rule of
the authorization model.

### Step 7: Just-in-time user provisioning

Create `UserProvisioningService.java` in `com.seamware.consentmanager.service`.

- When a `USER`-role token presents an identifier with no matching `users` row, create one **inside
  the request** from claims only: `identifier` from the configured claim, plus `email`, `firstName`
  (`given_name`) and `lastName` (`family_name`) when present.
- Make it **idempotent and concurrency-safe** by relying on the existing `uq_users_identifier` unique
  constraint: attempt the insert, and on a duplicate-key `DataAccessException` re-read the row rather
  than failing the request. Do not use a pre-check-then-insert race.
- On each subsequent authenticated request, **refresh the display claims** if they changed, writing
  only when a value actually differs so `updated_at` is not churned on every request. These fields
  are explicitly non-authoritative copies; the IDP remains the source of truth.
- JIT provisioning applies to **users only** — participants are never auto-created.

Hook the service into `PrincipalResolutionFilter` so `UserPrincipal` always carries a persisted
`User`.

**Acceptance:** Integration tests extending `PostgresTestResource` assert: a `USER` token for an
unknown identifier creates exactly one row with the claim-derived fields; a second request creates
no further row; changed `email`/`given_name`/`family_name` claims update the row while an unchanged
claim set leaves `updated_at` untouched; and a concurrency test firing N parallel first-time requests
for the same identifier (via `CountDownLatch` and an executor) results in exactly one `users` row
with no request failing. Covers AC 10, 11, 12.

### Step 8: Keycloak integration tests, role matrix, and documentation

Prove the whole pipeline against a **real** IDP issuing **real** tokens.

- Add `KeycloakTestResource.java` under `src/test/java/com/seamware/consentmanager/support/`,
  mirroring `PostgresTestResource`: a **single suite-wide singleton** `dasniko/testcontainers-keycloak`
  container (the dependency is already declared) with an imported realm JSON fixture defining the `dataspace-user`,
  `dataspace-participant` and `dataspace-catalog` roles, a `participant_id` claim mapper, and clients
  for each role. It implements `TestPropertyProvider` to inject the container's issuer, discovery
  URL, audience, claim paths and role mapping into `consent-manager.identity-providers`. Provide a
  combined base class composing the Postgres and Keycloak resources, and note that
  `application-test.yml` currently sets `micronaut.security.enabled: false` — security-aware tests
  must re-enable it via their own property source.
- Add a **parameterized `(role × endpoint × expected status)` matrix test** (AC 16) driven by a
  `@MethodSource` table, covering every role including the no-mapped-role and no-token cases.
  TICKET-019 **forbids hand-minted tokens** here: every token in this matrix must be obtained from
  the Keycloak container. Hand-minted tokens stay confined to the Step 5 unit tests, for the negative
  cryptographic cases (`alg: none`, symmetric algorithms, cross-issuer `kid`) that a conforming IDP
  cannot be made to issue. Since only `/api-status` exists today, include a small test-scoped controller exposing one endpoint per
  role so the matrix is meaningful and remains the template later endpoint tickets extend.
- Add a **key-rotation integration test** (AC 13): obtain a valid token, rotate the Keycloak realm's
  signing key mid-test, obtain a token signed by the new key, and assert both that the new token is
  accepted without restarting the application and that the rotation is picked up through the JWKS
  refetch path.
- Ensure all new integration tests are named `*IT` so `maven-failsafe-plugin` picks them up and
  `./mvnw verify -DskipUTs` exercises them.
- Update `AGENTS.md`: document the new `security` package and its classes, the
  `consent-manager.identity-providers` configuration **and its immutability at runtime**, the
  `api/components/` spec layout, the concrete `ApiStatusController`, the role model and the
  `@Secured` convention. The stale `error/ApiError.java` entry was already corrected in the commit
  that introduced this plan — do not go looking for it.
- Run `./mvnw spotless:apply` and confirm `./mvnw verify` is green.

**Acceptance:** `./mvnw verify` passes with all integration tests running against Testcontainers
Keycloak and PostgreSQL; the role matrix test covers every documented role/status combination; the
rotation test passes without an application restart. Covers AC 13, 16, 17 and closes out the ticket.
