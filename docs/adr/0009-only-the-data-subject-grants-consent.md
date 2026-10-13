# ADR 0009 — Only the data subject grants consent, and a request confers nothing

- **Status:** Accepted.
- **Date:** 2026-10-13
- **Ticket:** TICKET-006 (Taiga #71), step 6
- **Relates to:** `POST /consents` (`grantConsent`) and `POST /consents/requests`
  (`requestConsent`) in `api/openapi.yaml`; `ConsentService`, `ConsentWriter`,
  `ConsentStateMachine` and `ConsentFactory` in `…consentmanager.service`;
  `ConsentEventTrigger` in `…consentmanager.domain`; and the `consents` / `consent_events` tables
  and the partial unique index `uq_consents_granted_per_user_notice` in
  `V1__initial_schema.sql`.

## Context

Two operations arrive together and they look symmetric: a participant asks a user for consent, and
a user grants or refuses it. They are not symmetric, and the whole design of this flow follows from
saying so once, in the right place.

The service this one replaces let a participant present an `x-user-key` header naming the user it
was acting for and write a consent in that user's name. That is not a consent. A permission whose
only evidence is that the party who benefits from it said it was given cannot be shown afterwards
to have been given at all, and the record it leaves is indistinguishable from the record a
fabrication would leave. The header is gone and nothing replaces it.

The second question is what a participant's *request* is, given that it is not a grant. A request
is reachable, it writes a row, that row names both parties and a privacy notice — so there is a
standing temptation to treat it as a consent in a weaker state, and to let a counterparty rely on
it. The temptation is worth refusing explicitly, because the refusal is what the `PENDING` status
means.

The third question is where the guard lives. `Consent` already exposed `withStatus(...)` and
`updateStatus(...)`, and erasure (`UserService`) and deregistration (`ParticipantService`) already
moved consents between states by calling them. A transition table that only the new endpoints
consult would be a convention, and conventions decay: the next flow that needs to move a consent
calls the setter directly, and nothing notices.

## Decision

**Only the data subject decides.** `POST /consents` is a pure `USER` route: the subject is read
from the token's identifier claim and from nowhere else, there is no parameter naming a user, and
`UserPrincipal` is the only principal type the operation admits. A participant cannot reach the
route at all.

**A participant may ask, and never answer.** `POST /consents/requests` opens a `PENDING` consent
naming the asking participant — taken from the token, so no caller can put the question in
another's name — and the user it names. The request body elects no scope: a scope proposed by the
party that wants the data would read back afterwards as something the subject had agreed to. A
`PENDING` row confers nothing. It is not a permission in a weaker form, it is a question on the
record, and the only thing it entitles the asker to is an answer.

**Every status change goes through `ConsentFactory`**, which is the only caller of
`Consent.withStatus`/`updateStatus`, and which consults `ConsentStateMachine` for every move.
`ConsentStatusInvariantTest` scans `src/main/java` and fails the build on any other caller, so "no
transition bypasses the guard" is enforced rather than documented. Erasure and deregistration were
rewritten onto the factory for that reason, and their behaviour is unchanged by it.

**Lineage is a single upward link.** A new consent carries `parentConsentId`, and no row carries a
child column. A chain is walked upward from its newest end; nothing has to be written twice, and a
superseded consent is never mutated to point at its successor.

### What a decision does to an open request

A user's decision **adopts** an open `PENDING` request for the same (user, notice) pair rather than
creating a second row beside it. The adopted row's status moves to `GRANTED` or `REFUSED` in place.

This is the alternative to the obvious implementation, which would insert a fresh row and leave the
request standing. A stranded `PENDING` would be a question the subject has visibly answered and the
record says is still open, and — because the service refuses a second request while one is open —
it would `409` every later request for that pair forever. Adoption is what makes a request always
answered rather than stranded.

The read that finds the row to adopt takes a row lock — `findByUserIdAndPrivacyNoticeIdForUpdate`,
deliberately unfiltered by status — which is what keeps two concurrent decisions from adopting the
same request twice. `ConsentGrantAtomicityIT` holds that line: two concurrent first grants leave one
consent and one conflict, and two decisions against a single open request leave nothing unanswered
and nothing answered twice.

Adoption is why **`POST /consents` answers `200` as well as `201`**. A decision that opens a new
record is a creation; a decision that writes onto a request already on the record is not, and
saying so in the status code is the only way a client can tell which happened. The adopted row
keeps its `id` and its own `parentConsentId`.

A decision that supersedes a standing `GRANTED` does both things at once: the open grant moves to
`REVOKED` as an `EXPLICIT` act of the subject, and the new or adopted row points at it.

Two consequences are worth stating because neither is visible from the endpoint:

- **The decision closes every *other* open request for the pair** as `TERMINATED`, attributed to
  this service rather than to the subject. The locking read cannot block a concurrent insert and no
  index covers `PENDING`, so a pair really can come to hold two open requests; answering only one
  of them would strand the other and reinstate exactly the permanent `409` adoption exists to
  prevent.
- **An adopted row's `createdAt` dates the offer, not the answer.** When the subject decided is the
  timestamp of the `CONSENT_GIVEN` / `CONSENT_REFUSED` event. TICKET-010's lineage walk and
  TICKET-011's receipt must read the moment of decision from the event, never from the row.

### What counts as a decision

A decision is `GRANTED` or `REFUSED` and nothing else. `ConsentStateMachine.requireDecision`
refuses every other status in the service layer, above the transition table.

The table is deliberately more permissive than the endpoint, and the two must not be confused.
`CREATABLE` admits `PENDING`, because that is the status a participant's request opens. Without the
narrower service-layer check, a subject could post a decision of `PENDING` — requesting consent
from themselves — or of `TERMINATED`, closing an open request as a deliberate act of their own.
Neither is a thing this flow means to offer. `ConsentServiceIT` proves the refusal lands in the
service and not only in the specification's `decision` enum, so a future client of the service that
bypasses the endpoint inherits it.

The states that count as *still in force* are named once, as `ConsentStateMachine.OPEN_STATUSES`
(`GRANTED`, `PENDING`): one set, read by the adoption rule, by the conflict that refuses a second
request, and by nothing that redefines it locally.

### The states the table does and does not admit

- **`DRAFT` is not creatable here**, although the ticket's own state table lists `- -> DRAFT`
  ("consent prepared, awaiting user action") beside `GRANTED`, `REFUSED` and `PENDING`. Nothing in
  this ticket prepares a draft, so admitting the creation would ship a state that no endpoint can
  produce and no endpoint can answer. `DRAFT` keeps its *outgoing* edges, and TICKET-009 adds it to
  `CREATABLE` and to the factory's event table together — the two halves are one change, because
  `DRAFT` has no entry in the factory's status-to-event mapping and creating one today would raise
  an `IllegalStateException` rather than a refusal. The acceptance criterion "the state machine
  rejects every invalid transition" is therefore read here as covering transitions, not as covering
  that deferred creation.
- **`PENDING -> TERMINATED` and `DRAFT -> TERMINATED` are edges in the table**, because erasure and
  deregistration already close the consents nobody answered, and the adoption rule above closes
  them too. The ticket's table is silent on the move rather than forbidding it, and a table that
  declared invalid what the service performs would be worse than no table at all.

### The rules a decision is checked against

- **`parentConsentId` is the most recent consent for the pair, whatever its status.** A grant after
  a refusal, an expiry or a termination extends the chain rather than starting a new one; revoking
  an open `GRANTED` and granting again is one case of that rule, not a special one.
- **A privacy notice naming no consumer is refused with `400`.** `consents.consumer_id` is `NOT
  NULL` and stays that way: no notice this service generates lacks a consumer, so the alternative
  would be relaxing a column to admit a document the service cannot produce.
- **An explicitly empty elected `purposes` or `data` array is a `400`.** This diverges from the
  ticket's literal "verify each is a subset" — the empty list *is* a subset — because electing
  nothing is not a narrowing of the notice, it is a consent to nothing wearing the shape of a
  consent. For the same reason an election against a notice that declares no purposes or no
  categories is a `400`. An *absent* election against such a notice is accepted and freezes an
  absent scope, which is not the same record as an empty one.
- **`contractUri` is frozen once**, in the `consents.contract_uri` column, and the snapshot does not
  duplicate it. One value, one place, no way for the two to disagree later.
- **Every consent event carries an `actor`**, so a decision is exactly as attributable as the
  request that prompted it.
- **An unknown user and an unaffiliated one are refused alike**, with `404` and an identical
  message, so `POST /consents/requests` cannot be used to enumerate the dataspace's users. `403` on
  that route means something else entirely and only that: the caller is named on neither side of
  the notice, or has left the dataspace.

### Deliberate versus systemic events

`ConsentEventTrigger` is the single decider of `consent_events.event_type`: `EXPLICIT` writes
`"explicit"`, `SYSTEM` writes `"system"`. An expiry job's move, or a consent closed because a
counterparty left the dataspace, is therefore never filed as a deliberate act by the subject, and
the two strings exist in one place rather than at every call site that writes an event.

## Consequences

- **A participant cannot grant, repair or withdraw a consent on a user's behalf — ever.** Any later
  ticket that wants a participant-side write to a consent has to argue with this ADR first. An
  operator import, a migration or a "sign on behalf of" convenience is the same decision wearing a
  different name.
- **An unanswered request is not a failure state.** Nothing in this service chases it, expires it or
  notifies anyone about it; TICKET-013 / TICKET-020 own notification and attach at the points where
  a request is opened and a decision is recorded.
- **`PENDING` must stay unreliable on purpose.** A reader that treats a pending consent as a weak
  permission has misread it, and a later feature that lets a counterparty act on one would undo the
  only thing the status says.
- **The transition table is the single source of truth about consent states**, and it is total over
  the flows in the service. A new flow that moves a consent adds its edge to the table or fails the
  build on `ConsentStatusInvariantTest` — including the flows this ticket defers.
- **Seams that are deliberately absent.** No placeholder code exists for any of them:
  - TICKET-009 — `POST /consents/{id}/resume` and refusing an open `PENDING` directly. It adds
    `DRAFT` to `CREATABLE` and to the factory's event table in one change.
  - TICKET-010 — the lineage endpoint. It walks `parentConsentId` upward and must read each
    decision's moment from the `CONSENT_GIVEN` / `CONSENT_REFUSED` event.
  - TICKET-011 — receipt signing and `GET /.well-known/jwks.json`. It reads the frozen snapshot and
    the decision event; this service holds no signing key today (see `docs/security.md`).
  - TICKET-013 / TICKET-020 — user and state-change notifications.
  - The TTL expiry job, which will move `GRANTED` to `EXPIRED` through `ConsentFactory` with a
    `SYSTEM` trigger, like every other non-deliberate move.
- **An adopted request means a consent's own row cannot date the decision.** Any consumer of
  `consents.created_at` that means "when the user decided" is wrong for exactly the rows a
  participant asked for, which is the majority of them in a dataspace that uses requests at all.
