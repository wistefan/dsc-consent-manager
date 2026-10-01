# ADR 0001 — Delegate OIDC discovery and JWKS handling to `micronaut-security-oauth2`

- **Status:** **Rejected.** Superseded by
  [ADR 0002](0002-own-identity-provider-registry-on-nimbus.md), which keeps implementation
  plan convention 6. Retained because the framework behaviour recorded below was verified by
  source reading and is the evidence for rejecting this option; do not re-litigate it without
  re-reading the sources.
- **Date:** 2025-10-01 (proposed), 2025-10-01 (rejected)
- **Ticket:** TICKET-003 (Taiga #67), step 2
- **Superseded by:** [ADR 0002](0002-own-identity-provider-registry-on-nimbus.md)

This is the single place where the behaviour of the framework we delegate to is written
down. Configuration files point here instead of repeating it: there is one artefact to
re-verify on a Micronaut upgrade, not three copies to drift apart.

Every framework claim below was verified by reading the **micronaut-security 5.4.0**
sources. A version bump invalidates this document until it is re-checked.

## Context

The plan specified a self-contained trust list — `@EachProperty(list = true)` binding
`issuer`, `discovery-url` and `jwks-cache-ttl` onto this service's own type — with
programmatic per-issuer validators built on Nimbus (`JWKSourceBuilder`). Convention 6
ruled out Micronaut's declarative JWKS block because that block configures a *single*
static issuer while the registry holds a configurable *number* of providers.

While implementing step 2 it became clear that `micronaut-security-oauth2` already
performs OpenID discovery and JWKS retrieval per configured client, which covers the
mechanical part of plan steps 3 and 4 with code that is already maintained and tested.

## Decision

Declare each trusted issuer as `micronaut.security.oauth2.clients.<name>.openid.issuer`
and let Micronaut Security perform discovery, fetch the `jwks_uri` and verify signatures.
Keep only what the framework cannot express — audience, clock skew, claim paths and role
mapping — under `consent-manager.identity-providers.<name>`, keyed by the same name.

`IdentityProviderRegistryValidator` joins the two halves at startup and fails fast when
they disagree.

## Consequences

### What this buys

Discovery, `jwks_uri` resolution, JWKS retrieval, key-set caching and RSA/EC signature
verification are not written or maintained here.

### What the framework does **not** do

These are the gaps that remain the scope of steps 3–5. They must not be assumed away.

| Requirement | Status in micronaut-security 5.4.0 |
|---|---|
| AC 2 — discovered issuer compared to configured issuer | **Absent.** `DefaultOpenIdProviderMetadataFetcher.fetch` returns the document unchecked. `OpenIdClientFactory.overrideFromConfig` only back-fills the configured value when the document omits `issuer`; a present-but-mismatched discovered issuer silently wins. |
| AC 3 — retry with backoff, readiness reporting | **Absent.** Discovery is attempted once, when the signature-configuration bean is first created. A failed fetch becomes a `DisabledBeanException`. `JwksUriSignatureFactory` is `@EachBean(DefaultOpenIdProviderMetadata)` and reads `jwks_uri` eagerly, so if the provider is unreachable at that moment no JWKS signature configuration is ever created and **every token is rejected for the lifetime of the process** — while startup succeeds and liveness stays green. |
| AC 4 — byte-for-byte issuer match | **Weaker than it looks.** `IssuerJwtClaimsValidator` delegates to `ClaimsUtils.endsWithIgnoringProtocolAndTrailingSlash`, which strips the scheme and one trailing slash from both sides and evaluates `expected.endsWith(actual)`. A token's `iss` only has to be a *suffix* of the configured issuer, scheme discarded. |
| AC 13/14 — refetch on unknown `kid`, rate-limited | **Absent.** The JWKS verification path fails against the cached key set and does not re-fetch. |
| Per-provider `jwks-cache-ttl` | **Not expressible.** The per-provider `cacheExpiration` knob is `@Deprecated(forRemoval = true, since = "4.11.0")` ("Not used. JWKS is cached via Micronaut Cache"), and `JwksUriSignatureFactory` sets only the JWKS URL. Caching goes through `CacheableJwkSetFetcher`, which is `@CacheConfig("jwks")`, so a single **global** TTL *is* expressible as `micronaut.caches.jwks.expire-after-write`. One cache serves every provider; with none configured the effective expiration is the 60 s default. |
| Issuer-bound signature verification | **Absent, and this one is load-bearing.** `NimbusJsonWebTokenSignatureValidator.validate` iterates every `SignatureConfiguration` and returns on the first that verifies; the reactive path — the one the JWKS beans take — is the same iteration as a `Flux`. Neither reads the token's `iss`. With providers A and B configured, a token signed with **B's** key but carrying A's `iss` verifies on B's keys and then satisfies the global issuer/audience validators, which only pin A. **B can mint tokens that authenticate as A.** |
| Rejection of `alg: none` with no keys | **Inverted.** `AbstractJsonWebTokenValidator` latches `noSignatures = imperativeSignatureConfigurations.isEmpty() && reactiveSignatureConfigurations.isEmpty()` in its constructor and treats a `PlainJWT` as validly signed when that flag is set. See "Unsigned-token guard" below. |

### Interim guards this forced into step 2

Each is scaffolding that a later step removes. They exist because the delegation above
leaves the service exploitable in their absence, not because step 2 wanted them.

- **Exactly one provider, named `primary`.** A multi-entry trust list is a startup
  failure, not a warning, because of the issuer-bound-verification gap above: the second
  provider could forge tokens for the first. Removed in step 5.
- **Global claim validators.** `micronaut.security.token.jwt.claims-validators.issuer`
  and `.audience` are set, because the built-in validator otherwise applies only the
  expiration and subject-not-null checks and would accept any token signed by the trusted
  issuer whatever its `aud`. They hold one global value each, which is the second reason
  the trust list is capped at one entry. `IdentityProviderRegistryValidator` asserts that
  both actually resolve to the sole provider's issuer and audience — they are independently
  settable, so naming the provider `primary` is not on its own enough. Removed in step 5.
- **`UnsignedTokenRejector`.** A `SignatureConfiguration` that matches no algorithm and
  verifies no token, registered so the imperative collection is never empty and the
  `noSignatures` short-circuit above is unreachable. Note the two collections hold
  different beans: the JWKS bean an OpenID client contributes is `ReactiveJwksSignature`
  (`@EachBean(JwksSignatureConfiguration.class) implements ReactiveSignatureConfiguration`),
  **not** a `SignatureConfiguration`, so the imperative collection is otherwise empty even
  when discovery has succeeded. Permanent for as long as Micronaut's validator is in the
  chain.
- **Disabled protected-resource metadata.** `micronaut-security-oauth2` enables
  `ProtectedResourceMetadataController` by default — `@Secured(IS_ANONYMOUS)` on
  `GET /.well-known/oauth-protected-resource`, publishing the configured issuer URLs to any
  caller, against US-ID-008 — and `ResourceMetadataWwwAuthenticateChallengeProvider`, which
  appends `resource_metadata="…"` to every 401. The two flags are independent and both are
  set to `false`. Permanent.

### Cost to later steps

Steps 3 and 4 must still build issuer verification, retry-with-backoff, readiness
reporting, a per-provider JWKS TTL and rate-limited refetch on an unknown `kid`. Because
Micronaut's metadata bean *disables itself* via `DisabledBeanException` rather than
retrying, step 3 needs a way to re-create it, not merely a health indicator that reports
the problem. It is a live possibility that steps 3–5 displace this dependency again.

Steps 3–5 of the plan assume the other design and need re-planning either way.

## Alternatives considered

1. **The planned programmatic registry** (Nimbus `JWKSourceBuilder` per issuer). Expresses
   every acceptance criterion directly — per-provider TTL via `.cache(ttl, timeout)`,
   AC 14 via `.rateLimited(cooldown)`, outage tolerance via `.retrying()`/`.outageTolerant()`,
   single-flight refresh under concurrency — and binds each key set to its issuer, which
   removes the one-provider cap and the forged-issuer exposure. More code here.
2. **Micronaut now, Nimbus later.** What this ADR currently describes. The risk is that the
   seams the plan intended to build on are gone, and the interim guards above have to be
   unwound rather than extended.

## Open question for review

Does the human reviewer accept this departure, with the plan updated to match, or should
step 2 be reworked onto the planned programmatic registry before step 3 begins? The
recommendation from implementation experience is **(1)**: the per-provider acceptance
criteria have to be built regardless, and building them on Nimbus directly avoids both
the one-provider cap and a later migration away from this dependency.
