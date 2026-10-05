# Security Architecture

The Consent Manager is a stateless OAuth2 **resource server**. It issues no tokens, stores no
credentials and holds no sessions. Every authenticated request carries a bearer JWT minted by an
OpenID Connect provider from a trust list fixed at startup.

## Trust list: `consent-manager.identity-providers`

The trust list is read **once at startup** from configuration and is immutable for the life of the
process. There is no runtime reload, no admin endpoint, no provider table and no auto-registration;
adding, removing or re-pointing a provider is a configuration change plus a restart.
`IdentityProviderRegistry` publishes the entries as an unmodifiable view and exposes no mutator.

The only state that changes after startup is per-provider metadata for that fixed set — the
discovered `jwks_uri` and the cached signing keys. That is what lets key rotation take effect
without a restart (JWK Set cache TTL: `micronaut.caches.jwks.expire-after-write`, default `60s`).

Each entry is keyed by a provider name (`primary` by default):

| Key | Env var | Default | Meaning |
| --- | --- | --- | --- |
| `issuer` | `IDP_ISSUER` | — | Expected `iss`; must match the token exactly. |
| `discovery-url` | `IDP_DISCOVERY_URL` | — (required) | OpenID configuration document URL. |
| `audience` | `IDP_AUDIENCE` | — | Required `aud` value. |
| `allow-insecure-transport` | `IDP_ALLOW_INSECURE_TRANSPORT` | `false` | Permits `http://` endpoints (tests only). |
| `clock-skew` | `IDP_CLOCK_SKEW` | `30s` | Leeway applied to `exp`/`nbf`/`iat`. |
| `claims.user-identifier` | `IDP_CLAIM_USER_IDENTIFIER` | `sub` | Claim path carrying the user id. |
| `claims.participant-identifier` | `IDP_CLAIM_PARTICIPANT_IDENTIFIER` | `participant_id` | Claim path carrying the participant id. |
| `claims.roles` | `IDP_CLAIM_ROLES` | `realm_access,roles` | Dotted/comma path to the role array. |
| `role-mapping.{user,participant,catalog}` | `IDP_ROLE_{USER,PARTICIPANT,CATALOG}` | `consent-{user,participant,catalog}` | Provider-side role name mapped onto each internal role. |

`IdentityProviderRegistryValidator` fails startup on an invalid or empty trust list.
`IdentityProviderHealthIndicator` reports readiness `DOWN` until every provider's discovery has
resolved; discovery retries with bounded backoff rather than aborting the process.

## `com.seamware.consentmanager.security`

| Class | Role |
| --- | --- |
| `IdentityProviderConfiguration` | `@EachProperty` binding of one trust-list entry. |
| `IdentityProviderRegistryValidator` | Startup validation of the trust list. |
| `IdentityProviderRegistry` | Startup-fixed registry; drives OIDC discovery per provider. |
| `ResolvedIdentityProvider` / `ResolutionState` | A provider plus its discovery outcome. |
| `OpenIdClientConfigurationAdapter` | Adapts a registry entry to `OpenIdClientConfiguration` so `DefaultOpenIdProviderMetadataFetcher` performs discovery (ADR 0003). |
| `JwksSignatureConfigurationAdapter` | Adapts a resolved `jwks_uri` to `JwksSignatureConfiguration` so `JwkSetFetcher`/`ReactiveJwksSignature` fetch, cache and verify (ADR 0004). |
| `IssuerSignatureVerifier` | Routes a token to **its own** issuer's key set only; applies a cooldown when a key set is unreadable. |
| `UnsignedTokenRejector` | Rejects `alg: none` and non-JWS tokens before any key lookup. |
| `ConsentManagerTokenValidator` | The validation pipeline: issuer → audience → expiry/skew → signature → claims (ADR 0005). |
| `ClaimMapper` | Extracts identifiers and roles via the configured claim paths. |
| `Role` | Internal role model: `USER`, `PARTICIPANT`, `CATALOG`. |
| `ConsentManagerPrincipal` + `UserPrincipal` / `ParticipantPrincipal` / `CatalogPrincipal` | Typed principals; the acting role's identifier claim is required (ADR 0006). |
| `PrincipalResolutionFilter`, `PrincipalArgumentBinder(s)` | Resolve the typed principal and inject it into controller methods. |
| `BearerChallengeProvider` | RFC 6750 `WWW-Authenticate: Bearer` on 401. |

Failures are rendered as RFC 7807 Problem Details by `GlobalExceptionHandler`, with `type` URIs
under `https://consent-manager.example/problems/<slug>` and a `correlationId` field.

## `@Secured` convention

Access is declared in the specification, not in Java, and a secured operation states it **twice**:

- `security: [{bearerAuth: [ROLE, ...]}]` — the published contract a client reads.
- `x-roles: [ROLE, ...]` — what the `java-micronaut-server` generator turns into
  `@Secured("ROLE", ...)` on the routed method. The templates never read an operation's `security`
  scopes, so `security:` alone emits `@Secured(SecurityRule.IS_AUTHENTICATED)`: a route open to
  every authenticated caller, whatever roles the contract names.

Role names match `Role` constants (`USER`, `PARTICIPANT`, `CATALOG`). Public operations declare
`security: []` and no `x-roles`, and carry `@Secured(SecurityRule.IS_ANONYMOUS)`. A secured
operation also declares `x-principal: <UserPrincipal | ParticipantPrincipal | CatalogPrincipal |
ConsentManagerPrincipal>`, which `api/templates/server/controller.mustache` turns into the routed
method's typed caller parameter; without it `PrincipalResolutionFilter` never runs its refusals for
that route.

Never annotate the concrete controller: the routed method lives on the generated abstract supertype
and its rule is the one that governs. `SpecSecurityConsistencyTest` fails the build when `@Secured`
and `x-roles` disagree, when `x-roles` and `security` disagree, when an operation has no
implementation, when the concrete controller only inherits the generated delegate (whose body
answers 501), when a secured operation declares no principal parameter or one whose type cannot
hold a role it admits, and when a path item hides its operations behind a `$ref`.

Implemented operations: `/api-status` (anonymous, `ApiStatusController`), `POST /users/register`
and `GET /users/me` (`USER`, `UserController`).

## Spec layout

`api/openapi.yaml` is the root document and `$ref`s into `api/components/`:
`components/security.yaml` (the bearer token contract), `components/schemas/`, `components/responses/`.
The `copy-openapi-spec` execution copies the whole `api/` tree into `target/classes/static/` so the
relative `$ref`s resolve for Swagger UI.

## Testing

- `support/PostgresTestResource` — suite-wide PostgreSQL container.
- `support/KeycloakTestResource` — suite-wide Keycloak container importing
  `src/test/resources/keycloak/consent-manager-realm.json` (roles, `participant_id` mapper, one
  client per role); injects issuer, discovery URL, audience and role mapping as test properties.
  Claim paths and clock skew are inherited from `application.yml`.
- `support/KeycloakAndPostgresTestResource` — base class composing both. `application-test.yml` sets
  `micronaut.security.enabled: false`, so security-aware tests re-enable it from their own property
  source.
- `KeycloakRoleMatrixIT` — parameterized `(role × endpoint × expected status)` matrix against real
  Keycloak-issued tokens. Hand-minted tokens are confined to the unit tests covering cryptographic
  negatives a conforming IDP cannot issue.
- `KeycloakKeyRotationIT` — rotates the realm signing key mid-test and asserts tokens signed by the
  new key are accepted without an application restart.

Integration tests are named `*IT` and run under `maven-failsafe-plugin` (`./mvnw verify -DskipUTs`).
