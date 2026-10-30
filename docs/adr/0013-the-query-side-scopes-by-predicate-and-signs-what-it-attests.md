# ADR 0013 — The query side scopes by predicate and signs what it attests

[Documentation](../README.md) › [ADR index](README.md) › ADR 0013

- **Status:** Accepted. Superseded in part by [ADR-0014](0014-a-grant-receipt-is-signed-once-and-travels-beside-its-signature.md) (receipt signing).
- **Date:** 2026-10-15
- **Ticket:** TICKET-010 (Taiga #74), step 8
- **Relates to:** `GET /consents/me`, `GET /consents/me/{id}`, `GET /consents`, `GET /consents/{id}`,
  `GET /consents/check`, `GET /contracts/available`, `GET /contracts/available/{role}` and
  `GET /.well-known/jwks.json` in `api/openapi.yaml`; `ConsentQueryService`, `ConsentLineageService`,
  `ConsentReceiptService` and `ContractDiscoveryService` in `…consentmanager.service`; `ReceiptSigner`
  in `…consentmanager.security`; `ConsentRepository`; [ADR-0008](0008-deregistration-retains-the-participant-and-closes-its-open-business.md),
  whose deregistered participant the check refuses; and [ADR-0012](0012-a-consent-receipt-names-its-subject-and-is-dated-by-its-grant.md),
  which decides what a receipt says.

## Context

This ticket added the read side of consents. It is the service's only runtime control point. Before
a participant processes a subject's data it asks this service whether it may, and the answer is
only as good as the scoping behind it. Every read here selects records that belong to someone
else's relationship: the subject's decisions, made with two specific participants. Five questions
had to be decided together because each depends on the same scope.

1. **Where the scope is applied:** in the query, or afterwards on what the query returned.
2. **What a caller learns from a miss:** the answer for an identifier or id it has no standing for.
3. **How fresh a "yes" must be:** whether a validity answer may be cached.
4. **What a receipt is rendered from:** the live notice, or the consent's frozen copy of it.
5. **How a receipt proves itself:** whether it can be verified away from this service.

## Decision

**Scope is a predicate, never a post-filter.** A subject's read carries `user_id = :subject`. A
participant's carries `provider_id = :caller OR consumer_id = :caller`. Both conditions are in the
`WHERE` clause of the statement that reads the rows, and `totalElements` is counted under the same
predicate. A consent outside the caller's scope is therefore never loaded into memory here, and a
missing check in Java cannot expose one. Each lineage hop reapplies the same predicate inside the
single `WITH RECURSIVE` walk. A chain stops at the first record the caller may not see; that
record's id is visible only as the last link's `parentConsentId`. The walk is also bounded by
`consent-manager.consents.lineage-max-depth`, and by its path array if the chain contains a cycle.

**A miss on a listing is an empty page, and a miss on an id is a `404`.** On `GET /consents`, a
`userIdentifier` filter returns an empty `200` page in all of these cases:
- no user holds the identifier,
- the caller is party to no consent for that user,
- the caller is party to consents for that user, but none match the remaining filters.

A `403` there would let a participant probe which identifiers exist. A single-consent read
addressed by an id outside the caller's scope is a `404`, identical to an unknown id, on both the
subject's and the participant's route. This differs from `terminate`'s `403` (see
`docs/security.md`). A read gives the caller nothing to act on that would make the distinction
useful, so it may reveal nothing about a consent it is not a party to.

**The validity check is one statement, uniformly negative, never cached, and refused to a
departed participant.** `GET /consents/check` resolves the subject and the grant in one indexed
lookup. That lookup joins `users` on `uq_users_identifier` and uses the partial unique index
`uq_consents_granted_per_user_notice`, which also guarantees there is at most one row. Only
`GRANTED` counts. A named purpose must equal one of the frozen `purposes` verbatim. An unknown
subject and a known subject with no grant get the same `valid: false` and the same reason. The
response carries `Cache-Control: no-store`, which the specification declares. A stale "yes" is the
worst answer this service can give, and a revocation must take effect on the next check rather
than after a TTL. The caller is resolved through `requireActive()`, not `requireRegistered()`.
A "yes" authorises processing, which is acting in the dataspace, so a deregistered participant is
refused with `403` even though the consents naming it are retained.

**A receipt is rendered from the frozen snapshot.** Receipts in the ISO/IEC 29184 and Kantara
formats read only `consents.snapshot` and `contract_uri`, never the live notice. Archiving or
regenerating a notice therefore cannot change what a subject was shown when they agreed. ADR-0012
decides what the receipt contains.

**A receipt carries a detached JWS, and the key is published at the origin root.**

> **Superseded in part by [ADR-0014](0014-a-grant-receipt-is-signed-once-and-travels-beside-its-signature.md).**
> The JWS now travels beside the receipt as `{receipt, jws}` rather than as a `signature` member,
> the header adds `typ: JOSE`, `ReceiptSigner` became the `signing` package, a Kantara receipt is
> signed once at grant time and stored, and rotation takes two deploys because the JWKS is
> cacheable. The bullets below record the decision as it was taken.

- **What is signed.** `ReceiptSigner` signs the RFC 8785 canonical form of the receipt's other
  members. The compact JWS with an empty payload is added as the receipt's `signature` member.
  The receipt therefore stays plain JSON that conforms to its vocabulary, and a reader that
  ignores signatures can still consume it.
- **The header.** It names only `alg` and `kid`. There is no `jku`, so a verifier obtains keys from
  the deployment it trusts rather than from a URL the signed object supplies.
- **Where the key is published.** The public half is at `GET /.well-known/jwks.json`, anonymous, at
  the RFC 8615 origin root and outside `API_PREFIX`. A holder of a receipt can verify it without a
  credential and without trusting a copy of the receipt.
- **Missing or unusable key.** Startup fails, so a deployment never issues unsigned receipts by
  accident. `RECEIPTS_EPHEMERAL_SIGNING_KEY` is the explicit opt-out for development and tests.
- **Rotation.** A retired key's public entry moves to `RECEIPTS_RETIRED_VERIFICATION_KEYS` and is
  published after the active key. Receipts issued before a rotation keep verifying.

**Contract discovery fails whole.** `GET /contracts/available[/{role}]` reads every candidate
contract through `ContractGateway`. The reads run concurrently but are bounded in concurrency and
in total time. Any upstream failure or deadline answers `502` with no partial list. A list that
silently omits a contract would read as "you take part in nothing else".

## Consequences

- An access rule on the read side is a change to a `ConsentRepository` query, not to a check in a
  service. `ConsentQueryIndexUsageIT` asserts with `EXPLAIN` that every such query, the lineage
  walks included, is served by an index and does not use a sequential scan. A new finder belongs
  in that IT.
- A participant cannot tell "this subject does not exist" from "I have nothing with this subject".
  An integrator debugging an empty page has to check the identifier through `GET /users/{identifier}`,
  which is subject to its own affiliation rules.
- `includeLineage` on a listing costs one recursive query per entry. That cost is bounded by
  `CONSENTS_PAGE_MAX_SIZE × CONSENTS_LINEAGE_MAX_DEPTH`, and both values are configured per
  deployment rather than fixed in the specification.
- Every validity check reaches the database. Freshness was chosen over load. A deployment that
  needs more throughput scales reads; it does not cache answers.
- The receipt signing key is the one private key this service holds (see `docs/security.md`).
  Losing it without first moving its public entry to
  `RECEIPTS_RETIRED_VERIFICATION_KEYS` makes every receipt it signed unverifiable.

## Alternatives considered

- **Load, then check the caller in Java.** Rejected. Every new route would need to remember the
  check. The page count would report rows the caller cannot see. One missed call would leak a
  consent.
- **`403` for an unlinked `userIdentifier`.** Rejected. It turns the listing into an oracle for
  which identifiers exist.
- **A short cache TTL on the validity check.** Rejected. A revoked consent would still read "yes"
  for the length of the TTL, at the one place where that is not acceptable.
- **An attached JWS (the receipt as the JWS payload) or a JWS in a response header.** Rejected.
  The first hides the receipt's vocabulary inside base64url. The second does not survive the
  receipt being saved or forwarded as a document.
- **A `jku` header pointing at the JWKS.** Rejected. A forged receipt could name the forger's own
  key set.
