# ADR 0007 — Erasure pseudonymises the user and retains the consent audit trail

- **Status:** Accepted.
- **Date:** 2026-10-06
- **Ticket:** TICKET-004 (Taiga #68), step 8
- **Relates to:** `DELETE /users/me` in `api/openapi.yaml`, `UserService.erase`, and the
  `ON DELETE RESTRICT` on `consents.user_id` in `V1__initial_schema.sql`.

## Context

A data subject can ask this service to erase them. Taken literally — delete the row and everything
that points at it — that would delete the consents too, and the consents are the whole point of the
service: they are the record of what a participant was permitted to do with that person's data, and
of when that permission started and ended. Destroying them destroys the only evidence that
processing which already happened was lawful, including processing the data subject may later want
to challenge. The schema says so already: `consents.user_id` is `ON DELETE RESTRICT`, so the
database refuses to let the user row be deleted out from under a consent.

At the same time the request has to actually erase something. A soft-delete flag on a row that still
carries an email address and a subject identifier is not erasure; neither is leaving the global
identifier in place, because that identifier is exactly how a participant or an identity provider
would find the person again.

So the question is what to erase, what to keep, and what to put in the identifier column, which is
`NOT NULL` and unique and therefore cannot simply be emptied.

## Decision

Erasure pseudonymises the user record and retains the consent records.

- **Erased:** `email`, `first_name` and `last_name` are set to null, and `identifier` is replaced.
  Every `user_participants` row for the user is deleted, ending every affiliation.
- **Revoked:** every consent still `GRANTED` moves to `REVOKED` and gains a `CONSENT_REVOKED` event,
  attributed to the service rather than to a person — there is no longer a person to attribute it
  to — with the reason recorded in the event detail.
- **Retained:** the `consents` and `consent_events` rows, with their original ids, statuses, frozen
  snapshots and timestamps. They keep pointing at the user row, which is why that row survives.
- **The replacement identifier is a freshly generated opaque value** under the reserved prefix
  `urn:consent-manager:erased:`, suffixed with a UUIDv7 from the existing `UuidGenerator`.
- **The whole thing is one transaction.** `UserService.erase` is the only `@Transactional` method in
  that class: a half-erased subject — unlinked but still named, or renamed with consents still
  granted — is worse than one not erased at all. It is also the only write there that has no
  insert-and-catch to recover from, so the enclosing transaction costs nothing (see the class note
  on why the registration paths must stay outside one).
- **The pseudonym is published exactly once**, in the response to the erasure, because it is the
  only handle left onto the retained consent records.

### Why the pseudonym is opaque and not a hash

The obvious alternative is `sha256(identifier)`: stable, collision-free, and it lets an operator
re-derive the pseudonym from a known identifier. That last property is the problem. Subject
identifiers and email addresses are drawn from a small, guessable space, so a hash of one is
reversible by enumeration: anyone holding the erased row and a list of candidate identifiers
recovers the person. A keyed hash only moves the secret, and a service that holds the key holds the
ability to undo the erasure on request. A value that was never a function of the identifier cannot
be inverted by anyone, with or without a key, which is the property erasure actually needs.

## Consequences

- An erased subject who authenticates again is a new person to this service: the identifier their
  token carries no longer matches any row, so just-in-time provisioning inserts a fresh user. Their
  old consents stay behind, attached to a record nothing can name. This is intended, and the
  integration test asserts it rather than leaving it to be discovered.
- Erasure is irreversible, including by the operator. There is no path back from the pseudonym to
  the identifier, so an erasure issued in error cannot be undone and the subject has to start over.
- The consent trail remains queryable by consent id, by participant and by privacy notice — every
  access path except the one through the person. Reports that count consents per participant are
  unaffected by an erasure; reports that join through `users` lose their subject, as they must.
- Erasure stays within the `ON DELETE RESTRICT` the schema imposes, so no migration and no cascade
  rule changes with it. A later ticket that wants a hard delete would have to argue with this ADR
  and with the foreign key at once.
- `DELETE /users/me` never answers `404`. The route requires a `USER` token, and the principal
  resolution filter provisions the row before the handler runs, so a subject this service has never
  seen is registered and erased in the same request.
