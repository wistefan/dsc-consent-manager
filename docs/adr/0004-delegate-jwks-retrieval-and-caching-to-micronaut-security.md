# ADR 0004 — Retrieve, cache and verify against JWK Sets with `micronaut-security-jwt`

- **Status:** Accepted.
- **Date:** 2026-10-01
- **Ticket:** TICKET-003 (Taiga #67), step 4
- **Supersedes:** [ADR 0002](0002-own-identity-provider-registry-on-nimbus.md) in the part
  that keeps "Nimbus for JWS verification and JWKS caching in step 4", and implementation
  plan convention 6 in its original wording ("custom validation, not declarative JWKS").
  Everything else ADR 0002 decides — the `consent-manager.identity-providers` schema, the
  trust list being fixed at startup, per-provider claim paths and role mapping — still
  stands, as does [ADR 0003](0003-use-micronaut-security-oauth2-for-openid-discovery.md)
  for discovery.
- **Relates to:** [ADR 0001](0001-delegate-oidc-discovery-and-jwks-to-micronaut-security.md),
  whose survey of framework behaviour is the evidence used below.

## Context

Step 4 shipped as `JwksKeySource`: a `com.nimbusds.jose.jwk.source.JWKSource` per resolved
issuer, built with `JWKSourceBuilder` and configured with a per-provider cache TTL, a
rate-limited refetch for unknown `kid` values, retry and outage tolerance, plus two
Micrometer counters — roughly 300 lines of this repository's own code sitting in front of
an identity provider's key endpoint.

On review of PR #5 the maintainer rejected it, for the second ticket running:

> This PR again tries to implement functionality that is covered by the micronaut oauth
> lib. Do not do this. And please update the plan to ensure this does not happen in future
> prs.

The reasoning in ADR 0002 that led here was: the *declarative* configuration block
`micronaut.security.token.jwt.signatures.jwks.<name>` does not fit this service, therefore
the framework's JWKS support does not fit this service. The first half is correct; the
second does not follow, and that is the mistake this ADR corrects. ADR 0003 already drew
the same distinction for discovery — **a declarative block that does not fit is a reason to
construct the framework's components programmatically, never a reason to write a
replacement for them.**

## Why the declarative block genuinely does not fit

Two properties of `JwksSignatureConfigurationProperties` and the factory that consumes it,
verified against `micronaut-security-jwt` 5.4.0:

1. **The URL is bound at startup.** The block takes a literal `url` per entry. This
   service does not know a provider's `jwks_uri` at startup: it is whatever the
   provider's OpenID metadata document says, and that document is fetched asynchronously
   so an identity provider outage cannot stop the service from booting (ADR 0003, AC 3).
2. **Every configured endpoint becomes a *global* verifier.** The module registers each
   JWKS signature as a `SignatureConfiguration` bean and the validator tries a token
   against each in turn until one verifies, without ever reading the token's `iss`. With
   two providers on the trust list, that lets provider B mint a token that authenticates
   as provider A. The ticket's trust model requires the opposite: a token is checked
   against the keys of the issuer it claims, and against no others.

Neither property is about *fetching*, *caching*, *`kid` matching* or *signature
verification*. They are both about **routing** — which URL, and which token gets offered
to which key set.

## Decision

Use `micronaut-security-jwt`'s components, reached through their public interfaces rather
than through the declarative block, and write only the routing.

| Concern | Who does it |
|---|---|
| HTTP request for the JWK Set | `JwksClient` / `DefaultJwkSetFetcher` (module) |
| Caching the JWK Set | `CacheableJwkSetFetcher`, `@CacheConfig("jwks")` on Micronaut Cache (module) |
| Matching the token's `kid`, selecting candidate keys | `JwksSignatureUtils.matches` (module) |
| Verifying the JWS against each candidate | `JwkValidator` / `ReactiveJwksSignature` (module) |
| Describing one trusted provider as a JWKS endpoint | `JwksSignatureConfigurationAdapter` (≈60 lines here) |
| Deciding *whose* keys a token may be checked against | `IssuerSignatureVerifier` (≈80 lines here) |

`IssuerSignatureVerifier` resolves the token's `iss` through `IdentityProviderRegistry`
first, and only then constructs a `ReactiveJwksSignature` over that one provider's
adapter. An issuer that is not on the trust list, or whose discovery has not succeeded,
is answered `false` with no outbound request at all — which is also what closes the
global-verifier hole above.

`JwksKeySource`, `JwksKeySourceTest` and `JwksKeySourceIT` are deleted.

## Consequences

**The per-provider `jwks-cache-ttl` knob is removed.** The module's cache is a single
Micronaut Cache named `jwks`, keyed by `(providerName, url)`, so entries do not collide
between providers but the *lifetime* is one global setting:
`micronaut.caches.jwks.expire-after-write`, exposed as `IDP_JWKS_CACHE_TTL` and defaulting
to 60 seconds (see the rotation-latency note below). The per-provider knob is deleted from the configuration schema,
`application.yml`, `application-test.yml` and `.env.sample` rather than left in place to be
silently ignored. `JwksSignatureConfiguration#getCacheExpiration()` is
`@Deprecated(forRemoval = true, since = "4.11.0")` ("Not used. JWKS is cached via Micronaut
Cache") and is not read on this path; the adapter returns the module's own default for it.

That single TTL carries both halves of what step 4 owed, and it is the only lever over
either. In steady state, **for as long as the provider answers**, this service issues at
most one JWK Set request per provider per TTL whatever arrives — because the cache is never
invalidated on a miss, **a flood of tokens bearing `kid` values no provider ever published
costs no outbound request at all** (AC 14). (The bound is a steady-state one: a burst of
concurrent *first-ever* verifications for one provider can each miss before the first load
is cached, since Micronaut's `@Cacheable` over a reactive return type does not deduplicate
in-flight loads. That is bounded by concurrency at that one instant, not by traffic, and it
is the cold-start case only.)

**The cache does not bound the request rate while a provider's key endpoint is failing, and
that gap is closed in the routing.** An earlier revision of this ADR claimed the TTL bounded
the request rate unconditionally. It does not. Verified against micronaut-security-jwt 5.4.0
and micronaut-cache-core 6.1.1: `HttpClientJwksClient.load` does
`.onErrorResume(HttpClientException.class, t -> Mono.empty())`, and `CacheInterceptor`'s
reactive path ends in `.switchIfEmpty(... asyncCacheInvalidate(asyncCache, key, errorHandler) ...)`
— an empty result is not stored, and the key is invalidated. So a failed fetch is never
cached, and once the entry has expired with the endpoint unreachable or answering 5xx,
*every* verification issues a fresh request with no rate limit and no backoff: 1:1
amplification aimed at an already-unhealthy provider.

The fix stays on this service's side of the line this ADR draws. `IssuerSignatureVerifier`
already owns the routing decision — whether a provider's keys are consulted at all — so
after a lookup fails to produce a key set it stops consulting that provider for
`KEY_SET_UNAVAILABLE_COOLDOWN` (10s) and rejects its tokens outright, which bounds the
outage case at one probe per provider per window. No key material is retained across the
window: holding a last-known-good JWK Set would be a second cache alongside the module's and
would keep honouring keys the provider may have just withdrawn. Tokens from a provider whose
keys cannot be read are rejected either way; the cooldown only decides how much traffic this
service relays onto a provider that cannot answer. An open window is never extended by
further traffic, so a recovered provider is always probed again within one window.

**The same TTL is the whole of the rotation latency, and that is the expensive half of the
trade.** The module has no refresh-on-miss path — `clearCache` has no caller on the
verification path and `CacheableJwkSetFetcher` declares no `@CacheInvalidate` — so from the
moment a provider starts signing with a newly published key until the cached set expires,
*every* token signed by that key is rejected. Keycloak's default rotation publishes the new
key and starts signing with it at the same moment, so that window is a total authentication
outage for this service, not a degraded mode. The deleted `JwksKeySource` refetched on an
unknown `kid` and so had near-zero rotation latency; delegating to the module gives that up.

**The default TTL is therefore 60 seconds, not the 5 minutes first proposed.** The cost of
a short TTL is one JWK Set request per provider per minute, which no provider notices; the
cost of a long one is minutes of blanket 401s after every rotation. With the request rate
bounded regardless of traffic, there is nothing on the other side of the trade worth
minutes of outage. AC 13 is met either way; 60s is the operationally defensible point.
Anyone raising `IDP_JWKS_CACHE_TTL` is buying rotation downtime with it.

**This is strictly stronger than the rate limiter it replaces.** The automated review of
PR #5 established that Nimbus's `RateLimitedJWKSetSource` opens a window with `counter = 1`
*and allows that call*, then allows one more — two requests per window reach the provider,
not the one the deleted javadoc claimed. The cache allows zero.

**One advisory finding against `JwksKeySource` is resolved by the move; one is inherited.**
Both concerned its `kid` handling. Two keys sharing one `kid` no longer pick a winner:
`JWKMatcher.keyID` selects both and `JwksSignatureUtils.verify` accepts the token if the
signature verifies against *any* candidate (`matches.stream().anyMatch(...)`). Trying each
candidate is not key confusion — the signature still has to verify, and the candidate list
never leaves the issuer the token claimed.

The kid-less-provider finding is **not** fixed, and an earlier revision of this ADR claimed
otherwise. Read against the 5.4.0 sources, `JwksSignatureUtils.matches` adds a `keyID`
constraint only when the *token* carries a `kid` (`if (keyId != null) { builder =
builder.keyID(keyId); }`), and when the resulting selection is empty `verify` returns false
with no fallback. So a provider that publishes kid-less keys is supported only in the
sub-case where it also mints kid-less tokens — those are offered every published key. A
provider publishing keys without a `kid` while minting tokens that carry one remains
unusable, exactly as under `JwksKeySource`. That is now the module's behaviour rather than
ours to change: no such provider is on the trust list, and supporting one would mean an
upstream change or a revision of this ADR, not a local matcher.

**Observability is the one thing kept.** The deleted class's fetch counters are replaced by
`consentmanager.token.signature.verifications`, tagged `provider` and `outcome`, plus a WARN
line naming the `kid` that no key verified — which is what tells a missed rotation apart
from forged traffic. That was the substance of a third advisory finding, and it survives the
rewrite.

**`com.nimbusds:nimbus-jose-jwt` and `io.micronaut.reactor:micronaut-reactor` become explicit
`<dependency>` entries.** Both are named directly in main sources from this step on; arriving
transitively through `micronaut-security-jwt` would let an unrelated upgrade break this build
with no change here.

**Convention 6 of the implementation plan is rewritten** to state the general rule rather
than this instance of it, so the next step does not have to rediscover it: where the
framework ships a component, use that component; a declarative block that does not fit is a
reason to implement the framework's interfaces, and any genuine gap goes through an ADR
*before* a replacement is written.
