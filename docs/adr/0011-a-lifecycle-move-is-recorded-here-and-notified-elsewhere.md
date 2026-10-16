# ADR 0011 — A lifecycle move is recorded here and notified elsewhere

- **Status:** Accepted.
- **Date:** 2026-10-14
- **Ticket:** TICKET-009 (Taiga #73), step 6
- **Relates to:** the five `POST /consents/{id}/<verb>` operations in `api/openapi.yaml`;
  `ConsentLifecycleService`, `ConsentWriter`, `ConsentFactory` and `ConsentStateChangeLog` in
  `…consentmanager.service`; TICKET-013 (user notification) and TICKET-020 (state-change
  notification), which own delivery; and [ADR-0007](0007-erasure-pseudonymises-the-user-and-retains-the-audit-trail.md),
  whose boundary the log line respects.

## Context

This ticket added the five moves a consent makes after it exists — revoke, refuse, terminate,
re-confirm and resume. Two of its acceptance criteria say more than "the move is written": a
termination is to notify the data subject, and a revocation is to emit a participant notification.

Nothing on this branch delivers anything to anyone. There is no queue, no outbox, no webhook
registry and no receipt signer; TICKET-013 and TICKET-020 own that subsystem and ticket #71 shipped
the grant flow on the same footing. The choice is therefore not *whether* to build it here — it is
whether the gap is left silent, papered over with a sender that logs and claims to have sent, or
named.

Three smaller questions arrive with it, and they are decided together because they are one
question seen from three sides: what does it mean for this service to have recorded a move?

## Decision

**A move is effective in this service's records the moment its transaction commits, and effective
in practice only once the parties have been told.** The two are different facts and the service
asserts only the first. `consents.status` and the `consent_events` trail are the authority on what
was authorised and when; they are not, and are not claimed to be, the mechanism by which a
processor stops processing. A subject who revokes gets `200` because the revocation is recorded,
not because anyone has yet acted on it.

**Delivery is deferred to TICKET-013 and TICKET-020, and the seam they attach to is named.**
`ConsentLifecycleService.report` is the one path every lifecycle move passes through: it takes the
committed write together with the status it moved from, renders the result and calls
`ConsentStateChangeLog.recorded`. Every move reaches it — not only the one a caller addressed, but
the revocation and the terminations a `resume` performs in the same transaction, which the writer
returns as collateral moves and `report` is handed one at a time — so a superseded grant's closing
is reported through the seam rather than lost. A route or a collateral write added later cannot miss
the seam without going around that method. `ConsentStateChangeLog`
emits one structured line per move through the Logback JSON encoder and does nothing else — it is
not a queue, buffers nothing, retries nothing, and is deliberately shaped so that a reader cannot
mistake it for a delivery guarantee.

**The verb is `POST /consents/{id}/revoke`, not `DELETE /consents/{id}`.** A revocation *adds* to
the record: the consent survives with a new status and a new trail entry, because the evidence of
what was authorised while it was authorised is the point of keeping it (ADR-0007 retains the same
trail through an erasure for the same reason). `DELETE` names the opposite act, carries no actor or
trigger, and would leave a client that retries unable to distinguish "revoked" from "never
existed". A named verb also lets the five moves share one shape, one authorisation rule and one
guard through `ConsentStateMachine`, rather than splitting into a status `PATCH` whose body would
have to be validated back into the same transition table.

**Re-confirmation is an event, not a transition.** `GRANTED → GRANTED` is absent from
`ConsentStateMachine.TRANSITIONS` and stays absent; `ConsentFactory.affirm` writes a
`CONSENT_RE_CONFIRMED` event and returns the consent untouched. `consents.updated_at` dates state
changes, and a re-confirmation changes no state — it is dated by its own event. Admitting the
self-edge instead would make every guard that asks "may this move?" answer yes for a move that
moves nothing, and would silently re-date a record whose authorisation is unchanged.

## Consequences

- **AC 13 and AC 14 are not met, and a log line is not a notification.** Nothing is delivered. The
  criteria belong to TICKET-013 and TICKET-020 and should be moved there rather than ticked here;
  the deviation is recorded in `IMPLEMENTATION_PLAN.md` under "Criteria deliberately not met as
  written" as well as here.
- **Note (2026-10-16):** TICKET-013's subject notification is now delivered by
  [ADR-0015](0015-subject-notifications-are-best-effort-and-informational.md). It is best-effort,
  called from `request`/`terminate` directly and not from this seam. TICKET-020 still owns
  guaranteed participant notification and the outbox described below.
- **Whoever implements delivery must not simply hang a sender off this call.** `recorded` runs
  *after* the write has committed and outside its transaction, so a crash between the commit and
  the call loses the line. That is acceptable for a log and not acceptable for a notification: a
  transactional handoff means an outbox row written inside `ConsentWriter`'s unit of work, with
  `ConsentStateChangeLog` keeping the shape of the event the sweep then drains. The seam marks the
  place; it does not pre-decide the mechanism.
- **The log line names the subject by `user_id` and never by identifier.** An erasure rewrites the
  database and cannot reach a line already shipped to a collector, so ADR-0007's boundary holds
  only if the identifiers stay out of the stream. The actor is likewise left to
  `consent_events.actor`, where an erasure can still reach it.
- **A re-confirmation is invisible to a reader of the `consents` table alone.** Neither the status
  nor `updated_at` changes. Anything that needs to show when a subject last affirmed a consent in
  force must read the trail — which is the same place the rest of the lifecycle lives, so no reader
  gains a second source.
- **The held status in the line is the one the write locked, not an earlier unlocked read.**
  `ConsentWriter` returns the status it read under the row lock alongside the move, and `report`
  logs *that*, so a concurrent move of the same record cannot leave the line naming a status the
  write did not move from. The trail remains the authority a consumer reconstructing the lifecycle
  should read from, but the line no longer risks disagreeing with it.
