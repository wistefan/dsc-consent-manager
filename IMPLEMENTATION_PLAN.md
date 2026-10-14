# Implementation Plan: Consent Manager - Reimplementation - Consent Lifecycle Operations

## Overview
Add the five operations that move a consent after it exists — `revoke`, `refuse`, `terminate`,
`re-confirm` and `resume` — as `POST /consents/{id}/<verb>` routes on top of the grant flow merged
for ticket #71. Every one of them authorises the caller against the consent itself (never on role
alone), guards its move through the existing `ConsentStateMachine`, and writes the status and its
audit event in one transaction — `resume` closing the grant it supersedes in that same unit of work. No database migration and no new state-table edge are needed: the
transitions, the `ConsentEventState` values and `consent_events.actor` are all already in place.

## Context the steps build on

Verified against the work branch, so no step below re-establishes it:

- `service/ConsentStateMachine` already permits `PENDING|DRAFT → GRANTED|REFUSED|TERMINATED` and
  `GRANTED → REVOKED|TERMINATED|EXPIRED`, and `requireTransition` already raises
  `BadRequestException` naming both the held and the attempted status — **AC 7 is satisfied by the
  existing message format**, and no step adds an edge to `TRANSITIONS`.
- `domain/ConsentEventState` already declares `CONSENT_REVOKED`, `CONSENT_REFUSED`,
  `CONSENT_TERMINATED`, `CONSENT_RE_CONFIRMED` and `CONSENT_RESUMED`; `consent_events.actor` exists
  in `V1__initial_schema.sql` and on the `ConsentEvent` entity. **No Flyway migration.**
- `service/ConsentWriter` is the transactional half of every consent write and already holds the
  `(user, notice)` row lock (`findByUserIdAndPrivacyNoticeIdForUpdate`) that step 4 takes. Its
  private `revokeOpenGrant` + `parentConsentId` supersede is what `decide` already does to record a
  decision over a grant in force, and step 4 reuses it rather than inventing a second shape.
- `service/ConsentFactory` is the only source allowed to call `withStatus` / `updateStatus` —
  `ConsentStatusInvariantTest` fails the build otherwise, so every new transition in this ticket
  goes through the factory.
- `security/PrincipalArgumentBinders` already registers a binder for the sealed
  `ConsentManagerPrincipal` itself, which is what makes step 4's two-role route bindable.
- The package is `com.seamware.consentmanager`. The `eu.prometheusx.…` paths in the ticket's "Files
  to Create" section are stale from the pre-revision ticket; the repository wins.

## Conventions every step follows

- **Spec first.** Each operation is written inline in `api/openapi.yaml` (never `api/paths/*.yaml`,
  which `SpecReferenceResolutionTest` fails the build over) with `security`, `x-roles`,
  `x-principal`, the shared `401`/`403` response refs, and the `{id}` path parameter declared
  `type: string, format: uuid` as `GET /privacy-notices/{id}` declares it. A new schema file is
  registered under `components.schemas` in `openapi.yaml` by `$ref` to
  `api/components/schemas/<Name>.yaml`.
- **Tag `Consent Lifecycle`.** All five operations carry it, so the generator emits one
  `AbstractConsentLifecycleController` and the ticket's `ConsentLifecycleController` is its single
  concrete subclass. Each step adds its own handler to that subclass;
  `SpecSecurityConsistencyTest.concreteControllerDeclaresTheHandler` requires the method be
  *declared* there, not inherited.
- **Raise `SPECIFIED_OPERATION_COUNT`** in `SpecSecurityConsistencyTest` (line 87) in the same
  commit that adds an operation: 21 → 23 → 24 → 25 → 26 across steps 1–4, step 1 moving it by two
  because it delivers both `revoke` and `refuse`.
- **Add a row per operation to `EndpointRoleMatrixIT.Endpoint`** in the same commit —
  `everySpecifiedOperationIsCovered` is total over the parsed operations and matches
  `"<method> <path>"` with the template intact (`"post /consents/{id}/revoke"`) — plus its
  `roleMatrix()` row giving the expected status for USER, PARTICIPANT, CATALOG, OUTSIDER and
  ANONYMOUS. A PARTICIPANT-only route is additionally swept by `participantScopedEndpoints`, which
  covers the *unregistered* caller only — a deregistered one needs its own case (see step 2).
- **Who the event names.** Every one of the five writes is a deliberate act of an identified
  caller, so all of them record `ConsentEventTrigger.EXPLICIT` — `ConsentWriter`'s `SUBJECT_TRIGGER`
  — never the `SYSTEM` trigger reserved for the cleanup sweeps. The actor string is the caller's own
  identifier: the subject's `identifier` for `revoke`, `refuse`, `re-confirm` and a subject's
  `resume`; the participant's `identifier` for `terminate`. A `CATALOG`-relayed `resume` has no
  dataspace identifier to use, so it records the token's verified `iss` **and** `sub` through a named
  format constant on `ConsentLifecycleService` — `CATALOG_ACTOR = "catalog:%s:%s"`. Not `sub` alone:
  `ConsentManagerPrincipal.subject()` documents it as unique per issuer only, and the trust list
  admits more than one issuer, so a `sub`-only actor would let two catalogs write the same string
  into the single column AC 11 relies on. The `catalog:` prefix is also what keeps a relayed grant
  from reading in the trail as one the subject made here directly (see step 4).
- **Build green at every step:** `./mvnw spotless:apply && ./mvnw verify`.

---

## Steps

### Step 1: Lifecycle foundation, and the two status moves a subject makes — revoke and refuse

Establishes everything the later steps reuse, and delivers `POST /consents/{id}/revoke`
(US-CM-006) and `POST /consents/{id}/refuse` (US-CM-007). They differ only in the status they move
to, so they are one service method parameterised by the target status rather than two.

**Spec — `api/openapi.yaml`:**
- `/consents/{id}/revoke` → `post`, `operationId: revokeConsent`, tag `Consent Lifecycle`,
  `security: [{bearerAuth: [USER]}]`, `x-roles: [USER]`, `x-principal: UserPrincipal`, no request
  body, `200` → `#/components/schemas/Consent`, `400`/`401`/`403`/`404` shared refs, `default` →
  `ProblemDetail`. The description states that revocation is recorded immediately here and that
  `DELETE` is deliberately not the verb — nothing is deleted and the record stays readable.
- `/consents/{id}/refuse` → `post`, `operationId: refuseConsent`, same auth and shape; description
  states it is how a subject declines a participant's unanswered request, so it is permitted from
  `PENDING` and `DRAFT` and from nothing else — a consent in any other status, `GRANTED` included,
  is `400` naming both states. `revoke` is the mirror image: permitted from `GRANTED` only.
- `SPECIFIED_OPERATION_COUNT` 21 → 23.

**Repository — `repository/ConsentRepository`:**
- `findByIdForUpdate(UUID id)` — a `@Query` `SELECT … FOR UPDATE`, modelled on the existing
  `findByUserIdAndPrivacyNoticeIdForUpdate`. Every lifecycle write re-reads the row under this lock
  inside its transaction and guards the transition on *that* read, so two concurrent revocations
  cannot both append an event: the loser sees `REVOKED` and is answered `400`.

**Service:**
- `service/ConsentParties` (new `@Singleton`) — resolves a stored consent's `providerId` /
  `consumerId` to the identifiers the API renders and builds `ConsentDetails`. Extracted from
  `ConsentService.details`/`party`, which now delegate to it, so the read projection is built in one
  place for both the grant flow and the lifecycle flow. A missing participant stays an
  `IllegalStateException` — the foreign keys forbid it, so it is corruption, not a `404`.
- `service/ConsentWriter.move(UUID consentId, ConsentStatus target, String actor,
  ConsentEventTrigger trigger)` — `@Transactional`, called with the subject's identifier and
  `EXPLICIT` per the actor rule above: re-read under `findByIdForUpdate`,
  `factory.transition(...)`, `consents.update` + `events.save`. One unit of work, so the status and
  its event cannot diverge (AC 12). A refused transition throws before anything is written.
- `service/ConsentLifecycleService` (new `@Singleton`) — `revoke(User subject, UUID consentId)` and
  `refuse(User subject, UUID consentId)`, both over a private `move(subject, consentId, target)`
  that: loads the consent (`404` when unknown), requires `consent.getUserId()` equal the subject's
  id and answers the same `404` otherwise so the route cannot be used to probe which consents exist
  (AC 8), then calls the writer and renders through `ConsentParties`.

**API — `api/ConsentLifecycleController`** (new, `@Controller`, extends the generated
`AbstractConsentLifecycleController`): `revokeConsent` and `refuseConsent` take `UserPrincipal` as
the first parameter and pass `principal.user()`; both answer `200` with `ConsentMapper`'s
representation.

**Tests:**
- `service/ConsentLifecycleIT` (Testcontainers Postgres, modelled on `ConsentServiceIT`):
  parameterized over the starting states for each of the two operations — the permitted move writes
  the status, flips `consented` and appends exactly one event carrying the actor (AC 1, 2, 11); a
  refused move writes nothing and the message names both states (AC 7); a consent owned by another
  user is `404` (AC 8).
- `api/ConsentLifecycleSurfaceIT` (Keycloak + Postgres, modelled on `ConsentGrantIT`): the two
  routes end to end, the subject taken from the token, every non-`USER` role refused.
- `EndpointRoleMatrixIT`: `REVOKE_CONSENT` / `REFUSE_CONSENT` rows against an absent consent id —
  USER `404`, PARTICIPANT/CATALOG/OUTSIDER denied, ANONYMOUS challenged.

**Acceptance:** AC 1, 2, 7, 8, 11, 12 for these two operations; `./mvnw verify` green.

### Step 2: Terminate — the participant's counterpart, and the 403 a non-party gets

Delivers `POST /consents/{id}/terminate` (US-CM-008): the organisational end of an arrangement.
The move it exists *for* is `GRANTED → TERMINATED`, but it is not the only one the table permits,
and this plan does not change the table: `UNANSWERED_MOVES` is the entry for both `PENDING` and
`DRAFT` and contains `TERMINATED`, because erasure and deregistration already close unanswered
offers through that edge. So a participant may equally terminate a request that is still open, and
the statuses the operation refuses are exactly the terminal ones — `REVOKED`, `REFUSED`,
`TERMINATED` and `EXPIRED`.

**Spec:** `/consents/{id}/terminate` → `post`, `operationId: terminateConsent`, tag
`Consent Lifecycle`, `security: [{bearerAuth: [PARTICIPANT]}]`, `x-roles: [PARTICIPANT]`,
`x-principal: ParticipantPrincipal`, no body, `200` → `Consent`, shared `400`/`401`/`403`/`404`.
The description says termination is not the subject withdrawing; that a `PENDING` or `DRAFT`
consent is terminated too rather than refused, so a client must not expect a `400` there, and only a
consent already in a terminal status is `400` naming both states; and that a participant the
consent names on neither side is `403` — distinct from the `404` a foreign *user* gets, because a
participant that is party to nothing still legitimately knows the consent exists through its own
records. `SPECIFIED_OPERATION_COUNT` 23 → 24.

**Service:** `ConsentLifecycleService.terminate(Participant caller, UUID consentId)` — load
(`404`), then require `caller.getId()` equal the consent's `providerId` or `consumerId`, throwing
`ForbiddenException` otherwise (AC 9), reusing the phrasing of `ConsentService.requireParty`; then
the same `ConsentWriter.move` to `TERMINATED`, actor the participant's identifier, trigger
`EXPLICIT`.

**API:** `ConsentLifecycleController.terminateConsent(ParticipantPrincipal principal, UUID id)`
calls `principal.requireActive()` **before** loading the consent, because terminating acts in the
dataspace in the participant's name and that is the rule AGENTS.md states for such a route. Note
what the stricter check does and does not buy: `EndpointRoleMatrixIT.deregisterTheCallerParticipant`
*deletes* the `participants` row, so `participantScopedEndpoints` only sweeps the **unregistered**
case, which `requireRegistered()` would already satisfy; nothing in that sweep exercises a row
carrying `deregistered_at`. And ADR-0008 already terminated every `PENDING`/`DRAFT` consent naming
the departing participant and refuses the exit outright while a `GRANTED` one stands. What
`requireActive()` buys is therefore the window *after* deregistration: a retained participant's
notices stay readable, a subject may still grant against one, and a departed participant must not
then reach into the dataspace to end it. Because that case is not swept, step 2 asserts it with an
explicit IT.

That choice has a consequence this plan states rather than leaves to be discovered: a `GRANTED`
consent arising in that window can then be ended only by the subject revoking it. It is never
stuck — the party whose data it concerns always holds the exit — but the departed participant
cannot close an arrangement it is named in, and the IT above makes that the documented contract.
It is the right trade for these five operations: deregistration means no further acts in the
dataspace in that participant's name, and `terminate` is exactly such an act. The window itself is
the real defect, and it belongs to the grant flow rather than here — see "Out of scope".

**Tests:** extend `ConsentLifecycleIT` (a party terminates from `GRANTED`, and equally from
`PENDING` and `DRAFT` — the permitted edges, asserted rather than assumed; a non-party `403`; a
consent in each terminal status `400` naming both states) and `ConsentLifecycleSurfaceIT` (provider
and consumer tokens both admitted, a third participant refused, `USER`/`CATALOG` refused, and the
case the sweep misses: a participant whose row carries `deregistered_at` is `403` while the same
token still reads `GET /participants/me`); `EndpointRoleMatrixIT` row `TERMINATE_CONSENT` —
PARTICIPANT `404` on an absent id, all others denied — and confirm the route is picked up by
`participantScopedEndpoints`. Confirm `noWriteRouteLetsTheCallerNameTheRecord` still passes: `{id}`
addresses a consent, not a participant.

**Acceptance:** AC 9, and AC 3 in a wider form than it is written — see "Criteria deliberately not
met as written".

### Step 3: Re-confirm — an affirmation, not a transition

Delivers `POST /consents/{id}/re-confirm` (US-CM-009): the status does not change, only an event is
appended, so it cannot go through `requireTransition` at all. The pre-revision `triggerDataExchange`
query parameter is not carried over.

**State machine — `service/ConsentStateMachine`:** add `AFFIRMABLE = EnumSet.of(GRANTED)` and
`requireAffirmable(ConsentStatus held)`, raising `BadRequestException` naming the held status and
the one re-confirmation requires. It belongs in the one table rather than as an `if` in the service,
for the same reason every other rule does.

**Factory — `service/ConsentFactory`:** two additions, and they are deliberately one mechanism
rather than a mechanism and an exception.

- `ADMISSIBLE_EVENTS`, a table of every event state a consent in a given status may record, and
  **total over `ConsentStatus`**: `GRANTED → {CONSENT_GIVEN, CONSENT_RESUMED, CONSENT_RE_CONFIRMED}`,
  `DRAFT → {}` explicitly, and for every other status the single entry `EVENT_STATES` already
  derives. `DRAFT`'s entry has to be written out because `EVENT_STATES` deliberately omits the key —
  its own doc says "`DRAFT` records nothing" — and a missing key in a validation table is a null
  lookup rather than a refusal. An empty set refuses every event, which is what the omission means.
  `EVENT_STATES` keeps its job — the event a *transition* defaults to — and this table answers the
  wider question "which events may a consent in this status record", which is the one both `affirm`
  here and step 4's `transitionRecordedAs` ask. Introducing it here rather than in step 4 is what
  keeps the factory to one table: a reader never has to know that re-confirmation is filed outside
  it.
- `affirm(Consent consent, String actor)` — calls `requireAffirmable`, validates
  `CONSENT_RE_CONFIRMED` against `ADMISSIBLE_EVENTS` for the consent's unchanged status, builds the
  event and returns `RecordedConsent(consent, event, false)`. It lives in the factory because the
  factory is the only place a consent and its event are produced together; it is the one event
  `EVENT_STATES` cannot derive, since no status changed.

**Writer — `ConsentWriter.affirm(UUID consentId, String actor)`:** `@Transactional`, re-reads under
`findByIdForUpdate`, saves the event only. The consent row is deliberately **not** updated, so
`updatedAt` keeps dating state changes as `Consent.yaml` documents it; the re-confirmation is dated
by its own event.

**Spec:** `/consents/{id}/re-confirm` → `operationId: reConfirmConsent`, `USER`, `UserPrincipal`,
no body, `200` → `Consent` (the record, unchanged). The description states plainly that the status
does not move and what the operation is for — an affirmation recorded in the audit trail.
`SPECIFIED_OPERATION_COUNT` 24 → 25.

**Service / API:** `ConsentLifecycleService.reConfirm(User subject, UUID consentId)` with the same
`404`-on-foreign-consent rule; a `ConsentLifecycleController.reConfirmConsent` handler.

**Tests:** `ConsentFactoryTest` — affirm appends `CONSENT_RE_CONFIRMED` and leaves the status alone;
`ADMISSIBLE_EVENTS.keySet()` covers every `ConsentStatus` value, which a per-entry parity check
cannot see, since a missing key has no entry to compare; and every entry of `EVENT_STATES` is
admissible under `ADMISSIBLE_EVENTS` for its own status, so the two tables cannot drift apart;
`ConsentStateMachineTest` — parameterized over every status, only `GRANTED` is affirmable;
`ConsentLifecycleIT` — the stored status and `updatedAt` are untouched while exactly one event is
appended (AC 4, 11); matrix row `RE_CONFIRM_CONSENT`.

**Acceptance:** AC 4.

### Step 4: Resume — two kinds of caller, and the grant a catalog may only relay

Delivers `POST /consents/{id}/resume` (US-CM-010): `PENDING|DRAFT → GRANTED` on a consent addressed
by id, reachable by the subject and by a `CATALOG` caller relaying the subject's decision.

**ADR first — `docs/adr/0010-a-catalog-relayed-resume-names-the-subject.md`, written in this step
and not in step 6.** A `CATALOG` caller naming a user and moving a `PENDING` row to `GRANTED` is a
consent recorded without the subject acting in this service, which is the shape ADR-0009 abolished
("there is no parameter naming a user"; "a request confers nothing"). The route may not land before
the reconciliation does, so the ADR is part of this step's commit. What it records:

- **What the catalog relays.** The subject takes the decision in the catalog's own flow — the flow
  this operation *resumes* — and the catalog relays it. This service cannot verify the relay beyond
  trusting the catalog's token, and the ADR says so plainly rather than implying stronger evidence.
- **How narrow the exception is.** A catalog caller may only advance a consent that already exists,
  addressed by its id, in a status that is still open. It cannot create one, cannot choose the
  notice, and cannot choose the subject: `userIdentifier` is checked *against* the stored row rather
  than used to select it, so a mismatch is `404` and never a grant recorded for someone else.
- **How the trail distinguishes it.** The event records the relay, not the subject — the actor rule
  in "Conventions every step follows", `catalog:<issuer>:<sub>` — so a reader can always tell a relayed grant from one the subject made here directly.
  A relayed grant indistinguishable from a direct one is the part that would be indefensible.
- **What a relayed resume may close.** Recording the grant supersedes whatever grant is in force
  for the same (user, notice) pair — the same act `decide` performs when the subject calls here
  directly. It stays inside the exception's bounds, because the catalog chooses neither the subject
  nor the notice: the only consent it can close is the one its own relayed decision replaces. The
  revocation carries the `catalog:` actor, so the trail shows who closed it. Refusing instead would
  leave the relay no way forward at all, the route that adopts the request in place
  (`POST /consents`) being `USER`-only.
- **The remedy if the dataspace disagrees.** Dropping `CATALOG` from the route's `x-roles` removes
  the exception without touching the service, which is why the shape keeps that cheap.

**First, assert what the generator emits for an optional body.** All ten request bodies in this
specification today are `required: true`, so what `java-micronaut-server` emits for
`required: false` is unverified — and the whole plain-`USER` half of this operation rests on it.
If the generated parameter is `@Body ResumeConsent` without
`@Nullable`, Micronaut refuses a bodyless `POST` with a `400` before the handler runs, and a subject
resuming their own consent — who has nothing to say in a body — can never reach the route. This is
the same trap AGENTS.md records for `items:` degrading to `List<Object>`: assert the generated
signature before building on it. So the step's first commit adds the operation and schema, runs
`./mvnw generate-sources`, and reads the parameter off
`target/generated-sources/openapi/.../AbstractConsentLifecycleController.java`.

If it is not nullable, the fallback is to declare the body `required: true` with every property
optional, state in the operation description that a `USER` caller posts `{}`, and record the
deviation from the ticket's "Request Body: empty for a USER caller" on the ticket. The fallback is
named here so the step cannot quietly stall on the discovery.

**Schema — `api/components/schemas/ResumeConsent.yaml`** (registered in `openapi.yaml`'s
`components.schemas`): one optional `userIdentifier`, documented as required of a `CATALOG` caller
and **ignored** for a `USER` caller, exactly as `POST /consents` ignores a user named in the body —
for a subject's own call the token is the only statement of who decided. No property is `nullable`.

**Spec:** `/consents/{id}/resume` → `operationId: resumeConsent`, tag `Consent Lifecycle`,
`security: [{bearerAuth: [USER, CATALOG]}]`, `x-roles: [USER, CATALOG]`,
`x-principal: ConsentManagerPrincipal` (the only declarable type holding both roles — the
`getPrivacyNotice` precedent), `requestBody` not required (subject to the assertion above), `$ref`
`ResumeConsent`, `200` → `Consent`, shared `400`/`401`/`403`/`404` plus `409`.
`SPECIFIED_OPERATION_COUNT` 25 → 26.

**Factory:** `EVENT_STATES` maps `GRANTED` to `CONSENT_GIVEN`, which a resume must not record. Step
3 already introduced `ADMISSIBLE_EVENTS` and its `GRANTED` entry already contains `CONSENT_RESUMED`,
so this step adds no table — only `transitionRecordedAs(Consent, ConsentStatus target,
ConsentEventState recordedAs, String actor, ConsentEventTrigger)`, which performs the same
`requireTransition` and `updateStatus` as `transition` but files the event as `recordedAs`, having
validated it against `ADMISSIBLE_EVENTS` for the target status. Extending the factory is what
AGENTS.md prescribes over hand-building an event.

**The notice guard — resume is the second route into `GRANTED`, so it carries the first one's
guard.** `ConsentService.decide` passes every grant through its private `consentable(...)`: `404` on
an unknown notice, `400` on an archived one ("an archived notice states terms no longer in force"),
`400` on a notice naming no consumer. A `PENDING` opened by `POST /consents/requests` can sit for a
long time, and the notice can be regenerated — and therefore archived — in the meantime. Without the
guard, resuming that request would record a grant against terms no longer in force, while answering
the very same request through `POST /consents` would be refused. So this step extracts `consentable`
into a new `@Singleton service/ConsentNoticeGuard` (`ConsentService` delegating to it, as it
delegates the read projection to step 1's `ConsentParties`), and `ConsentLifecycleService.resume`
calls it on the consent's `privacyNoticeId` before the writer runs. One definition of "a notice that
may be consented to", used by both routes into `GRANTED`.

The stored snapshot is **not** re-frozen. The terms the row carries are the ones the subject was
shown when the request was opened and decided on in the catalog's flow, and a resume records that
decision rather than taking a new one; `ConsentWriter.answer` re-freezes precisely because `decide`
*is* a fresh election. The guard is what makes keeping them safe: an archived notice is refused, so
a resumed grant can never carry terms that are out of force. Where the notice was regenerated, the
subject answers the new one through `POST /consents`, which is the route that elects scope.

**Writer — `ConsentWriter.resume(UUID consentId, String actor)`:** `@Transactional`, and
deliberately symmetric with `decide`. It takes `findByUserIdAndPrivacyNoticeIdForUpdate` on the
consent's (user, notice) pair — the same lock the grant flow takes, so a resume and a concurrent
grant of the same pair serialise instead of interleaving — and under that lock reuses the private
`revokeOpenGrant` to close whatever grant is in force for the pair, moves the addressed row through
`transitionRecordedAs(… GRANTED, CONSENT_RESUMED …)`, and — exactly as `answer` does, and only when
this same call revoked something — sets its `parentConsentId` to the grant it closed. The
revocation, the status, the event and the link are one unit of work (AC 6, AC 12).

An earlier revision of this plan dropped the supersede, arguing that the resumed row is *older* than
the grant it would point at and that the link would therefore run backwards against ADR-0009. That
argument does not hold, and `ConsentWriter.answer` on the branch is the counter-example:
`decide` revokes the open grant and then points the adopted `PENDING` — likewise older than the
grant it closes, since the only interleaving that produces the pair is the one below — at it, with
the doc comment "It is re-pointed only when this same decision revoked a consent, because then that
revocation is what it superseded." ADR-0009's rule is about pointer *direction*, not row age: "a
superseded consent is never mutated to point at its successor". Writing `parentConsentId` on the row
being moved, naming what that move closed, is precisely the single upward link the ADR prescribes,
and the revoked row is not touched. The ADR already records that a consent's own row cannot date its
decision, so a row being older than its parent is a shape it expects rather than one it forbids.

The pair this closes is rare but reachable, and the plan should say how: `ConsentWriter.request`
refuses a second row while an `OPEN_STATUSES` row stands and `decide` answers an open `PENDING` in
place, but `request`'s own doc comment records that under `READ COMMITTED` "an ask racing a decision
that opens one" can land a `PENDING` beside a freshly created `GRANTED`, because a row the racer
inserted was never in this statement's snapshot and no lock makes it appear. Superseding means such
a `PENDING` is resumable like any other, instead of dead-ending on the unique index — which matters
most for a `CATALOG` caller, whose only escape would otherwise be `POST /consents`, a route it
cannot reach.

The `409` does not disappear; it stops being the ordinary case. A `GRANTED` inserted outside this
statement's snapshot is invisible to the locking read and still collides with
`uq_consents_granted_per_user_notice`, so the service catches `DataAccessException` outside the
transaction as `ConsentService.record` does. And resume answers only the row it was addressed to: it
leaves any other open request for the pair alone, because it is the answer to one identified request
rather than to the pair, and `decide` already closes the stragglers.

**Service — `ConsentLifecycleService.resume(ConsentManagerPrincipal caller, UUID consentId,
@Nullable String userIdentifier)`:** an exhaustive `switch` over the sealed principal —
`UserPrincipal` requires ownership (`404` otherwise) and ignores the body;
`CatalogPrincipal` requires a non-blank `userIdentifier` (`400` when absent) that resolves to the
consent's user (`404` otherwise, AC 10); `ParticipantPrincipal` is unreachable under the route's
`@Secured` rule and is refused with `ForbiddenException`, as `CallerScope.of` refuses its own
impossible branch. The writer's `DataAccessException` is caught **outside** the transaction and
turned into a `409` through `UniqueViolation.rejected`, exactly as `ConsentService.record` does.

**Tests:** `ConsentLifecycleIT` — resume from `PENDING` and from `DRAFT` grants, flips `consented`
and appends one `CONSENT_RESUMED` event; with a grant in force for the same pair, that grant is
`REVOKED` with its own event and the resumed row's `parentConsentId` names it, while with no grant
in force `parentConsentId` is left exactly as stored (the two halves of AC 6, and the assertion that
keeps the link from being written unconditionally); from every terminal status it is `400`; a
`CATALOG` caller with a mismatched or absent `userIdentifier` is `404`/`400` (AC 10), and a relayed
resume writes the `catalog:<issuer>:<sub>` actor while the subject's own writes the subject's
identifier;
a consent whose notice has since been archived is `400` and writes nothing, as is one whose notice
names no consumer — the guard above, asserted rather than assumed; and a resumed row keeps the
snapshot it was opened with. `ConsentLifecycleSurfaceIT` — a `USER` token and a `CATALOG` token
both reach it, a `PARTICIPANT` token does not, and a `USER` resume sent with **no request body at
all** succeeds, which is the end-to-end form of the generator assertion above (the matrix row always
sends a body, so it would not catch a non-nullable parameter). Matrix row `RESUME_CONSENT` — USER
`404`, CATALOG `404`, PARTICIPANT/OUTSIDER denied, ANONYMOUS challenged; the row's body carries
`userIdentifier`, which the USER case must ignore rather than reject.

**Acceptance:** AC 5, 6, 10.

### Step 5: The total matrix and the concurrency the index has to settle

The per-operation tests of steps 1–4 cover each operation's own cases; this step makes the coverage
total and exercises the races.

- `service/ConsentLifecycleMatrixIT` — a parameterized test over the full cross product of the five
  operations and all seven `ConsentStatus` values, each case declaring the expected outcome (the new
  status, or the `400` naming both states). Driven from an enum of operations so that **adding a
  sixth operation without extending the matrix is a compile error**, the same way
  `EndpointRoleMatrixIT` is total over the parsed operations (AC 15). The expected outcomes are read
  off the existing table, not invented: `terminate` succeeds from `GRANTED`, `PENDING` **and**
  `DRAFT` (per step 2), `revoke` only from `GRANTED`, `refuse` only from `PENDING`/`DRAFT`, `resume`
  only from `PENDING`/`DRAFT`, and `re-confirm` only from `GRANTED` — everything else is the `400`.
- **How the matrix reaches the statuses no endpoint produces.** `DRAFT` is deliberately absent from
  `ConsentStateMachine.CREATABLE` and `EXPIRED` has no producer at all, so those fixtures cannot be
  built through the service. The IT seeds them by saving a `Consent` in the wanted status straight
  through `ConsentRepository`, which is allowed: `ConsentStatusInvariantTest` scans `src/main/java`
  only, so a test may set a status the production code may not.
- `service/ConsentLifecycleAtomicityIT` — modelled on `ConsentGrantAtomicityIT`
  (`@MicronautTest(transactional = false)`, two threads on a latch): a revocation racing a re-grant
  of the same (user, notice) pair leaves exactly one `GRANTED` row, and a resume racing a grant of
  the same pair leaves exactly one, with the loser answered `409` rather than `500` (AC 16) — the
  resume case exercises both halves of step 4's writer: the supersede when the grant is visible to
  the locking read, and the `409` when it was inserted outside that statement's snapshot. A second
  case: two concurrent revocations of the same consent append exactly one event, the loser `400` —
  which is what the `findByIdForUpdate` re-read from step 1 buys.
- An assertion, in the matrix IT or alongside it, that a refused operation leaves **no** event row —
  status and event are written together or not at all (AC 12).

**Acceptance:** AC 15, 16, and AC 12 proven rather than argued.

### Step 6: The notification seam, the deferral ADR and the documentation

AC 13 and AC 14 name notifications that belong to TICKET-013 and TICKET-020; no notification or
receipt-signing subsystem exists on the work branch, and ticket #71 shipped without one. This step
leaves an honest, documented seam rather than inventing the subsystem or dropping the criteria
silently.

- `service/ConsentStateChangeLog` (new `@Singleton`) — emits one structured log event per lifecycle
  move (consent id, subject, parties, held status, new status, actor, trigger) through the Logback
  JSON encoder already configured. `ConsentLifecycleService` calls it after each successful write,
  in one place. It is the point where TICKET-013's user notification and TICKET-020's state-change
  notification attach; it is not a queue and does not pretend to be one.
- `docs/adr/0011-a-lifecycle-move-is-recorded-here-and-notified-elsewhere.md` — why revocation is
  effective in this service's records the moment it is written while being effective in practice
  only once participants are told; why `POST …/revoke` rather than `DELETE`; why re-confirmation is
  an event and not a transition; and that delivery is deferred to TICKET-013/TICKET-020, with the
  seam named. The `CATALOG` relay is **not** argued here — ADR-0010 settles it in step 4, where the
  route that needs it lands.
- `docs/security.md` — a subsection beside "The two consent routes are deliberately asymmetric"
  recording the lifecycle authority rule: a `USER` operation checks ownership and answers `404`, a
  `PARTICIPANT` operation checks party membership and answers `403`, and role alone is never
  sufficient on any of the five.
- `AGENTS.md` — the new files in the project tree (`ConsentLifecycleService`, `ConsentParties`,
  `ConsentNoticeGuard`, `ConsentStateChangeLog`, `ConsentLifecycleController`), the
  `Consent Lifecycle` tag and its controller, `SPECIFIED_OPERATION_COUNT` **26**, the affirmation
  rule and the single `ADMISSIBLE_EVENTS` table in the consent-lifecycle convention, ADR-0010 and
  ADR-0011 in the important-files list, and the ADR range in the project tree raised accordingly.
  In the same pass, complete the service-layer record list in the project tree, which reads as
  exhaustive while omitting six types already on the branch — `ContractParties`, `GeneratedNotice`,
  `NoticeContent`, `NoticeResolution`, `ResolvedNotice` and `RegistrationOutcome`. Every edit to
  `AGENTS.md` this ticket needs is gathered here, in the one step that performs them.
  **Deferred, and deliberately so.** An implementation agent is forbidden from editing `AGENTS.md`
  — it is appended to the agent system prompt and cached across sessions, so any edit invalidates
  that cache for every later session. Step 6 therefore leaves the file untouched and leaves this
  bullet standing as the exact delta for a plan-mode agent to apply. Nothing else in the step, and
  nothing in the build, depends on it; the cost of the deferral is that `SPECIFIED_OPERATION_COUNT`
  and the file list in `AGENTS.md` read stale until it is applied.

**Acceptance:** the seam AC 13 and AC 14 attach to exists and is named; the criteria themselves are
deviations — see "Criteria deliberately not met as written". The repository context file is left to
the deferred pass described above.

---

## Criteria deliberately not met as written

Two deviations, gathered here because this is the section a reader amending the ticket reads, rather
than left argued only inside the step that causes them. (AC 6 was listed here in an earlier revision
of this plan; it is not a deviation — step 4 implements the revoke-and-link as written.)

**AC 3 — `terminate` is wider than "moves `GRANTED` → `TERMINATED`".** Step 2 also admits `PENDING`
and `DRAFT`, because `ConsentStateMachine.UNANSWERED_MOVES` contains `TERMINATED` for both and this
plan changes no edge. Narrowing the operation to the criterion would mean either an `if` in the
service that contradicts the one transition table, or removing an edge erasure and deregistration
depend on — which "Out of scope" excludes. The consequence is real and worth the ticket's attention:
a participant can unilaterally close a request the subject has not answered, which overlaps what
`refuse` does from the subject's side. The alternative, if that is unwanted, is a narrower guard
expressed as its own admissible-move set rather than as a special case, and it should be decided
before step 2 lands.

**AC 13 and AC 14 — the notifications.** "Termination notifies the data subject" and "revocation
emits a participant notification" are not met: step 6 emits a structured log line per lifecycle move
and nothing is delivered to anyone. No notification subsystem exists on the branch, TICKET-013 and
TICKET-020 own it, and ticket #71 shipped on the same footing. `ConsentStateChangeLog` is named as
the seam those tickets attach to, which makes the gap traceable — but traceable is not met, and the
criteria need moving to those tickets rather than ticking here.

---

## Out of scope

- Receipt signing (TICKET-011), notification delivery (TICKET-013, TICKET-020) and data-exchange
  triggering, which the revision removed from this ticket.
- Refusing a grant against a privacy notice whose provider or consumer has deregistered — the window
  step 2 describes. That guard belongs to `ConsentService.consentable` and to the grant flow, not to
  these five operations, and widening it here would change behaviour ticket #71 settled. It is named
  so the omission is a decision rather than an oversight.
- Sweeping `consent_events.actor` when ADR-0007 erases a data subject. The lifecycle moves record
  the subject's own identifier as the actor, but so does the grant flow merged for ticket #71 —
  `ConsentWriter.decide` has always written `subject.getIdentifier()` there — so an erasure leaving
  that string standing in the trail is a gap these five operations widen rather than open. Closing
  it means a statement that rewrites the column, a verifier test and a decision on whether a
  party's entries on the same consents are in scope; that belongs to a ticket of its own, not to
  this one. It is named here so the omission is a decision rather than an oversight.
- `GET /consents` / `GET /consents/{id}` and the consent history read side.
- Any change to `ConsentStateMachine.TRANSITIONS`, to `ConsentEventState`, or to the database
  schema — all three already carry what these operations need.
