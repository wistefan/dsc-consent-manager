# ADR 0002 — Own the identity provider registry, build it on Nimbus

- **Status:** **Partly superseded** by
  [ADR 0003](0003-use-micronaut-security-oauth2-for-openid-discovery.md), which moves the
  discovery request itself onto `micronaut-security-oauth2`'s
  `DefaultOpenIdProviderMetadataFetcher`, and by
  [ADR 0004](0004-delegate-jwks-retrieval-and-caching-to-micronaut-security.md), which moves
  JWK Set retrieval, caching and JWS verification onto `micronaut-security-jwt` and removes
  the per-provider `jwks-cache-ttl` knob. What this ADR decides about the configuration
  schema, the trust list being fixed at startup, per-provider claims and role mapping still
  stands; its choice of hand-built Nimbus components for JWKS does not.
- **Date:** 2025-10-01
- **Ticket:** TICKET-003 (Taiga #67), step 2
- **Superseded in part by:** [ADR 0003](0003-use-micronaut-security-oauth2-for-openid-discovery.md),
  [ADR 0004](0004-delegate-jwks-retrieval-and-caching-to-micronaut-security.md)
- **Supersedes:** [ADR 0001](0001-delegate-oidc-discovery-and-jwks-to-micronaut-security.md)
- **Keeps:** the ticket's `consent-manager.identity-providers` configuration schema. Its
  reading of implementation plan convention 6 ("custom validation, not declarative JWKS")
  was rewritten by ADR 0004.

## Context

Step 2 was first implemented by delegating OpenID discovery and JWKS retrieval to
`micronaut-security-oauth2`, moving `issuer` to
`micronaut.security.oauth2.clients.<name>.openid.issuer` and dropping `discovery-url`
and `jwks-cache-ttl` from the service's own schema. ADR 0001 records that option, the
framework behaviour behind it and the gaps it leaves. It was proposed, reviewed and
**rejected**; this ADR records what replaces it and why.

The evidence against delegation — all of it verified against the
**micronaut-security 5.4.0** / **micronaut-security-oauth2 5.4.0** artefacts (the version
`micronaut-parent:5.2.0` pins) and written up in ADR 0001 — is:

| Requirement | `micronaut-security-oauth2` |
|---|---|
| AC 2: discovered `issuer` compared byte-for-byte with the configured one | not performed; the discovered `jwks_uri` is used unverified |
| AC 3: discovery retried with backoff, readiness `DOWN`, liveness `UP` | `DefaultOpenIdProviderMetadataFetcher` turns a connection failure into a `DisabledBeanException`, which `DefaultBeanContext.initializeContext` catches and logs at DEBUG — one attempt, no retry, no backoff, no readiness signal, and the provider is *silently* left without metadata or a signature configuration until the process restarts |
| AC 13: per-provider JWKS cache TTL | `JwksUriSignatureFactory` sets only the URL, so cache expiration is global and not configurable per provider |
| AC 14: rate-limited refetch on an unknown `kid` | `JwksSignatureUtils.verify` does not refetch |
| Per-provider `iss`/`aud` validation | `claims-validators.issuer` / `.audience` hold a single global value each (and `IssuerJwtClaimsValidator` suffix-compares rather than equals); `NimbusReactiveJsonWebTokenSignatureValidator` is handed a flat `List<SignatureConfiguration>` with no issuer binding and tries each until one verifies, so a second provider's key would authenticate a token claiming the first provider's issuer |

Two further consequences of the delegated design were live in the rejected
implementation and are worth stating, because they are what moved this from a style
preference to a correctness one:

- the trust list was effectively **capped at one provider** (anything else re-opened the
  cross-issuer key-confusion path above), and that provider had to be named `primary`;
- the module enables an anonymous `/.well-known/oauth-protected-resource` endpoint and a
  `resource_metadata="..."` addition to the `WWW-Authenticate` challenge by default, both
  of which publish the configured issuer URLs and had to be switched off explicitly.

So the delegation supplies the two easiest parts of the problem — an HTTP GET of a
discovery document and RSA signature verification — while supplying none of AC 2, 3, 13
or 14, and step 3 would have had to *replace* a framework bean that disables itself
rather than extend a seam it owns.

## Decision

Keep plan convention 6. The trust list is this service's own type, bound from the
ticket's schema, and discovery, key caching and verification are built on
**`com.nimbusds:nimbus-jose-jwt`**, which `micronaut-security-jwt` already brings
transitively.

1. `micronaut-security-oauth2` is **removed** from `pom.xml`. `micronaut-security-jwt`
   stays: it supplies the security filter, the `@Secured` rules, the bearer-token reader
   and the `TokenValidator` SPI that `ConsentManagerTokenValidator` plugs into in step 5,
   plus Nimbus itself.
2. The trust list is `consent-manager.identity-providers.<name>`, bound with
   `@EachProperty` onto `IdentityProviderConfiguration`, carrying `issuer`,
   `discovery-url`, `audience`, `clock-skew`, `jwks-cache-ttl`, the per-provider `claims`
   paths and the per-provider `role-mapping` — the ticket's §1 schema, with no key
   relocated into a framework namespace and none rejected.
3. Step 3 fetches each `discovery-url` with a Micronaut `HttpClient`, compares the
   document's `issuer` byte-for-byte with the configured one, and retries transport
   failures with bounded exponential backoff while readiness reports `DOWN`.
4. Step 4 builds **one Nimbus `JWKSource` per resolved issuer** with
   `JWKSourceBuilder.create(jwksUri).cache(ttl, refreshTimeout).rateLimited(cooldown)
   .retrying(...).outageTolerant(...)`, which supplies the per-provider TTL (AC 13), the
   rate-limited unknown-`kid` refetch (AC 14) and single-flight refresh under concurrency
   without a hand-written cache.
5. Step 5's `ConsentManagerTokenValidator` resolves the provider from the token's `iss`
   **first** and verifies against that provider's key source only, so a key valid at
   issuer A can never authenticate a token claiming issuer B.

### The one documented deviation: a name-keyed map, not a list

The plan writes `@EachProperty(value = "...", list = true)`. This implementation binds a
**name-keyed map** instead: `consent-manager.identity-providers.<name>`.

The reason is operator-facing, not internal. All configuration here is externalised
through environment variables, and a list entry is addressed by ordinal
(`CONSENT_MANAGER_IDENTITY_PROVIDERS_0_ISSUER`). Micronaut does not bind that spelling,
so with `list = true` the documented way to add a provider through the environment does
not work and — because `@EachProperty` simply produces no bean for a key it cannot read —
fails **silently**, leaving a resource server running with a smaller trust list than its
operator believes it has. A name gives each entry a stable, readable address that binds
from the environment (`CONSENT_MANAGER_IDENTITY_PROVIDERS_PRIMARY_ISSUER`) and makes a
per-provider override replace a value rather than append a second entry.

Nothing else changes: the number of providers is still configurable, every entry is still
validated, and `IdentityProviderRegistryValidator` still refuses to start on an empty
list. The name is carried on the bean so diagnostics can name the offending block.

## Consequences

- Steps 3, 4 and 5 of the implementation plan stand as written. Nothing in them is
  "largely superseded"; the earlier handoff note saying so was wrong and is withdrawn.
- There is no framework bean to replace and no `DisabledBeanException` path to work
  around: an unreachable identity provider is this service's own state machine
  (`PENDING` / `RESOLVED` / `FAILED`), which is what makes AC 3's "start anyway, readiness
  `DOWN`" reachable at all.
- `UnsignedTokenRejector` is still required, and for a reason independent of this
  decision: `AbstractJsonWebTokenValidator` latches
  `noSignatures = imperativeSignatureConfigurations.isEmpty() && reactiveSignatureConfigurations.isEmpty()`
  in its constructor and reports a `PlainJWT` as validly signed when that flag is set. No
  signing key is registered until step 5, and none is registered during an identity
  provider outage after that, so the collection must be kept non-empty.
- Until step 5 lands, **no token authenticates at all**: no signature configuration can
  verify anything, so every bearer token is rejected. That is the fail-closed direction
  and it is pinned by `TokenSignatureEnforcementIT`.
- The interim global `claims-validators.issuer` / `.audience` entries that the rejected
  design needed are gone. They were a protocol-stripped suffix match, not the
  byte-for-byte comparison AC 4 requires, and nothing now depends on them.
- Writing discovery and key caching ourselves is more code than configuring a module.
  Only the parts the framework does not provide are hand-written: the HTTP fetch, the
  issuer comparison, the retry schedule and the per-issuer routing. The cache, the rate
  limiter, the single-flight refresh and the JWS verification are Nimbus's, because that
  is where a subtle concurrency bug would be a security bug.

## Addendum — reaffirmed on PR #4 (step 3), 2026-10-01

The step-3 review asked the same question this ADR answers: *why is all of this
individually implemented instead of using the Micronaut OIDC dependency?* The module's
API was re-inspected at the pinned version (`micronaut-security-oauth2:5.4.0`) rather
than taken on trust from the step-2 write-up. Nothing changed:

- `OpenIdProviderMetadataFetcher.fetch()` is the only discovery entry point; it is driven
  eagerly by `@Context @EachBean(OpenIdClientConfiguration.class)` in the `@Internal`
  `OpenIdClientFactory`, so there is no seam to hang a retry schedule or a resolution
  state off — step 3 would have to *replace* that bean, not extend it.
- `JwkSetFetcher`'s caching implementations (`CacheableJwkSetFetcher`,
  `ReactorCacheJwkSetFetcher`) are package-private, and `JwksSignatureConfiguration`
  exposes a single global `getCacheExpiration()`, so AC 13's per-provider TTL and AC 14's
  rate-limited unknown-`kid` refetch have no configuration surface at all.
- There is still no issuer-to-key-set binding anywhere in the module.

What the module *does* supply well — an HTTP GET of a discovery document and RSA
signature verification — this service does not reimplement either: the GET is a plain
Micronaut `HttpClient` call, and every cryptographic operation from step 4 on is Nimbus,
which `micronaut-security-jwt` brings transitively. `micronaut-security-jwt` itself is
kept and is what `ConsentManagerTokenValidator` plugs into. The decision is narrow: the
*trust list* is ours because the module does not have one.
