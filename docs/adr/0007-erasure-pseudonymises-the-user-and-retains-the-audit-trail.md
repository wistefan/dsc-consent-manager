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
of when that permission started and ended. The schema says so already: `consents.user_id` is
`ON DELETE RESTRICT`, so the database refuses to let the user row be deleted out from under a
consent.

At the same time the request has to actually erase something. A soft-delete flag on a row that still
carries an email address and a subject identifier is not erasure; neither is leaving the global
identifier in place, because that identifier is exactly how a participant or an identity provider
would find the person again.

So the question is what to erase, what to keep, and what to put in the identifier column, which is
`NOT NULL` and unique and therefore cannot simply be emptied.

## Decision

Erasure closes everything still open, removes every affiliation, and then either **deletes** the
user record or **pseudonymises** it, depending on whether a retained consent still refers to it.

- **Closed:** every consent still `GRANTED` moves to `REVOKED` and gains a `CONSENT_REVOKED` event;
  every consent still unanswered — `PENDING` or `DRAFT` — moves to `TERMINATED` and gains a
  `CONSENT_TERMINATED` event. Both are attributed to the service rather than to a person — there is
  no longer a person to attribute them to — with the reason recorded in the event detail. Erasure
  leaves nothing about the subject in a state a later lifecycle operation could still advance.
- **Unlinked:** every `user_participants` row for the user is deleted, ending every affiliation.
- **Deleted, when nothing refers to the row:** if no consent names the user, the `users` row is
  deleted outright. There is then no audit trail to anchor, `ON DELETE RESTRICT` cannot bite, and
  deletion is the more complete erasure. This is the common case for a caller who never consented
  to anything — including a caller this service first saw in the very request that erases them,
  since the route's own authentication provisions the row before the handler runs. Keeping a
  renamed husk in that case would be unbounded: a replayed token would mint one per request and
  nothing in this service ever removes them.
- **Pseudonymised, when consents refer to the row:** `email`, `first_name` and `last_name` are set
  to null and `identifier` is replaced by a freshly generated opaque value under the reserved prefix
  `urn:consent-manager:erased:`, suffixed with a UUIDv7 from the existing `UuidGenerator`. The row
  survives for one reason only: the consents need something to point at.
- **Retained:** the `consents` and `consent_events` rows, with their original ids, statuses, frozen
  snapshots and timestamps.
- **The whole thing is one transaction.** `UserService.erase` is the only `@Transactional` method in
  that class: a half-erased subject — unlinked but still named, or renamed with consents still
  granted — is worse than one not erased at all. It is also the only write there that has no
  insert-and-catch to recover from, so the enclosing transaction costs nothing (see the class note
  on why the registration paths must stay outside one).
- **The pseudonym is published exactly once**, in the response to the erasure, and only when there
  is one — a deleted record reports no pseudonym, because nothing is left for one to point at.

### Why the pseudonym is opaque and not a hash

The obvious alternative is `sha256(identifier)`: stable, collision-free, and it lets an operator
re-derive the pseudonym from a known identifier. That last property is the problem. Subject
identifiers and email addresses are drawn from a small, guessable space, so a hash of one is
reversible by enumeration: anyone holding the erased row and a list of candidate identifiers
recovers the person. Keying the hash fixes the enumeration but not the rest: the pseudonym is the
row's unique key *and* is published to the data subject, so it travels further than the key does,
and a derived value is a stable correlator — the same person erased twice would land on the same
pseudonym, re-linking the two records the erasure was meant to separate. A value that was never a
function of the identifier has neither property, which is what the identifier column needs.

Confirming that a *named* person consented is a different requirement, and it gets its own field
rather than a weaker identifier.

### Keeping a keyed verifier, so a named person can still be checked

Taken alone, the previous section proves too much. A consent record is evidence under Art. 7(1)
only if someone can tie it to a person when it legitimately matters — a dispute between a
participant and an individual, a supervisory inquiry, a complaint. The reply "the participant has
its own record" is weaker than it sounds: ISO/IEC 29184 does place the record of consent and of its
withdrawal on the PII controller, so each participant has that obligation in principle, but in this
data space participants delegate it to the consent manager in practice — that is what the service
is for. An erasure that destroys every tie would quietly destroy the evidence they rely on, and
leave a retained trail that is not evidence of anything about a person at all.

So a retained record also keeps `users.erasure_verifier`:
`base64url(HMAC-SHA256(secret, identifier))` over the identifier it is losing.

- **It confirms, it does not reveal.** Given a candidate identifier the asker already holds, the
  operator recomputes the value and compares. The column yields no identifier on its own, and
  without the secret it is not computable at all — a database dump is not enough.
- **The secret lives with the operator.** `ERASURE_VERIFICATION_SECRET`, outside the database,
  never with a participant. Only the authority running the service can perform the check, which is
  the point: the capability is held by the party already accountable for the records.
- **It is never a lookup path.** No repository method, no index, no API operation reads it, and
  provisioning matches on `identifier` alone. A subject who authenticates again after erasure is
  still a stranger: recognising them by their verifier would reveal to the service — and through it
  to participants — that this person was here before, which is exactly what they asked to undo.
- **The honest cost.** For the holder of the key the retained rows are pseudonymous, not anonymous:
  the Recital 26 argument below holds against everyone except the operator, who can test candidates
  and, given a list of identifiers, enumerate them. That is a deliberate trade of a residual
  capability held by exactly one accountable party against evidence that would otherwise be worth
  nothing. Two levers bound it: leaving the secret unset (the default) stores no verifier at all,
  and destroying or rotating the secret retires every verifier written under it, completing the
  anonymisation for those records without touching the trail itself.

### What a consent that names nobody is still worth

The fair objection to all of the above is that a consent record which cannot be traced back to a
human looks like an empty artefact — and if it is empty, retaining it is not a trade-off against the
erasure right, it is just a failure to honour it. The honest answer is that such a record loses one
kind of weight and keeps another, and the two must not be conflated.

**It no longer names the person who consented.** GDPR Art. 7(1) requires the controller to be able
to demonstrate that *the data subject* consented. After erasure this service cannot produce the
subject of a retained consent, and will not try: nothing in it resolves a record to a person, so
the trail cannot be used *against* the erased individual, which is the point. What survives is the
narrower confirmation described above — an operator holding both the key and a candidate identifier
can establish that this record was that person's — which is enough for a dispute or an inquiry
raised by someone who already knows who they are asking about, and useless for anything else.

**It remains evidence about the controller's own processing.** GDPR Art. 5(2) accountability is not
about one data subject; it is about whether the operator can show what the system did. The retained
rows still answer "which participant pairs were authorised under which privacy notice, on what
terms, between which dates, and how did each authorisation end" — the questions a supervisory
authority, an audit, or a dispute between two participants actually asks. ISO/IEC 29184's
requirement to keep a record of consent *and of its withdrawal* is satisfied by exactly this: the
`CONSENT_REVOKED` event added by the erasure is itself part of what the standard wants kept, and
deleting the consent would delete the record that it was withdrawn.

**Retaining it is not in tension with Art. 17, because after erasure it is no longer personal
data.** Recital 26 is explicit that the principles do not apply to information that does not relate
to an identifiable person. The pseudonym was never a function of the identifier and this service
retains no mapping from one to the other, so by the means reasonably likely to be used here the
retained rows no longer relate to an identifiable person — for everyone but the operator, whose
keyed verifier is the bounded exception argued above. Art. 11 describes the resulting position
directly: a controller that can no longer identify the data subject is not obliged to acquire more
information merely to comply — and the data subject may still supply information enabling
identification. That is precisely what the published pseudonym is for.

**The subject keeps the only key.** Handing the pseudonym to the data subject once, in the erasure
response, is deliberate: it makes the retained trail reachable by the one person entitled to reach
it, and by nobody else. A subject who kept it can point at their own retained records in a later
complaint; a subject who did not has placed the trail genuinely beyond reach, which is also a
legitimate outcome of asking to be forgotten. This is the Art. 11(2) shape, not an accident of
implementation.

Two consequences follow from taking that argument seriously, and both are reflected in the decision
above. A record that no consent refers to carries none of this weight, so it is deleted rather than
kept — there is no accountability interest in a row that attests to nothing. And a consent left
`PENDING` or `DRAFT` would be neither evidence nor a closed matter, just an open offer attributable
to someone who asked to be forgotten, so erasure closes those too.

## Consequences

- An erased subject who authenticates again is a new person to this service: the identifier their
  token carries no longer matches any row, so just-in-time provisioning inserts a fresh user. Their
  old consents, if any were retained, stay behind attached to a record nothing can name. This is
  intended, and the integration test asserts it rather than leaving it to be discovered.
- Erasure is irreversible, including by the operator. There is no path back from the pseudonym to
  the identifier, so an erasure issued in error cannot be undone and the subject has to start over.
- The pseudonym is a capability, not an index: this service currently exposes no operation that
  takes one. A subject who keeps theirs can only use it through the operator. If the retained trail
  is meant to be reachable by the data subject in practice rather than in principle, a later ticket
  has to add that read — and it has to do so without ever accepting a pseudonym from a participant,
  which would re-link the record to whoever holds the value.
- The consent trail remains queryable by consent id, by participant and by privacy notice — every
  access path except the one through the person. Reports that count consents per participant are
  unaffected by an erasure; reports that join through `users` lose their subject, as they must.
- Erasure stays within the `ON DELETE RESTRICT` the schema imposes, so no migration and no cascade
  rule changes with it. A later ticket that wants a hard delete of a *retained* trail would have to
  argue with this ADR and with the foreign key at once.
- **Pseudonymisation covers the `users` row only.** It is sound today because nothing else stores
  anything about the person: `consent_events.actor` is a participant or this service, and
  `details` carries no subject data. A later consent-lifecycle ticket that records the subject's
  identifier, email or a participant's local identifier in `actor`, `details` or a consent snapshot
  breaks that silently — the erasure here would leave it behind. Any such column has to be swept by
  `UserService.erase` in the same change that introduces it.
- The verification secret is an operational secret of the same weight as a signing key. It belongs
  outside the database and outside every participant's reach: a participant holding it gains an
  oracle over erased records. Losing it is irreversible in the other direction — every verifier
  written under it becomes dead weight — and that is also the documented way to finish anonymising
  a retained trail.
- The verifier is a yes/no oracle, never an index. There is deliberately no query path to it, and
  a later ticket must not add one — least of all to recognise a re-registering subject, which would
  turn erasure into a rename.
- `users.erasure_verifier` is nullable and stays null for every live record, so it is readable as
  "this row has been erased" by anyone with database access. That is already visible from the
  reserved identifier prefix, so it discloses nothing new.
- Erasure now closes `PENDING` and `DRAFT` consents as well as `GRANTED` ones. A consent-lifecycle
  ticket must not treat `TERMINATED` as a state its own flows can resume from.
