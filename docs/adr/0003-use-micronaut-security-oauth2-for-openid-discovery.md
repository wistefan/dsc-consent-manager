# ADR 0003 — Perform OpenID discovery with `micronaut-security-oauth2`

- **Status:** Accepted.
- **Date:** 2025-10-01
- **Ticket:** TICKET-003 (Taiga #67), step 3
- **Supersedes:** [ADR 0002](0002-own-identity-provider-registry-on-nimbus.md), in the
  part that decides *who issues the discovery request*. Everything else ADR 0002
  decided — the `consent-manager.identity-providers` schema, the trust list being fixed
  at startup, per-provider claim paths and role mapping, and Nimbus for JWS verification
  and JWKS caching in step 4 — still stands.
- **Supersedes:** [ADR 0001](0001-delegate-oidc-discovery-and-jwks-to-micronaut-security.md)'s
  rejected status only insofar as the discovery half is concerned; its record of framework
  behaviour remains accurate and is the evidence used below.

## Context

Step 3 shipped with `IdentityProviderRegistry` composing the discovery request itself: a
`HttpRequest.GET` against the configured `discovery-url`, deserialized into a two-field
`OpenIdProviderMetadata` record declared in this repository. ADR 0002 justified that by
the gaps in `micronaut-security-oauth2`'s *declarative* OpenID client support.

On review of PR #4 the maintainer rejected that reasoning twice, explicitly:

> The OIDC part should not be individually implemented, use the micronaut dependency
> instead. If that violates some of the Acceptance Criteria, I accept it.

The decision to re-examine was therefore not ours. What remained was to find the form of
delegation that costs the least.

## The two forms of delegation, and why they differ

`micronaut-security-oauth2` offers its OpenID support at two levels, and ADR 0002
only ever assessed the first.

**The configuration binding** — `micronaut.security.oauth2.clients.<name>.openid.*` —
models a provider as a **login client**. It requires a client id, and the beans it
switches on install authorization-code login routes, a token-exchange client and an
anonymous `/.well-known/oauth-protected-resource` document. The ticket's mandate is a
stateless resource server that issues no tokens, stores no credentials and keeps no
sessions; a client id and a login route are exactly the things it must not have. This
level is still rejected, for the reason ADR 0002 gave.

**The discovery components** — `OpenIdProviderMetadataFetcher`,
`DefaultOpenIdProviderMetadataFetcher` and `DefaultOpenIdProviderMetadata` — are public
types that consume a public interface, `OpenIdClientConfiguration`. They know nothing
about login. The fetcher composes the metadata URL from that interface's `issuer` and
`configurationPath`, issues the request and deserializes the document. That is the whole
of "the OIDC part" this service needs, and it is usable without the configuration binding.

ADR 0002 conflated the two: it rejected the module because of what its *configuration*
implies, and concluded that the *discovery code* had to be rewritten too. That does not
follow, and this ADR corrects it.

## Decision

Perform discovery with `DefaultOpenIdProviderMetadataFetcher`, one per trust-list entry,
constructed directly rather than through the module's configuration binding.

`OpenIdClientConfigurationAdapter` presents one `IdentityProviderConfiguration` in the
shape `OpenIdClientConfiguration` describes. The configured `discovery-url` is split at
its origin — scheme, host and port become `issuer`, the rest becomes
`configurationPath` — so recomposing them yields the operator's URL verbatim. The login
members of the interface (authorization, token, registration, user-info, end-session) are
reported absent: a resource server calls none of them and the fetcher reads none of them.

Consequences:

- This service no longer composes a `.well-known` path, issues a discovery request or
  declares a metadata type. `OpenIdProviderMetadata` is deleted.
- `micronaut-security-oauth2` becomes a `compile` dependency, and it does not publish a
  route. Its login, callback and logout routes are `@Requires`-gated on
  `micronaut.security.oauth2.clients.*`, which this service never sets, so they stay
  unregistered on their own. Its RFC 9728 protected-resource-metadata beans are **not**
  gated on `clients.*` and default to *enabled*: `ProtectedResourceMetadataController`
  serves `/.well-known/oauth-protected-resource`, and
  `ResourceMetadataWwwAuthenticateChallengeProvider` appends a `resource_metadata`
  parameter to every 401 challenge. The two are gated on
  `micronaut.security.oauth2.protected-resource-metadata.enabled` and
  `.www-authenticate` *independently* — switching the controller off does not switch the
  challenge provider off — so `application.yml` sets both to `false` explicitly.
- The fetcher reports a request that did not complete as a `DisabledBeanException`, named
  for what the module would do about it: disable that provider for the life of the
  process. That is precisely the AC 3 violation ADR 0002 worried about — but it is only a
  consequence of letting the *module* own the lifecycle. Owning the lifecycle ourselves,
  we catch it, and the registry retries on its existing backoff schedule. Startup still
  never blocks and never fails on an identity provider.

## What this service still owns, and why

Delegation stops at the request. The registry keeps:

- the **trust list** and its `PENDING`/`RESOLVED`/`FAILED` state, because membership must
  be fixed at startup (convention 5) and the module has no such concept;
- the **byte-for-byte issuer comparison** (AC 2), which the module does not perform;
- the **retry schedule and readiness reporting** (AC 3), which the module replaces with
  giving up once;
- the **per-issuer routing** the token validator looks up, since the module's
  claims-validators hold one global issuer and audience.

None of those is "the OIDC part". They are the trust model around it.

## Acceptance criteria impact

None. AC 2 and AC 3 are still met, by the code described above. The maintainer's offer to
accept an AC violation was not needed.
