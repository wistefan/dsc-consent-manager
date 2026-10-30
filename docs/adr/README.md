# Architecture Decision Records

An ADR records one significant design decision: the context that forced it, what was decided, its
consequences and the alternatives turned down. Each lives in its own file named
`NNNN-kebab-title.md`, numbered in the order it was written, and carries `Status`, `Date` and
`Ticket` header fields. An ADR is a record: once accepted, its decision text is never edited; a later
ADR supersedes it instead, and the status below says which.

Up to the [Documentation](../README.md) index · the [project README](../../README.md).

## Identity and token validation

| ADR | Title | Status | Summary |
| --- | --- | --- | --- |
| [0001](0001-delegate-oidc-discovery-and-jwks-to-micronaut-security.md) | Delegate OIDC discovery and JWKS handling to `micronaut-security-oauth2` | Rejected; superseded by [0002](0002-own-identity-provider-registry-on-nimbus.md) | Proposed declaring each trusted issuer to Micronaut Security for discovery, JWKS and verification; kept as the verified record of the framework's behaviour. |
| [0002](0002-own-identity-provider-registry-on-nimbus.md) | Own the identity provider registry, build it on Nimbus | Partly superseded by [0003](0003-use-micronaut-security-oauth2-for-openid-discovery.md) (discovery), [0004](0004-delegate-jwks-retrieval-and-caching-to-micronaut-security.md) (JWKS retrieval, caching, JWS verification) | The trust list is the service's own type, fixed at startup, with per-provider claim paths and role mapping. |
| [0003](0003-use-micronaut-security-oauth2-for-openid-discovery.md) | Perform OpenID discovery with `micronaut-security-oauth2` | Accepted; supersedes part of [0002](0002-own-identity-provider-registry-on-nimbus.md) and revisits [0001](0001-delegate-oidc-discovery-and-jwks-to-micronaut-security.md)'s discovery half | Discovery runs through one `DefaultOpenIdProviderMetadataFetcher` per trust-list entry, constructed directly; its reasoning is extended to JWKS by 0004. |
| [0004](0004-delegate-jwks-retrieval-and-caching-to-micronaut-security.md) | Retrieve, cache and verify against JWK Sets with `micronaut-security-jwt` | Accepted; supersedes part of [0002](0002-own-identity-provider-registry-on-nimbus.md) | JWK Set retrieval, caching and verification use the module's components through their public interfaces; the service writes only the routing. |
| [0005](0005-own-token-validator-on-micronaut-security-jwt.md) | Own `TokenValidator`, built on the module's components, instead of `NimbusJsonWebTokenValidator` | Accepted | `ConsentManagerTokenValidator` is the only `TokenValidator` and drives the pipeline; everything mechanical underneath stays the module's. |
| [0006](0006-identifier-claim-is-required-of-the-acting-role.md) | Require the identifier claim of the acting role, not of every granted role | Accepted | Authentication requires one granted role's identifier (else `401`); authorization requires the acting role's identifier (else `403`). |

## Users, participants and the data subject

| ADR | Title | Status | Summary |
| --- | --- | --- | --- |
| [0007](0007-erasure-pseudonymises-the-user-and-retains-the-audit-trail.md) | Erasure pseudonymises the user and retains the consent audit trail | Accepted | Erasure closes every open consent, removes every affiliation, then deletes the user or pseudonymises it if a retained consent still refers to it. |
| [0008](0008-deregistration-retains-the-participant-and-closes-its-open-business.md) | Deregistration retains the participant and closes its open business | Accepted | Refused with `409` while a grant is outstanding; otherwise closes open business, archives notices, then deletes the row or retains it marked deregistered, in one transaction. |
| [0009](0009-only-the-data-subject-grants-consent.md) | Only the data subject grants consent, and a request confers nothing | Accepted | `POST /consents` is a pure `USER` route reading the subject from the token alone; bounded by 0010's single relay. |
| [0010](0010-a-catalog-relayed-resume-names-the-subject.md) | A catalog may relay a resume, and the trail names it as the relay | Accepted | A `CATALOG` caller may relay a decision the subject took elsewhere, on one operation, fenced by conditions enforced in code. |

## Consent records, receipts and notifications

| ADR | Title | Status | Summary |
| --- | --- | --- | --- |
| [0011](0011-a-lifecycle-move-is-recorded-here-and-notified-elsewhere.md) | A lifecycle move is recorded here and notified elsewhere | Accepted; partly superseded by [0016](0016-participant-notifications-use-a-transactional-outbox.md) (participant delivery) | A move is effective in this service's records when its transaction commits; the service does not claim it is what stops processing. |
| [0012](0012-a-consent-receipt-names-its-subject-and-is-dated-by-its-grant.md) | A consent receipt names its subject to the parties and is dated by its grant | Accepted | A receipt names its subject on either route, carries every MUST key without inventing values, and takes `consentTimestamp` from the granting event. |
| [0013](0013-the-query-side-scopes-by-predicate-and-signs-what-it-attests.md) | The query side scopes by predicate and signs what it attests | Accepted; partly superseded by [0014](0014-a-grant-receipt-is-signed-once-and-travels-beside-its-signature.md) (receipt signing) | Read scope is a `WHERE` predicate, never a post-filter; receipts render from the frozen snapshot and are signed with a key published at the origin root. |
| [0014](0014-a-grant-receipt-is-signed-once-and-travels-beside-its-signature.md) | A grant receipt is signed once and travels beside its signature | Accepted; supersedes part of [0013](0013-the-query-side-scopes-by-predicate-and-signs-what-it-attests.md) | A detached JWS over the RFC 8785 canonical form of the whole receipt travels beside it, not inside it. |
| [0015](0015-subject-notifications-are-best-effort-and-informational.md) | Subject notifications are best-effort and informational | Accepted | Email is a notification, never part of authentication: no token, id, code or state-changing link in any message. |
| [0016](0016-participant-notifications-use-a-transactional-outbox.md) | Participant notifications use a transactional outbox | Accepted | Every consent write path enqueues its participant notifications in the same transaction; delivery is leased, retried durably and signed. |

## Adding an ADR

Take the next free number, name the file `NNNN-kebab-title.md`, and give it the same header fields
(`Status`, `Date`, `Ticket`) as the others. Add its row to the matching table here in the same
commit. If it supersedes part of an earlier ADR, say so in both rows.
