# ADR 0010 — A catalog may relay a resume, and the trail names it as the relay

[Documentation](../README.md) › [ADR index](README.md) › ADR 0010

- **Status:** Accepted.
- **Date:** 2026-10-13
- **Ticket:** TICKET-009 (Taiga #73), step 4
- **Relates to:** `POST /consents/{id}/resume` (`resumeConsent`) in `api/openapi.yaml`;
  `ConsentLifecycleService`, `ConsentWriter` and `ConsentNoticeGuard` in
  `…consentmanager.service`; `CatalogPrincipal` in `…consentmanager.security`; and
  [ADR-0009](0009-only-the-data-subject-grants-consent.md), whose rule this one bounds.

## Context

ADR-0009 settled who grants consent in this service, and it settled it absolutely: the subject is
read from the token's identifier claim and from nowhere else, "there is no parameter naming a
user", and a participant's request "confers nothing". The service it replaced let a party name the
user it was acting for and write a consent in that user's name, and the reason that is not a
consent is that the record it leaves is indistinguishable from the record a fabrication would
leave.

`POST /consents/{id}/resume` admits a `CATALOG` caller, which names a user and moves a consent to
`GRANTED`. That is, on its face, the shape ADR-0009 abolished, and it cannot be allowed to land as
an unremarked exception discovered later by whoever reads the route and the ADR together.

The flow it serves is real. A consent request is opened here, the subject is sent to the dataspace
catalog to read the notice and decide, and the decision is taken there, in the catalog's own flow —
the flow this operation *resumes*. The subject never returns to this service with a token of their
own, so without the route the request they answered stays `PENDING` forever, and the only route
that could adopt it, `POST /consents`, is `USER`-only and therefore unreachable to the relay.

What this service can verify is bounded, and the bound is worth stating rather than implying. It
verifies the catalog's token against the trust list. It does not and cannot verify that the subject
decided, or what they were shown when they did. The evidence for a relayed grant is the catalog's
word, and nothing stronger.

## Decision

**A `CATALOG` caller may relay a decision the subject took elsewhere, and may do nothing else.**
The exception to ADR-0009 is exactly one operation wide and is fenced by four conditions, each of
which is enforced in code rather than documented as an expectation:

- **It can only advance a record that already exists.** The consent is addressed by its own
  identifier in the path. A catalog cannot create one, so every relayed grant answers a request a
  party to the notice opened here, against a notice this service generated.
- **It cannot choose the subject.** `userIdentifier` is checked *against* the addressed consent,
  never used to select one. An identifier naming anybody but that consent's subject is `404`, the
  same answer an unknown consent gets, so a catalog can neither grant for someone else nor learn
  whose consent an identifier addresses. Omitting it altogether is `400`: a relay that does not say
  whose decision it relays is not a relay.
- **It cannot choose the notice or the terms.** The notice is the one the record was opened
  against, and the stored snapshot is kept exactly as it stands. A resume records a decision taken
  elsewhere; it does not elect scope here. Where the notice has since been regenerated and
  archived, `ConsentNoticeGuard` refuses the resume with `400` — the same refusal
  `POST /consents` gives — so a relayed grant can never carry terms no longer in force, and the
  subject answers the current notice through the route that does elect scope.
- **It cannot act on a record already decided.** `ConsentStateMachine` permits `PENDING` and
  `DRAFT` to `GRANTED` and nothing else, so a revoked, refused or terminated record is `400` naming
  the status it holds.

**The trail says it was a relay.** The `CONSENT_RESUMED` event records the caller, and a catalog
caller is not the subject, so it is recorded as `catalog:<issuer>:<sub>` — both halves, because
`ConsentManagerPrincipal.subject()` is unique per issuer only and the trust list admits more than
one catalog, and prefixed, so no identifier scheme in the dataspace collides with it. A subject
resuming their own consent records their own identifier, as every other lifecycle move does. A
relayed grant indistinguishable from one the subject made here directly is the part that would be
indefensible; a relayed grant that announces itself is a record whose provenance a reader can
weigh.

**A relayed grant supersedes what it replaces.** Recording it revokes whatever consent is in force
for the same subject and notice and re-points the resumed record's `parentConsentId` at it, in one
transaction — the same act `ConsentService.decide` performs when the subject calls here directly,
and the revocation carries the `catalog:` actor, so the trail shows who closed it. This stays
inside the fence: the catalog chooses neither the subject nor the notice, so the only consent it
can close is the one its own relayed decision replaces.

**The subject's own half of the route is unchanged by all of this.** A `USER` caller resumes their
own consent, states nothing — the body may be omitted entirely — and a `userIdentifier` sent anyway
is ignored, exactly as `POST /consents` ignores a user named in the body. For the subject's own
call the token remains the only statement of who decided.

## Consequences

- **ADR-0009 is bounded, not weakened.** Its rule now reads: only the data subject decides, and the
  only record of a decision this service accepts from anyone else is a relay of a decision already
  taken, over a record the relay did not create, for a subject it did not choose, marked in the
  trail as a relay. A reader checking whether this service ever wrote a consent on a party's bare
  say-so still finds no such route.
- **The relay is trusted exactly as far as the catalog is.** A compromised or dishonest catalog can
  grant the consents it was asked to relay and no others. That is the residual risk, it is the risk
  of admitting the role at all, and it is why the role is admitted on this one operation rather
  than on the grant route.
- **The remedy is a one-line spec change.** Dropping `CATALOG` from the operation's `security` and
  `x-roles` removes the exception without touching the service: the sealed switch in
  `ConsentLifecycleService.resume` keeps its `CatalogPrincipal` branch, which simply becomes
  unreachable, as the `ParticipantPrincipal` branch already is. A dataspace that will not accept
  the relay can therefore refuse it without a fork.
- **An audit reading `consent_events.actor` must expect two shapes.** A dataspace identifier, or a
  `catalog:` string that is not one. A consumer matching actors against the participant and user
  directories needs to treat the prefix as a third case rather than as an unresolvable identifier.
- **Nothing here applies to the other lifecycle moves.** Revoke, refuse and re-confirm remain
  `USER`-only and terminate remains `PARTICIPANT`-only; no relay reaches them, because none of them
  needs one — the flows that would ask for it end a consent rather than record one.
