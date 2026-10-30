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

## `com.seamware.consentmanager.signing`

| Class | Role |
| --- | --- |
| `SigningKeyStore` | Loads the signing key and retired-keys JWKS eagerly at startup, each from a mounted file (`RECEIPTS_SIGNING_KEY_FILE`, `RECEIPTS_RETIRED_VERIFICATION_KEYS_FILE`) or inline (`RECEIPTS_SIGNING_KEY`, `RECEIPTS_RETIRED_VERIFICATION_KEYS`); holds the one private key and the published key list (active first). |
| `CanonicalJsonSerializer` | RFC 8785 canonical form of a receipt: the bytes a JWS covers. |
| `ConsentSigningService` / `JwsConsentSigningService` | Signs a receipt or a participant notification as a detached JWS (`alg`, `kid`, no `jku`, and the `typ` its `SignatureType` names: `JOSE` for a receipt, `consent-notification+jws` for a notification); `verify` / `verifyCanonical` require that `typ` and answer `false`, never throw, on any mismatch. |
| `SignedReceipt` | A receipt, its JWS, the signing `kid` and the canonical text that was signed. |
| `ReceiptSigningException` | Any signing failure; unchecked and not a `DataAccessException`, so it fails the grant with `500`. |

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
| `GET /.well-known/jwks.json` (origin root, outside `API_PREFIX`; `Cache-Control: public, max-age=3600`) | anonymous | `ReceiptSigningController` |
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
| `GET /participants/me/notifications` | `PARTICIPANT` | `ParticipantNotificationController` |
| `POST /privacy-notices` | `PARTICIPANT` | `PrivacyNoticeController` |
| `POST /privacy-notices/by-contract` | `PARTICIPANT` | `PrivacyNoticeController` |
| `GET /privacy-notices/{id}` | `USER`, `PARTICIPANT`, `CATALOG` | `PrivacyNoticeController` |
| `POST /consents` | `USER` | `ConsentController` |
| `POST /consents/requests` | `PARTICIPANT` | `ConsentRequestController` |
| `POST /consents/{id}/revoke` | `USER` | `ConsentLifecycleController` |
| `POST /consents/{id}/refuse` | `USER` | `ConsentLifecycleController` |
| `POST /consents/{id}/terminate` | `PARTICIPANT` | `ConsentLifecycleController` |
| `POST /consents/{id}/re-confirm` | `USER` | `ConsentLifecycleController` |
| `POST /consents/{id}/resume` | `USER`, `CATALOG` | `ConsentLifecycleController` |
| `GET /consents/me` | `USER` | `ConsentQueryController` |
| `GET /consents/me/{id}` | `USER` | `ConsentQueryController` |
| `GET /consents` | `PARTICIPANT` | `ConsentQueryController` |
| `GET /consents/{id}` | `PARTICIPANT` | `ConsentQueryController` |
| `GET /consents/check` | `PARTICIPANT` | `ConsentQueryController` |
| `GET /contracts/available` | `USER` | `ContractDiscoveryController` |
| `GET /contracts/available/{role}` | `PARTICIPANT` | `ContractDiscoveryController` |

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

This `403`/`404` asymmetry is intended and stays: the `terminateConsent` description in
`api/openapi.yaml` specifies the `403` and its rationale, `ConsentLifecycleService.requireParty`
implements it, and `OwnershipMatrixIT` pins it as the FOREIGN row of `terminate`.

A `CATALOG` relay on `resume` is held to the `USER` shape rather than the participant one: an
unknown consent and a consent whose subject is not the one the body names are the same `404`, so a
catalog cannot learn whose consent an identifier addresses — see
[ADR 0010](adr/0010-a-catalog-relayed-resume-names-the-subject.md).

`EndpointRoleMatrixIT` sweeps only the role dimension of these routes; that a consent of the wrong
owner or the wrong party is refused is pinned by the lifecycle integration tests, because no role
matrix can express it.

### A consent read is scoped in the query, never after it

Every read on the consent query routes puts the caller into the `WHERE` clause. A subject reads
`user_id = :subject`, and a participant reads `provider_id = :caller OR consumer_id = :caller`, on
every lineage hop too. A record outside that scope is never loaded. A `userIdentifier` the caller
has no consent for yields an empty page rather than a `403`. An id outside the scope is a `404`
identical to an unknown one. `GET /consents/check` resolves through `requireActive()`, answers an
unknown subject and a subject with no grant identically, and is sent `Cache-Control: no-store`. See
[ADR 0013](adr/0013-the-query-side-scopes-by-predicate-and-signs-what-it-attests.md).

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

**Receipt signing is the one key this service holds.** It issues no tokens, but every consent
receipt it renders is answered as `{receipt, jws}`: a detached JWS (RFC 7515 Appendix F) *beside*
the receipt, over the RFC 8785 canonical form of the whole receipt. The receipt carries no
`signature` member. `JwsConsentSigningService` (package `signing`, behind `ConsentSigningService`)
signs with the private JWK that `SigningKeyStore` loads at startup. The
JWS header names only `alg`, `kid` and `typ: JOSE` — no `jku` — so a verifier fetches the JWKS from
the deployment it obtained the receipt from, or one it otherwise trusts, rather than from a URL the
signed object chose.

The same key signs participant notifications (ADR-0016), so the two kinds of document are kept
apart by `typ`. A receipt keeps `typ: JOSE`, which every receipt issued so far carries; a
notification carries `typ: consent-notification+jws`. `verifyCanonical` takes the expected
`SignatureType` and refuses a header naming the other, so a receipt signature never verifies as a
notification or the reverse, and the [integrator guide](participant-notifications.md) tells
recipients to require that `typ`. A notification's payload does not name its recipient, so a
participant can forward a genuine one to another's endpoint; the guide tells recipients to ignore
consents they are not a party to.

The Kantara receipt (`format=receipt-kantara`) is signed **once**, in the transaction that records
the grant. `ConsentWriter` hands every write whose recorded status is `GRANTED` to
`ConsentReceiptIssuer`, which stores the canonical text and JWS in `consent_receipts`; reads serve
those stored bytes and never sign again. A signing failure rolls the grant back and answers `500`, so
no grant is recorded without its receipt. The ISO/IEC 29184 record (`format=receipt`) carries the
live status, so it is signed on every read instead.

The public keys are published anonymously under their `kid`s at
`https://<host>/.well-known/jwks.json`: the origin root RFC 8615 defines, **not** under
`API_PREFIX`. `@OriginRoot` exempts `ReceiptSigningController` from the API prefix; the security
filter and `PrincipalResolutionFilter` still see the route, and its `security: []` declaration keeps
it anonymous. The response is sent `Cache-Control: public,
max-age=3600`, so a verifier may hold a key set up to one hour old. Two things follow: a key that
stopped signing must stay published for as long as receipts it signed are in circulation, and a new
key must be published one cache lifetime *before* it signs.

Each key is read from one of two forms. In production it is a mounted file: `RECEIPTS_SIGNING_KEY_FILE`
and `RECEIPTS_RETIRED_VERIFICATION_KEYS_FILE` hold paths, and the file is read as UTF-8 and trimmed.
For local and test use the same JSON may be set inline in `RECEIPTS_SIGNING_KEY` and
`RECEIPTS_RETIRED_VERIFICATION_KEYS`. Setting both forms of one key fails startup. So does a missing,
unreadable or empty file, which never falls back to an ephemeral key. The steps below name the
inline variables; with mounted files, edit the file content instead.

Startup fails without a usable private key unless `RECEIPTS_EPHEMERAL_SIGNING_KEY=true` permits a
throwaway one (dev and test only), and receipts signed by such a key stop verifying on restart. A
private member, an unsupported key type or a repeated `kid` in the retired-keys JWKS also fails
startup. Every such message names the variable, or the file variable and path, that supplied the
value, and no startup message quotes key material.

Generate a key with OpenSSL — ES256 (EC P-256) is preferred, RS256 (RSA, at least 2048 bits, 3072
recommended) is for verifiers that cannot handle EC — and convert it to the private JWK the variable
takes with the [`step` CLI](https://smallstep.com/docs/step-cli/):

```bash
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out receipts-signing.pem
# or: openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out receipts-signing.pem
step crypto jwk create receipts-signing.pub.json receipts-signing.json \
  --from-pem receipts-signing.pem --alg ES256 --use sig --kid receipts-2026-10 \
  --no-password --insecure          # --alg RS256 for the RSA key
RECEIPTS_SIGNING_KEY=$(jq -c . receipts-signing.json)
jq -c '{keys: [.]}' receipts-signing.pub.json   # this key's entry for the published-keys list
```

`step crypto jwk create … --kty EC --crv P-256 --alg ES256 --use sig --kid <kid> --no-password
--insecure` (no `--from-pem`) generates the key directly.

**Rotation takes two deploys,** because of the JWKS cache:

1. **Pre-publish.** Add the new key's *public* JWK to `RECEIPTS_RETIRED_VERIFICATION_KEYS`, leave
   `RECEIPTS_SIGNING_KEY` unchanged, and deploy. The new key is published but signs nothing.
2. **Wait** at least one `max-age` (one hour), so every cached JWKS includes the new key.
3. **Switch.** Set `RECEIPTS_SIGNING_KEY` to the new private JWK. In the same change, remove the new
   key's entry from `RECEIPTS_RETIRED_VERIFICATION_KEYS` — a `kid` present in both fails startup
   with the duplicate-key-id message — and add the old key's public JWK there. Deploy.
4. **Retire** the old public JWK only when no stored or distributed receipt still needs it. Receipts
   are long-lived, so normally it stays.

Switching in a single deploy makes receipts signed by the new key fail verification for up to an
hour at any verifier holding a cached JWKS. `RECEIPTS_RETIRED_VERIFICATION_KEYS` is therefore every
published key that does not sign — upcoming as well as retired — despite its name. Why the JWS
travels beside the receipt, why the Kantara receipt is stored and the ISO record is not, and why the
configuration stays a private JWK plus a public JWKS are recorded in
[ADR 0014](adr/0014-a-grant-receipt-is-signed-once-and-travels-beside-its-signature.md); why the
header carries no `jku` in
[ADR 0013](adr/0013-the-query-side-scopes-by-predicate-and-signs-what-it-attests.md).

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

Erasure also deletes the subject's stored grant receipts (`consent_receipts`) in the same
transaction. A signed receipt names the subject as `piiPrincipalId`, and pseudonymising it would
break its signature; erasure already revokes every grant, so no Kantara receipt can be read for the
subject afterwards. Copies the subject or a party saved earlier still verify against the published
keys — erasure withdraws this service's copy, not the signature.

## Outbound fetches

Three clients leave this service. `ContractGateway` reaches one configured host — the Contract
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

`ParticipantWebhookClient` is the third, and the same kind: it `POST`s signed participant
notifications (ADR-0016, [integrator guide](participant-notifications.md)) to the
`endpoints.consentNotification` URL a participant registered for itself. It applies the same fence
before connecting, through `client/NetworkAddressPolicy`, which both clients share: the URL must be
`https`, the host must appear in `PARTICIPANT_NOTIFICATIONS_ALLOWED_HOSTS` when that list is
non-empty (a listed host is waived from the address check, as above), and every address must lie
outside loopback, link-local, site-local, any-local, multicast, CGNAT (`100.64.0.0/10`) and ULA
(`fc00::/7`). A refused host abandons the notification with the fixed reason
`endpoint not permitted`, which is all the history route shows, so it never reveals whether an
internal host answers. Redirects are not followed; a `3xx` abandons. The response body is never
read, only the status.

The JDK resolver has no timeout and the connect timeout does not cover it, so the lookup runs on a
bounded executor under `PARTICIPANT_NOTIFICATIONS_RESOLVE_TIMEOUT`. A lookup that fails or times
out is *retried* as `name resolution failed`, not abandoned, because the JDK reports a transient
SERVFAIL the same way as a missing name and a brief DNS outage must not drop a revocation. That
leaves a second residual beside the rebinding one: whether a notification is retried or abandoned
shows the participant whether its hostname *resolves* in this service's DNS view (for instance,
whether a cluster-internal service name exists), though never whether anything listens there. The
DNS-rebinding window between the check and the connection is open exactly as for
self-descriptions. A non-empty `PARTICIPANT_NOTIFICATIONS_ALLOWED_HOSTS` closes both, since no
unlisted name is resolved or reached. `NetworkAddressPolicyTest` and `ParticipantWebhookClientTest`
pin the fence.

## Outbound email

Subject notifications (`…consentmanager.notification`, ADR-0015) are informational and confer
nothing. No message carries a token, a consent id or a link that acts. The only link is the bare
`NOTIFICATION_CLIENT_URL`, which startup requires to be an absolute `http(s)` URL. Subjects are
constants, so no caller-supplied value reaches a header. The transport requires STARTTLS and
always checks the server's identity. `SMTP_INSECURE_TRUST_ALL_CERTIFICATES` is the only way to
relax that, and it is logged at WARN on every startup. `SMTP_STARTTLS=false` combined with
`SMTP_USER` refuses to start. HTML views use `th:text` only. Because mail clients autolink even
escaped text, `LinkNeutralizer` defuses `scheme://`, `mailto:` and `www.` in the legal name, the
notice title and the user's name. A bare domain is left intact, and that risk is accepted. Logs
name the user id and the consent id, never the address. `SmtpUserNotificationServiceIT` scans
delivered mail for any URL the service did not configure.

## Correlation IDs

`observability/CorrelationIdFilter` runs ahead of every other filter, the security filter included,
so a `401`/`403` carries the ID too. It adopts an inbound `X-Request-ID` only when it matches
`[A-Za-z0-9._~-]{1,128}`. Any other value is replaced by a fresh UUIDv7, so a caller cannot inject a
forged log line or an unbounded value. The ID goes to the SLF4J MDC as `correlationId`, which is
propagated to every thread the request runs on. It is echoed in the `X-Request-ID` response header
and set as `correlationId` on every problem detail. It identifies a request and nothing more:
it is never a credential and never authorises anything.

## Server error detail

A `5xx` problem detail publishes a fixed sentence, never the exception message, because messages
name internal hosts, dependencies and identifiers. `error/ServerErrorDetail` is the one place that
decides this, for unhandled exceptions, `5xx` `ApiException`s (the `502` included) and
framework-raised errors. `ERRORS_EXPOSE_EXCEPTION_DETAIL`
(`consent-manager.errors.expose-exception-detail`) publishes the message instead. It is `false` in
`application.yml` and `true` only in the `dev` environment. A stack trace is never published either
way.

## Management port and metrics authentication

Health, `/metrics` and `/prometheus` are served at the origin root of `MANAGEMENT_PORT` (default
`9090`), never on the API port. Expose that port only inside the cluster. `/health/liveness` and
`/health/readiness` are anonymous. The aggregate `/health` shows details to authenticated callers
only, except in `dev`.

`METRICS_AUTHENTICATION_ENABLED` (default `true`, in every environment, `dev` included) drives
`endpoints.metrics.sensitive` and `endpoints.prometheus.sensitive`. While it is on, both need a valid
bearer token from a trusted provider, and `MetricsSensitiveEndpointRule` admits any such token
there and refuses it on every other sensitive endpoint. Set it to `false` only where network
policy, a service mesh or a scraping sidecar protects the management port instead.
`ManagementEndpointSecurityIT` pins both settings and where each route resolves.

## Log hygiene

Nothing at INFO or above quotes a bearer token, key material, the pseudonym key, a user's external
identifier, email or name, a request path or a query string.

- **Failures.** The three error handlers log one `FailureSummary` line at ERROR: the method, the
  matched route template (`/consents/{id}`, never the concrete path), the problem type, the
  exception class chain and the root cause's top frame. Exception messages are free text that
  quotes identifiers (a unique violation reads `Key (identifier)=(alice@example.com)`), so the
  message and full throwable go to DEBUG only, under the same `correlationId`.
- **People.** A line that must correlate a person at INFO or above logs
  `LogPseudonymizer.pseudonym(identifier)`: HMAC-SHA-256 under `LOG_PSEUDONYM_KEY`, truncated hex.
  A plain hash would fall to a list of candidate emails or DIDs and would stay linkable after an
  erasure. Startup fails when the key is unset or short. Pseudonyms are stable while the key is,
  and rotating it unlinks every pseudonym already logged.
- **Allowed as is.** The internal `userId` UUID and participant (legal-person) identifiers are
  logged unchanged. The UUID is opaque and is unlinked from the person by erasure, which is the
  reasoning of [ADR 0007](adr/0007-erasure-pseudonymises-the-user-and-retains-the-audit-trail.md).
  A participant identifier names an organisation, not a natural person. A JWS `kid` names a key,
  not key material.

## Spec layout

`api/openapi.yaml` is the root document and `$ref`s into `api/components/`:
`components/security.yaml` (the bearer token contract), `components/schemas/`, `components/responses/`.
The `bundle-openapi-spec` execution (`src/build/java/OpenApiBundler.java`) inlines every file the
root's `components` section names and rewrites each external `$ref` to an internal pointer. The
single document it writes is copied to `target/classes/static/openapi.yaml` and served unchanged and
anonymously at `GET /openapi.yaml`, which is also what Swagger UI loads. A `$ref` to a file that no
root component names fails the build.

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
- `JwksIT` — the receipt JWKS is anonymous, cacheable, public-only and lists the active key first;
  receipts signed by the active and by a retired key verify against it with plain Nimbus.
- `KeyMaterialLeakIT` — no log event, MDC value, throwable chain or response body carries any
  private member of the signing key, across signing, the ephemeral-key warning and signing failures.
- `LogLeakIT` — across a participant registering a sentinel person, a token user's JIT
  provisioning, grant, revoke and erasure, a malformed token, a token in a query string and two
  forced server failures, no event at INFO or above quotes a token, a private key member, the
  pseudonym key, or the identifier, email or name of either person.
- `ConsentReceiptIssuanceIT`, `SigningFailureIT` — every grant path stores exactly one receipt that
  reads never re-sign; a signing failure rolls the grant back and answers `500`.

Integration tests are named `*IT` and run under `maven-failsafe-plugin`, so `./mvnw verify` runs
them together with the unit suite; `./mvnw test` runs the unit tests alone. There is no
integration-only flag: Surefire and Failsafe share the `skipTests` property, and no property
separating the two is wired in `pom.xml`.
