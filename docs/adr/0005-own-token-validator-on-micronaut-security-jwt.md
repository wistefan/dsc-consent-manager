# ADR 0005 — Own `TokenValidator`, built on the module's components, instead of `NimbusJsonWebTokenValidator`

- **Status:** Accepted.
- **Date:** 2026-10-02
- **Ticket:** TICKET-003 (Taiga #67), step 5
- **Relates to:** [ADR 0003](0003-use-micronaut-security-oauth2-for-openid-discovery.md) (discovery)
  and [ADR 0004](0004-delegate-jwks-retrieval-and-caching-to-micronaut-security.md) (JWKS
  retrieval, caching and signature verification). This ADR decides only the layer above those:
  which component drives the validation pipeline.
- **Evidence pinned to:** `micronaut-security` / `micronaut-security-jwt` **5.4.0**, the version
  this repository pins. A version bump invalidates every source citation below until re-checked.

## Context

Review of PR #6 asked the right question:

> What's the concrete reason for not using the `NimbusJsonWebTokenValidator`?

It is the fair question to ask, because the two ticket reviews before it rejected hand-written
replacements for module machinery, and convention 6 of the implementation plan now forbids them.
`ConsentManagerTokenValidator` has to justify itself against that rule.

The relevant module types in 5.4.0 are:

- `TokenValidator` — the public SPI. `TokenAuthenticationFetcher` collects **every**
  `TokenValidator` bean and takes the first `Authentication` any of them emits.
- `NimbusReactiveJsonWebTokenValidator` / `NimbusJsonWebTokenValidator` — the module's JWT
  validators, which `JwtTokenValidator` adapts to that SPI.
- `NimbusJsonWebTokenSignatureValidator` — what those delegate the signature check to.
- The `GenericJwtClaimsValidator` beans: `ExpirationJwtClaimsValidator`,
  `NotBeforeJwtClaimsValidator`, `SubjectNotNullJwtClaimsValidator`, `IssuerJwtClaimsValidator`,
  `AudienceJwtClaimsValidator`.

## Decision

Keep `ConsentManagerTokenValidator` as this service's **only** `TokenValidator`, implementing the
module's public SPI, and keep the module's own JWT validators switched off through
`micronaut.security.token.jwt.nimbus.validator` and `.reactive-validator`.

This is a decision about the **pipeline driver only**. Everything mechanical underneath it is still
the module's: OIDC discovery through `DefaultOpenIdProviderMetadataFetcher` (ADR 0003); JWK Set
retrieval, caching and `kid`/algorithm matching through `JwkSetFetcher`, `CacheableJwkSetFetcher`,
`JwkValidator` and `ReactiveJwksSignature` (ADR 0004). This service writes no key cache, no
fetcher, no `kid` matcher and no signature check.

## Why the module's validator cannot drive this pipeline

### 1. Its signature check never reads `iss`, which is cross-issuer key confusion

`NimbusJsonWebTokenSignatureValidator.validateSignature(SignedJWT)` sorts **all** registered
`SignatureConfiguration` beans by whether they support the token's `alg` and returns `true` as soon
as any one of them verifies it. The token's `iss` claim is never consulted — by construction, since
the signature is checked before the claims are.

That is correct for the single-provider deployment the module's declarative configuration
describes. This service's trust list holds a configurable number of providers (convention 5). With
two trusted providers registered as global verifiers, a token minted by provider B and claiming
`iss: A` verifies against B's keys and authenticates as a caller from A. Provider B then speaks for
provider A's users.

The routing decision — *this token may only be offered this one issuer's keys* — is the only thing
`IssuerSignatureVerifier` adds, and no module component makes it. `ConsentManagerTokenValidator`
exists to resolve `iss` against the trust list **before** any key is touched.

### 2. Its claims validators hold one global issuer, audience and clock skew

`JwtClaimsValidatorConfigurationProperties` binds `micronaut.security.token.jwt.claims-validators`
with `private String issuer` and `private String audience` — single scalars. `AudienceJwtClaimsValidator`
and `IssuerJwtClaimsValidator` are `@Requires`-gated on those two properties and validate against
them for every token the application sees.

`aud` and `clock-skew` are per provider in `consent-manager.identity-providers`, and must be, since
each provider issues to its own audience and its clock is its own. One global value cannot express
that. The same holds for the configurable per-provider claim names (`user-identifier`,
`participant-identifier`, `roles`) and the per-provider `role-mapping` — concepts the module's
`JwtAuthenticationFactory` has no place for at all.

### 3. The validator types are not public API

`NimbusJsonWebTokenValidator` and `NimbusReactiveJsonWebTokenValidator` are package-private in
`io.micronaut.security.token.jwt.nimbus`, as is their shared `AbstractJsonWebTokenValidator`
superclass and `NimbusJsonWebTokenSignatureValidator`. They cannot be injected by type, subclassed
or composed from this repository's packages. The public contracts the module offers for this layer
are the interfaces `TokenValidator`, `JsonWebTokenValidator` and `ReactiveJsonWebTokenValidator` —
and implementing a published interface is exactly what convention 6 prescribes.

### 4. Left registered beside this one, it fails open on `alg: none`

`AbstractJsonWebTokenValidator`'s constructor latches
`noSignatures = imperativeSignatureConfigurations.isEmpty() && reactiveSignatureConfigurations.isEmpty()`,
and `validateSignature(PlainJWT)` then reports an unsigned token as validly signed whenever that
flag is set. Both collections **are** empty in this application: `IssuerSignatureVerifier` builds
its per-issuer `ReactiveJwksSignature` instances programmatically rather than publishing them as
beans, precisely so that no verifier is global (reason 1).

Because `TokenAuthenticationFetcher` takes the first `Authentication` **any** registered
`TokenValidator` returns, a module validator left enabled is not a redundant second opinion but a
laxer parallel path — and here one that would admit `alg: none`. Being stricter than a validator
running beside you buys nothing. `UnsignedTokenRejector` closes the same hole independently by
keeping the imperative collection non-empty; the two guards are kept side by side so that undoing
either alone cannot re-open it, and `TokenSignatureEnforcementIT` pins both.

### 5. Rejections must be indistinguishable (US-ID-008)

An unregistered issuer, a configured-but-unresolved issuer and a bad signature must all produce one
generic `401` that lets nobody enumerate the trust list. That is a property of how the pipeline
composes its failures — a single empty publisher — not of any individual check, so it is not
something a set of independently-registered claims validators can be configured to provide.

## Alternatives considered

- **Declare each provider under `micronaut.security.token.jwt.signatures.jwks.<name>` and keep the
  module's validator.** Rejected: it registers every provider as a *global* verifier, which is
  reason 1 exactly. It also binds a fixed set of URLs at startup, while a provider's `jwks_uri` is
  only known once asynchronous discovery resolves it (ADR 0003).
- **Add a `GenericJwtClaimsValidator` that checks `iss` against the registry.** Rejected: claims
  validators run *after* `validateSignature`, so the token has already been accepted against the
  wrong issuer's keys by the time such a validator sees it. It would reject the forgery in
  reason 1 only when the attacker also lies about `iss` — which they have no reason to do.
- **One `TokenValidator` bean per configured provider.** Rejected: `TokenAuthenticationFetcher`
  fans out across all of them and takes the first success, which reproduces the global-verifier
  behaviour at a different layer, and the registry's membership is fixed at startup while
  resolution state is not.

## Consequences

- `ConsentManagerTokenValidator` owns the pipeline ordering and the per-provider claim checks, and
  nothing else. It is roughly 300 lines, none of them cryptographic: parse, allow-list the
  algorithm, resolve the issuer, delegate the signature, then check time, audience, subject, roles
  and the role-implied identifier.
- The module's validators stay off. The `nimbus.validator` / `nimbus.reactive-validator` lines in
  `application.yml` are a security control, not tidying, and the comment there says so.
- Both guards against an unsigned token — the disabled validators and `UnsignedTokenRejector` — are
  pinned by `TokenSignatureEnforcementIT`, including a control assertion that an unsigned token
  *does* authenticate once all of them are undone together.
- Upgrading `micronaut-security` requires re-reading `AbstractJsonWebTokenValidator` and
  `NimbusJsonWebTokenSignatureValidator` before this document may be relied on again.
