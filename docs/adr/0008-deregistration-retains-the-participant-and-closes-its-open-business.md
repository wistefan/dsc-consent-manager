# ADR 0008 — Deregistration retains the participant and closes its open business

- **Status:** Accepted.
- **Date:** 2026-10-07
- **Ticket:** TICKET-005 (Taiga #69), step 5
- **Relates to:** `DELETE /participants/me` in `api/openapi.yaml`, `ParticipantService.deregister`,
  the `deregistered_at` column added by `V3__participant_deregistration.sql`, and the
  `ON DELETE RESTRICT` on `consents.provider_id`/`consumer_id` and
  `privacy_notices.provider_id`/`consumer_id` in `V1__initial_schema.sql`.

## Context

An organization that is leaving the dataspace asks this service to deregister it. The ticket says
the row is deleted. The schema says it mostly cannot be: both participant foreign keys on
`privacy_notices` and both on `consents` are `ON DELETE RESTRICT`, so a participant that ever
published a notice or appeared in a consent cannot have its row removed while those records stand —
and those records are the service's entire reason to exist.

There is a second question underneath that one, and it is where the participant case parts company
with the user case. ADR 0007 scrubs an erased user's identifier because that identifier is personal
data of a natural person who holds an erasure right, and the balance it strikes — keep the consent,
lose the link to the human — is a balance that right forces. A participant is an organization. It
has no erasure right, it is not a data subject, and the identifier on its row is the public name it
transacts under. Nothing here forces the same trade, so "do what erasure does" is not an argument,
only a resemblance. Review of PR #1 made exactly this point and it is recorded here rather than
re-argued later.

The third question is what to do with the business the departing participant leaves open. A consent
is a permission a *user* granted; a privacy notice is an offer the participant published. Those are
not symmetric, and deregistration cannot treat them as one thing.

## Decision

Deregistration refuses while a granted permission is outstanding, otherwise closes everything still
open, removes every affiliation, archives every live notice, and then either **deletes** the
`participants` row or **retains it intact and marks it deregistered**, depending on whether anything
still refers to it. It is one transaction.

In order:

1. `409 Conflict` if any consent naming the participant as provider or consumer is `GRANTED`.
2. Every `PENDING` and `DRAFT` consent naming it as provider or consumer moves to `TERMINATED`,
   each with a `CONSENT_TERMINATED` event attributed to this service.
3. Every `user_participants` row for the participant is deleted.
4. Every privacy notice naming it as provider or consumer and not already archived is archived.
5. The `participants` row is deleted when no consent and no privacy notice refers to it; otherwise
   `deregistered_at` is set, `email` is cleared and `endpoints` is reset to empty, and every other
   field is left exactly as it was.

### Why a granted consent blocks the exit

A `GRANTED` consent is a permission the *user* gave, and only the user can take it back. If
deregistration revoked it on the participant's behalf, the participant would be ending — by its own
unilateral act — a decision that was never its to make, and the user would find a permission they
granted gone with no record of having withdrawn it. The remedy belongs to the users, so the refusal
names the blocking consents and the participant has to wait for them to be revoked or to expire.

This is the opposite of what ADR 0007 does with a granted consent on erasure, and deliberately so:
there the revoking party *is* the grantor. Here it would be the counterparty.

### Why unanswered consents are terminated instead

A `PENDING` or `DRAFT` consent is not a permission, it is an unanswered offer, and nobody's decision
is overridden by withdrawing one. Leaving it would be the failure ADR 0007 names and closes for
users: a consent in a state some later lifecycle operation can still advance. A `PENDING` consent
naming a departed participant could be moved to `GRANTED` afterwards, handing a live data-sharing
basis to an organization that is gone and that no longer has an endpoint to receive anything at.

Terminating rather than widening the `409` to cover these is also deliberate. A draft the
participant itself opened must not be able to block the participant's own exit; that would let an
organization trap itself behind a record it created and cannot otherwise reach.

### Why consents and their events are retained

For the same reason ADR 0007 retains them. They are the evidence of what was permitted while it was
permitted, and the users on the other side of them are entitled to that record regardless of who has
since left. `consent_events` is append-only and nothing here rewrites it; the status change in step 2
is itself appended as an event rather than applied silently.

### Why the retained row keeps its identity

The retained row is **not** pseudonymised, and this is the substantive difference from ADR 0007.

An organization has no erasure right to satisfy, so there is no interest on the other side of the
scale. The ticket's own stated reason for retaining consents is that the participant reference
remains resolvable — scrubbing the identifier would defeat exactly that and leave every retained
consent naming a counterparty nobody can identify. And there is no `erasure_verifier` counterpart
here: a user's scrubbed identifier can still be checked against a keyed verifier by the operator, so
something survives the scrub. A scrubbed participant would be recoverable by no one at all, which is
a worse outcome than the one the scrub was meant to achieve.

Because `privacy_notices` restricts deletion on both participant keys, a participant that ever
published a notice always lands on the retain branch. The retain branch is therefore the normal
one, not the exception, which is one more reason it has to stay legible.

### Why `email` is cleared and `endpoints` is reset

`email` is the one field on `participants` that can be personal data of a natural person: it is a
contact person's address rather than the organization's identity. Keeping it would retain a
particular human's contact details for an organization that has left, with no basis for doing so.
It is cleared on the retain branch.

`endpoints` is reset for a different reason, which is not privacy but correctness: a departed
participant must not keep advertising a callback. A counterparty reading a retained row should be
able to see who it dealt with, not where to send traffic.

`identifier`, `legalName`, `selfDescriptionUri` and `legalPerson` are kept untouched. Those are the
organization's public identity and they are what makes a retained consent readable.

### Why outright deletion survives at all

A participant that registered and never transacted — no consent, no notice — is referenced by
nothing, and keeping a marked husk for it would accumulate rows no later call can ever remove. The
same argument ADR 0007 makes for deleting a consent-free user applies unchanged, because it is an
argument about unbounded junk rather than about personal data. So the ticket's "the row is deleted"
is honoured literally exactly where the schema permits it, and nowhere else.

### What `deregistered_at` means to a reader

It means the organization has left: the record is excluded from `GET /participants`, and every
participant-scoped write route refuses a token whose row carries it. The routes that act in the
dataspace in the participant's name — the `/participants/me/users` writes — refuse with `403`
through `ParticipantPrincipal.requireActive()`, because the caller is no longer a participant that
may act; `PUT /participants/me` and a repeated `DELETE /participants/me` refuse with `409`, because
there the departed record *is* the resource and the request is a conflict with its state. Reading
stays open throughout: `GET /participants/me` still answers, and the record is still resolvable by
`GET /participants/{identifier}` and by the directory listing's exact-identifier filter, because a
retained consent naming it has to stay readable.

It is **not** a soft delete pretending to be an erasure. Nothing about the row is hidden from a
reader who asks for it by name, and no one should read it as a record awaiting cleanup.

## Consequences

- Re-registering a deregistered identifier **reactivates** the existing row rather than conflicting
  with it: `uq_participants_identifier` makes a second row impossible, and refusing would bar an
  organization from the dataspace permanently on the strength of one `DELETE`. `POST /participants`
  therefore clears `deregistered_at` and overwrites the mutable fields from the body. Only an
  *active* row is a `409`.
- Deregistration is not irreversible the way erasure is, and it is not meant to be. Nothing is
  destroyed except the contact address and the endpoints, and a returning organization resumes under
  the same identifier with the same retained history. A ticket that later wants a true,
  unrecoverable removal of a participant has to argue with this ADR and with four `ON DELETE
  RESTRICT` constraints at once.
- A participant can be blocked from leaving by users who do not act. There is no deadline and no
  operator override in this service; `GRANTED` consents have to reach `REVOKED` or `EXPIRED` on
  their own terms first. If a dataspace needs a forced exit, that is a new decision and a new ADR,
  not a loosening of this one.
- The terminated consents are a one-way move. A consent-lifecycle ticket must not treat
  `TERMINATED` as a state its own flows can resume from — the same constraint ADR 0007 records for
  users, now reachable from the participant side too.
- **The sweep covers `participants`, `user_participants`, `privacy_notices` and `consents` only.**
  It is sound today because nothing else stores anything about the participant. A later ticket that
  records a participant identifier, endpoint or contact in `consent_events.details`, in a consent
  snapshot, or in a new table has to sweep it in `ParticipantService.deregister` in the same change,
  or the cascade will silently leave it behind.
- Archiving a notice on the way out can collide with nothing: `uq_privacy_notices_active_contract`
  is a partial index over non-archived rows, so archiving only ever frees the slot.
