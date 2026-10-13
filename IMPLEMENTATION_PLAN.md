# Implementation Plan: Consent Manager - Reimplementation - Consent Lifecycle Operations

## Overview
Add the five operations that move a consent after it exists — `revoke`, `refuse`, `terminate`,
`re-confirm` and `resume` — as `POST /consents/{id}/<verb>` routes on top of the grant flow merged
for ticket #71. Every one of them authorises the caller against the consent itself (never on role
alone), guards its move through the existing `ConsentStateMachine`, and writes the status and its
audit event in one transaction. No database migration and no new state-table edge are needed: the
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
  private `revokeOpenGrant` + `parentConsentId` supersede belongs to the *decision* flow and stays
  there — step 4 explains why a resume must not reuse it.
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
  `resume`; the participant's `identifier` for `terminate`; and, for a `CATALOG`-relayed `resume`,
  the catalog token's verified `sub` under a `catalog:` prefix constant, because `CatalogPrincipal`
  carries no dataspace identifier and a relayed grant must not read in the trail as one the subject
  made here directly (see step 4).
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

**Tests:** extend `ConsentLifecycleIT` (a party terminates from `GRANTED`, and equally from
`PENDING` and `DRAFT` — the permitted edges, asserted rather than assumed; a non-party `403`; a
consent in each terminal status `400` naming both states) and `ConsentLifecycleSurfaceIT` (provider
and consumer tokens both admitted, a third participant refused, `USER`/`CATALOG` refused, and the
case the sweep misses: a participant whose row carries `deregistered_at` is `403` while the same
token still reads `GET /participants/me`); `EndpointRoleMatrixIT` row `TERMINATE_CONSENT` —
PARTICIPANT `404` on an absent id, all others denied — and confirm the route is picked up by
`participantScopedEndpoints`. Confirm `noWriteRouteLetsTheCallerNameTheRecord` still passes: `{id}`
addresses a consent, not a participant.

**Acceptance:** AC 3, 9.

### Step 3: Re-confirm — an affirmation, not a transition

Delivers `POST /consents/{id}/re-confirm` (US-CM-009): the status does not change, only an event is
appended, so it cannot go through `requireTransition` at all. The pre-revision `triggerDataExchange`
query parameter is not carried over.

**State machine — `service/ConsentStateMachine`:** add `AFFIRMABLE = EnumSet.of(GRANTED)` and
`requireAffirmable(ConsentStatus held)`, raising `BadRequestException` naming the held status and
the one re-confirmation requires. It belongs in the one table rather than as an `if` in the service,
for the same reason every other rule does.

**Factory — `service/ConsentFactory.affirm(Consent consent, String actor)`:** calls
`requireAffirmable`, then builds the `CONSENT_RE_CONFIRMED` event against the unchanged consent and
returns `RecordedConsent(consent, event, false)`. It lives in the factory because the factory is the
only place a consent and its event are produced together; it is the one event the status→event table
cannot derive, since no status changed.

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
- **How the trail distinguishes it.** The event records the relay, not the subject — see the actor
  rule below — so a reader can always tell a relayed grant from one the subject made here directly.
  A relayed grant indistinguishable from a direct one is the part that would be indefensible.
- **The remedy if the dataspace disagrees.** Dropping `CATALOG` from the route's `x-roles` removes
  the exception without touching the service, which is why the shape keeps that cheap.

**Schema — `api/components/schemas/ResumeConsent.yaml`** (registered in `openapi.yaml`'s
`components.schemas`): one optional `userIdentifier`, documented as required of a `CATALOG` caller
and **ignored** for a `USER` caller, exactly as `POST /consents` ignores a user named in the body —
for a subject's own call the token is the only statement of who decided. No property is `nullable`.

**Spec:** `/consents/{id}/resume` → `operationId: resumeConsent`, tag `Consent Lifecycle`,
`security: [{bearerAuth: [USER, CATALOG]}]`, `x-roles: [USER, CATALOG]`,
`x-principal: ConsentManagerPrincipal` (the only declarable type holding both roles — the
`getPrivacyNotice` precedent), `requestBody` not required, `$ref` `ResumeConsent`, `200` →
`Consent`, shared `400`/`401`/`403`/`404` plus `409`. `SPECIFIED_OPERATION_COUNT` 25 → 26.

**Factory:** the status→event table maps `GRANTED` to `CONSENT_GIVEN`, which a resume must not
record. Add a second table of the event states admissible for a target status
(`GRANTED → {CONSENT_GIVEN, CONSENT_RESUMED}`) and
`transitionRecordedAs(Consent, ConsentStatus target, ConsentEventState recordedAs, String actor,
ConsentEventTrigger)`, which validates `recordedAs` against it. Extending the factory is what
AGENTS.md prescribes over hand-building an event.

**Writer — `ConsentWriter.resume(UUID consentId, String actor)`:** `@Transactional`, and
deliberately narrow. It takes `findByUserIdAndPrivacyNoticeIdForUpdate` on the consent's
(user, notice) pair — the same lock the grant flow takes, so a resume and a concurrent grant of the
same pair serialise instead of interleaving (AC 6) — reads the addressed row under that lock, then
`transitionRecordedAs(… GRANTED, CONSENT_RESUMED …)` and saves the status with its event in one unit
of work (AC 12).

It does **not** revoke an open grant and does **not** write `parentConsentId`. An earlier draft of
this plan had it reuse `revokeOpenGrant` and point the resumed row at the grant it closed; both are
wrong. ADR-0009 fixes lineage as a single upward link walked from the newest end and forbids
mutating a superseded row to point at its successor — and the resumed row is *older* than any grant
it would be pointed at, so the link would run backwards. The pair that supersede would close should
not arise either: `ConsentWriter.request` refuses a second row while an `OPEN_STATUSES` row stands,
and `decide` answers an open `PENDING` in place, so a `PENDING` and a `GRANTED` for one pair have no
producer. If one exists regardless, `uq_consents_granted_per_user_notice` refuses the second
`GRANTED` and the caller is answered `409` — the honest answer to a state this service does not
expect, rather than a silent rewrite of a grant the subject gave elsewhere.

**Service — `ConsentLifecycleService.resume(ConsentManagerPrincipal caller, UUID consentId,
@Nullable String userIdentifier)`:** an exhaustive `switch` over the sealed principal —
`UserPrincipal` requires ownership (`404` otherwise) and ignores the body;
`CatalogPrincipal` requires a non-blank `userIdentifier` (`400` when absent) that resolves to the
consent's user (`404` otherwise, AC 10); `ParticipantPrincipal` is unreachable under the route's
`@Secured` rule and is refused with `ForbiddenException`, as `CallerScope.of` refuses its own
impossible branch. The writer's `DataAccessException` is caught **outside** the transaction and
turned into a `409` through `UniqueViolation.rejected`, exactly as `ConsentService.record` does.

**Tests:** `ConsentLifecycleIT` — resume from `PENDING` and from `DRAFT` grants, flips `consented`
and appends one `CONSENT_RESUMED` event, while `parentConsentId` is left exactly as stored (the
lineage assertion that keeps the dropped supersede from returning); from every terminal status it is
`400`; a `CATALOG` caller with a mismatched or absent `userIdentifier` is `404`/`400` (AC 10), and a
relayed resume writes the `catalog:` actor while the subject's own writes the subject's identifier.
`ConsentLifecycleSurfaceIT` — a `USER` token and a `CATALOG` token both reach it, a `PARTICIPANT`
token does not. Matrix row `RESUME_CONSENT` — USER `404`, CATALOG `404`, PARTICIPANT/OUTSIDER
denied, ANONYMOUS challenged; the row's body carries `userIdentifier`, which the USER case must
ignore rather than reject.

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
  resume case is precisely what step 4 relies on in place of the supersede it dropped. A second
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
- `AGENTS.md` — the new files in the project tree, the `Consent Lifecycle` tag and its controller,
  `SPECIFIED_OPERATION_COUNT` **26**, the affirmation rule and the admissible-event-state table in
  the consent-lifecycle convention, ADR-0010 and ADR-0011 in the important-files list, and the ADR
  range in the project tree raised accordingly.

**Acceptance:** AC 13, 14 scoped explicitly and traceable; the repository context file matches the
merged state.

---

## Out of scope

- Receipt signing (TICKET-011), notification delivery (TICKET-013, TICKET-020) and data-exchange
  triggering, which the revision removed from this ticket.
- `GET /consents` / `GET /consents/{id}` and the consent history read side.
- Any change to `ConsentStateMachine.TRANSITIONS`, to `ConsentEventState`, or to the database
  schema — all three already carry what these operations need.
