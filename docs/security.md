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

Implemented operations:

| Operation | Roles | Controller |
|---|---|---|
| `GET /api-status` | anonymous | `ApiStatusController` |
| `POST /users/register` | `USER` | `UserController` |
| `GET /users/me` | `USER` | `UserController` |
| `DELETE /users/me` | `USER` | `UserController` |
| `POST /users/search` | `PARTICIPANT`, `CATALOG` | `UserController` |
| `GET /users/{identifier}` | `PARTICIPANT`, `CATALOG` | `UserController` |
| `POST /participants` | `PARTICIPANT` | `ParticipantController` |
| `GET /participants` | `USER`, `PARTICIPANT`, `CATALOG` | `ParticipantController` |
| `GET /participants/{identifier}` | `USER`, `PARTICIPANT`, `CATALOG` | `ParticipantController` |
| `GET /participants/me` | `PARTICIPANT` | `ParticipantController` |
| `PUT /participants/me` | `PARTICIPANT` | `ParticipantController` |
| `DELETE /participants/me` | `PARTICIPANT` | `ParticipantController` |
| `POST /participants/me/users` | `PARTICIPANT` | `ParticipantUserController` |
| `POST /participants/me/users/bulk` | `PARTICIPANT` | `ParticipantUserController` |
| `PATCH /participants/me/users/{identifier}` | `PARTICIPANT` | `ParticipantUserController` |
| `DELETE /participants/me/users/{identifier}` | `PARTICIPANT` | `ParticipantUserController` |
| `POST /privacy-notices` | `PARTICIPANT` | `PrivacyNoticeController` |
| `POST /privacy-notices/by-contract` | `PARTICIPANT` | `PrivacyNoticeController` |
| `GET /privacy-notices/{id}` | `USER`, `PARTICIPANT`, `CATALOG` | `PrivacyNoticeController` |
| `POST /consents` | `USER` | `ConsentController` |
| `POST /consents/requests` | `PARTICIPANT` | `ConsentRequestController` |

`EndpointRoleMatrixIT` answers each of these with every role, with a token holding no role of this
service and with no token at all, against a real Keycloak; it fails when an operation of the
specification has no row, so the table above cannot quietly fall behind the specification.
`KeycloakRoleMatrixIT` keeps proving the same of synthetic probe routes, which is where the
mechanism itself — discovery, signature verification, claim mapping, principal resolution — is
exercised independently of any published route.

### The two consent routes are deliberately asymmetric

`POST /consents` is a pure `USER` route and takes no participant into account at all. The data
subject is read from the token's identifier claim and from nowhere else: the operation admits only
`UserPrincipal`, the body names no user, and a `userIdentifier` or `email` sent in it anyway is
ignored rather than honoured (`ConsentGrantIT` pins that). There is no header, no parameter and no
role by which a participant can record a decision in a user's name — see
[ADR 0009](adr/0009-only-the-data-subject-grants-consent.md).

`POST /consents/requests` is the mirror image and is held to the stricter participant check.
`ConsentRequestController` resolves the caller through `ParticipantPrincipal.requireActive()`, not
`requireRegistered()`, because opening a request is acting in the dataspace in the participant's
name rather than reading or writing the participant's own record. A participant carrying
`deregistered_at` can still read itself back on `GET /participants/me` and is refused here with
`403`, so a departed organisation cannot start asking for consents on the way out. `requireActive()`
runs before the notice is looked up, which is also what lets `participantScopedEndpoints` sweep the
route without seeding any fixture.

Two further refusals on that route are security properties rather than validation. The caller must
be named as the notice's provider or consumer — a participant that is party to neither gets `403`
and cannot ask about a document it has no standing in. And an unknown user and a user the caller is
not affiliated with are refused *identically*, both `404` with the same message, so the route
cannot be used to enumerate the dataspace's users.

### A lifecycle move is authorised against the consent, never on the role

The five `POST /consents/{id}/<verb>` routes are authorised against the record rather than the
role, as `POST /consents/requests` and `PATCH`/`DELETE /participants/me/users/{identifier}` already
are: holding the admitted role and a well-formed identifier still gets a caller nowhere on its own.
`@Secured` decides which kind of caller may reach the handler; which *record* that caller may move
is decided in `ConsentLifecycleService` against the consent itself, and the two refusals are
deliberately different.

A `USER` operation — `revoke`, `refuse`, `re-confirm`, and a subject's own `resume` — resolves the
record through the subject's own id and answers `404`. A consent that does not exist and a consent
belonging to somebody else are the same `404` with the same message, so a user walking identifiers
learns nothing about which ones are real, exactly as `POST /consents/requests` refuses an unknown
and an unaffiliated user identically. The consent id is never the authority: it selects the record,
the token selects whose records are selectable.

A `PARTICIPANT` operation — `terminate` — reads the consent and then requires the caller to be
named as its provider or its consumer, and answers `403` when it is neither. Both parties may end
it: the provider holds the terms and the consumer is where the data would flow, so each has an
arrangement to end and a stranger has none. The refusal is `403` rather than `404` because a
participant is a registered, accountable identity acting in the dataspace under its own name, and a
party that *should* be a party has to be able to tell a mistyped id from a consent recorded against
a notice it is not on — a uniform `404` would collapse those two into one unactionable answer. The
caller is additionally resolved through `ParticipantPrincipal.requireActive()`, so a deregistered
participant is refused before the consent is read at all.

A `CATALOG` relay on `resume` is held to the `USER` shape rather than the participant one: an
unknown consent and a consent whose subject is not the one the body names are the same `404`, so a
catalog cannot learn whose consent an identifier addresses — see
[ADR 0010](adr/0010-a-catalog-relayed-resume-names-the-subject.md).

`EndpointRoleMatrixIT` sweeps only the role dimension of these routes; that a consent of the wrong
owner or the wrong party is refused is pinned by the lifecycle integration tests, because no role
matrix can express it.

## Participant onboarding

A participant is onboarded by its identity provider, not by this service. The provider's operator
issues the organisation an OAuth2 client and configures two things onto the tokens it mints: the
participant-identifier claim — `claims.participant-identifier`, `IDP_CLAIM_PARTICIPANT_IDENTIFIER`,
default `participant_id` — carrying the opaque identifier that names the organisation, and a
provider-side role that `role-mapping.participant` (`IDP_ROLE_PARTICIPANT`, default
`consent-participant`) translates to `PARTICIPANT`. Nothing here is negotiated with the Consent
Manager: there is no login endpoint, no credential is accepted and none is stored.

The client then fetches a token with the client-credentials grant and presents it as
`Authorization: Bearer <token>`. The worked request and response live on the `bearerAuth` scheme in
[`api/components/security.yaml`](../api/components/security.yaml), which is the published contract.

Registration is the first call that token makes: `POST /participants` with the organisation's
self-description. There is no approval workflow and no pending state. Until it succeeds the token
authenticates normally — `PrincipalResolutionFilter` resolves a `ParticipantPrincipal` whose
`participants` row is absent — but every other participant-scoped operation reads the row through
`ParticipantPrincipal.requireRegistered()` (or `requireActive()`, which also refuses a deregistered
one) and so answers `403` naming the unregistered-participant case; `CallerScope.of()` refuses it
on the user-search routes for the same reason. Reaching `POST /participants` is the single reason
the row-absent shape is resolved at all rather than rejected in the filter.

A token granting `PARTICIPANT` but carrying no participant identifier is rejected with `401` when
that is its only role, and authenticates but answers `403` on participant-scoped operations when it
also grants another role whose identifier it does carry — the identifier is required of the *acting*
role, see [ADR 0006](adr/0006-identifier-claim-is-required-of-the-acting-role.md).

**No JWKS endpoint is served here yet.** The Consent Manager is a resource server: it verifies
tokens against each issuer's published key set and holds no signing key of its own today. Receipt
signing and the key set arrive with TICKET-011, which owns `GET /.well-known/jwks.json`
(US-PM-008) end-to-end — the route and the key material both — so nothing should expect to fetch
keys from this module in the meantime.

## Erasure verification

`DELETE /users/me` strips the subject's identifier. A record kept for its consents (see
[ADR 0007](adr/0007-erasure-pseudonymises-the-user-and-retains-the-audit-trail.md)) carries
`users.erasure_verifier` = `<salt>.<mac>`, where `mac` is
`base64url(HMAC-SHA256(ERASURE_VERIFICATION_SECRET, salt || identifier))` without padding. It
confirms a candidate identifier the asker already holds; it reveals none on its own and is not
computable without the secret.

The secret belongs to the operator — outside the database, never with a participant. Unset (the
default) means erased records carry no verifier; rotating it retires every verifier written under
the old one. The service only ever writes the column: there is no query path to it, and adding one
would turn erasure into a rename. The check is run by hand:

```bash
VERIFIER=$(psql -Atc "select erasure_verifier from users where identifier = '<pseudonym>'")
printf '%s' "${VERIFIER%%.*}$IDENTIFIER" \
  | openssl dgst -sha256 -hmac "$ERASURE_VERIFICATION_SECRET" -binary \
  | basenc --base64url | tr -d '='   # equals ${VERIFIER#*.} iff that identifier consented
```

## Outbound fetches

Two clients leave this service. `ContractGateway` reaches one configured host — the Contract
Service — so its only exposure is the one the operator chose, and an outage there is fatal in one
place only: generation, where `UpstreamServiceException` becomes `502`. A retrieval degrades
instead — `PrivacyNoticeResolver` catches that exception so `GET /privacy-notices/{id}` reports
`contract` and `purposes` as `UNAVAILABLE`, which is why that operation declares no `502`.

`SelfDescriptionClient` is different in kind. It fetches absolute URLs this service reads out of
a document it does not own, which is a server-side request forgery primitive unless it is
fenced. `SelfDescriptionClient.permits` is that fence, applied before anything is sent: the scheme
must be `https` (`http` only under `SELF_DESCRIPTION_ALLOW_PLAINTEXT_HTTP`), the host must appear
in `SELF_DESCRIPTION_ALLOWED_HOSTS` when that list is non-empty, and every address the host
resolves to must lie outside loopback, link-local and private ranges. Redirects are not followed,
so an admitted host cannot hand the fetch on to one that was never admitted.

One residual stays open, and naming it is the point of this paragraph. The address check resolves
the host, and the connection resolves it again, so a name whose answer changes between the two is
still followed — nothing short of connecting through the pre-resolved address closes that, and a
catalog is named by DNS. `SELF_DESCRIPTION_ALLOWED_HOSTS` is the mitigation where it matters: a
deployment that pins the hosts it reads from is not exposed to a name it never admitted in the
first place.

`SELF_DESCRIPTION_MAX_DOCUMENTS` and `SELF_DESCRIPTION_MAX_DURATION` bound the fan-out, and are a
control rather than a tuning knob. The walk is over lists this service does not author — the
offerings a contract names, and the resources each offering names — so without a ceiling one
authenticated `GET /privacy-notices/{id}` reaches as far as whoever published them decided, and
without a deadline its worst case is that ceiling times the read timeout. Either bound stops the
walk and reports the `dataResources` section `UNAVAILABLE`, which is the same degradation every
other failure takes.

A host named in `SELF_DESCRIPTION_ALLOWED_HOSTS` is waived from the private-address refusal, and
only from that one: naming a host there is an operator's statement about their own network and
outranks the address heuristic, which is what lets a deployment read a catalog it hosts itself. An
empty list — the default — waives nothing. The list is operator configuration; no registration
path writes it.

The client never throws out of the service. A refusal, a failure and an unparseable document are
all `Optional.empty()`, which surfaces as an `UNAVAILABLE` section of the resolved notice rather
than an error, so an unreachable catalog can never become this service's `5xx`.
`SelfDescriptionGuardTest` pins the fence and `SelfDescriptionCacheIT` pins that nothing a refusal
or a failure produced is cached.

## Spec layout

`api/openapi.yaml` is the root document and `$ref`s into `api/components/`:
`components/security.yaml` (the bearer token contract), `components/schemas/`, `components/responses/`.
The `copy-openapi-spec` execution copies the whole `api/` tree into `target/classes/static/` so the
relative `$ref`s resolve for Swagger UI.

Path items are written **inline** in the root document; only schemas, responses and the security
scheme are externalised. `SpecReferenceResolutionTest` fails the build on an externalised path item,
because a path item behind a `$ref` hides its `security`, `x-roles` and `x-principal` from
`SpecSecurityConsistencyTest` and so hides the route's access rule from the test that checks it.
The consent flow follows the rule: `/consents` and `/consents/requests` are inline, while
`GrantConsent`, `ConsentRequest` and the shared `Consent` response schema live under
`components/schemas/`.

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

Integration tests are named `*IT` and run under `maven-failsafe-plugin`, so `./mvnw verify` runs
them together with the unit suite; `./mvnw test` runs the unit tests alone. There is no
integration-only flag: Surefire and Failsafe share the `skipTests` property, and no property
separating the two is wired in `pom.xml`.
