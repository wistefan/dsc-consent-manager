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
  revoke-and-link of an open grant (`revokeOpenGrant` + `parentConsentId`), which step 4 reuses
  rather than reimplements.
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
  commit that adds an operation: 21 → 22 → 24 → 25 → 26 across steps 1–4.
- **Add a row per operation to `EndpointRoleMatrixIT.Endpoint`** in the same commit —
  `everySpecifiedOperationIsCovered` is total over the parsed operations and matches
  `"<method> <path>"` with the template intact (`"post /consents/{id}/revoke"`) — plus its
  `roleMatrix()` row giving the expected status for USER, PARTICIPANT, CATALOG, OUTSIDER and
  ANONYMOUS. A PARTICIPANT-only route is additionally swept by `participantScopedEndpoints`.
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
  states it is how a subject declines a participant's `PENDING` request, and that refusing a
  `GRANTED`, `REVOKED` or `REFUSED` record is `400` naming both states.
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
  ConsentEventTrigger trigger)` — `@Transactional`: re-read under `findByIdForUpdate`,
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

Delivers `POST /consents/{id}/terminate` (US-CM-008): the organisational end of an arrangement,
`GRANTED → TERMINATED`, with the participant as actor.

**Spec:** `/consents/{id}/terminate` → `post`, `operationId: terminateConsent`, tag
`Consent Lifecycle`, `security: [{bearerAuth: [PARTICIPANT]}]`, `x-roles: [PARTICIPANT]`,
`x-principal: ParticipantPrincipal`, no body, `200` → `Consent`, shared `400`/`401`/`403`/`404`.
The description says termination is not the subject withdrawing, and that a participant the consent
names on neither side is `403` — distinct from the `404` a foreign *user* gets, because a
participant that is party to nothing still legitimately knows the consent exists through its own
records. `SPECIFIED_OPERATION_COUNT` 23 → 24.

**Service:** `ConsentLifecycleService.terminate(Participant caller, UUID consentId)` — load
(`404`), then require `caller.getId()` equal the consent's `providerId` or `consumerId`, throwing
`ForbiddenException` otherwise (AC 9), reusing the phrasing of `ConsentService.requireParty`; then
the same `ConsentWriter.move` to `TERMINATED` with the participant identifier as actor.

**API:** `ConsentLifecycleController.terminateConsent(ParticipantPrincipal principal, UUID id)`
calls `principal.requireActive()` **before** loading the consent — a deregistered participant must
not end an arrangement it has already left, and `participantScopedEndpoints` sweeps this route
expecting `403`, which only holds if the authority check precedes the `404`.

**Tests:** extend `ConsentLifecycleIT` (party terminates from `GRANTED`; non-party `403`; a
non-`GRANTED` consent `400`) and `ConsentLifecycleSurfaceIT` (provider and consumer tokens both
admitted, a third participant refused, `USER`/`CATALOG` refused); `EndpointRoleMatrixIT` row
`TERMINATE_CONSENT` — PARTICIPANT `404` on an absent id, all others denied — and confirm the route
is picked up by `participantScopedEndpoints`. Confirm `noWriteRouteLetsTheCallerNameTheRecord`
still passes: `{id}` addresses a consent, not a participant.

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

### Step 4: Resume — two kinds of caller, and the supersede that must share one transaction

Delivers `POST /consents/{id}/resume` (US-CM-010): `PENDING|DRAFT → GRANTED`, reachable by the
subject and by a `CATALOG` caller acting for them.

**Schema — `api/components/schemas/ResumeConsent.yaml`** (registered in `openapi.yaml`'s
`components.schemas`): one optional `userIdentifier`, documented as required of a `CATALOG` caller
and **ignored** for a `USER` caller, exactly as `POST /consents` ignores a user named in the body —
the token is the only statement of who decided. No property is `nullable`.

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

**Writer — `ConsentWriter.resume(UUID consentId, String actor)`:** `@Transactional` and the whole
of AC 6/AC 12 in one unit — take `findByUserIdAndPrivacyNoticeIdForUpdate` for the consent's
(user, notice) pair, reuse the existing `revokeOpenGrant` to close whatever grant is in force and
report its id, set it as the resumed record's `parentConsentId`, then
`transitionRecordedAs(... GRANTED, CONSENT_RESUMED ...)` and save. Nothing downstream is called, so
the hazard behind original bug 18.8 does not exist here.

**Service — `ConsentLifecycleService.resume(ConsentManagerPrincipal caller, UUID consentId,
@Nullable String userIdentifier)`:** an exhaustive `switch` over the sealed principal —
`UserPrincipal` requires ownership (`404` otherwise) and ignores the body;
`CatalogPrincipal` requires a non-blank `userIdentifier` (`400` when absent) that resolves to the
consent's user (`404` otherwise, AC 10); `ParticipantPrincipal` is unreachable under the route's
`@Secured` rule and is refused with `ForbiddenException`, as `CallerScope.of` refuses its own
impossible branch. The writer's `DataAccessException` is caught **outside** the transaction and
turned into a `409` through `UniqueViolation.rejected`, exactly as `ConsentService.record` does.

**Tests:** `ConsentLifecycleIT` — resume from `PENDING` and from `DRAFT` grants and links the
revoked predecessor (AC 5, 6); from every terminal status it is `400`; a `CATALOG` caller with a
mismatched or absent `userIdentifier` is `404`/`400` (AC 10). `ConsentLifecycleSurfaceIT` — a
`USER` token and a `CATALOG` token both reach it, a `PARTICIPANT` token does not. Matrix row
`RESUME_CONSENT` — USER `404`, CATALOG `404`, PARTICIPANT/OUTSIDER denied, ANONYMOUS challenged;
the row's body carries `userIdentifier`, which the USER case must ignore rather than reject.

**Acceptance:** AC 5, 6, 10.

### Step 5: The total matrix and the concurrency the index has to settle

The per-operation tests of steps 1–4 cover each operation's own cases; this step makes the coverage
total and exercises the races.

- `service/ConsentLifecycleMatrixIT` — a parameterized test over the full cross product of the five
  operations and all seven `ConsentStatus` values, each case declaring the expected outcome (the new
  status, or the `400` naming both states). Driven from an enum of operations so that **adding a
  sixth operation without extending the matrix is a compile error**, the same way
  `EndpointRoleMatrixIT` is total over the parsed operations (AC 15).
- `service/ConsentLifecycleAtomicityIT` — modelled on `ConsentGrantAtomicityIT`
  (`@MicronautTest(transactional = false)`, two threads on a latch): a revocation racing a re-grant
  of the same (user, notice) pair leaves exactly one `GRANTED` row, and a resume racing a grant
  leaves exactly one, with the loser answered `409` rather than `500` (AC 16). A second case: two
  concurrent revocations of the same consent append exactly one event, the loser `400` — which is
  what the `findByIdForUpdate` re-read from step 1 buys.
- An assertion, in the matrix IT or alongside it, that a refused operation leaves **no** event row —
  status and event are written together or not at all (AC 12).

**Acceptance:** AC 15, 16, and AC 12 proven rather than argued.

### Step 6: The notification seam, the ADR and the documentation

AC 13 and AC 14 name notifications that belong to TICKET-013 and TICKET-020; no notification or
receipt-signing subsystem exists on the work branch, and ticket #71 shipped without one. This step
leaves an honest, documented seam rather than inventing the subsystem or dropping the criteria
silently.

- `service/ConsentStateChangeLog` (new `@Singleton`) — emits one structured log event per lifecycle
  move (consent id, subject, parties, held status, new status, actor, trigger) through the Logback
  JSON encoder already configured. `ConsentLifecycleService` calls it after each successful write,
  in one place. It is the point where TICKET-013's user notification and TICKET-020's state-change
  notification attach; it is not a queue and does not pretend to be one.
- `docs/adr/0010-a-lifecycle-move-is-recorded-here-and-notified-elsewhere.md` — why revocation is
  effective in this service's records the moment it is written while being effective in practice
  only once participants are told; why `POST …/revoke` rather than `DELETE`; why re-confirmation is
  an event and not a transition; why a `CATALOG` caller must name the subject while a `USER` caller
  must not; and that delivery is deferred to TICKET-013/TICKET-020, with the seam named.
- `docs/security.md` — a subsection beside "The two consent routes are deliberately asymmetric"
  recording the lifecycle authority rule: a `USER` operation checks ownership and answers `404`, a
  `PARTICIPANT` operation checks party membership and answers `403`, and role alone is never
  sufficient on any of the five.
- `AGENTS.md` — the new files in the project tree, the `Consent Lifecycle` tag and its controller,
  `SPECIFIED_OPERATION_COUNT` **26**, the affirmation rule and the admissible-event-state table in
  the consent-lifecycle convention, and ADR-0010 in the important-files list.

**Acceptance:** AC 13, 14 scoped explicitly and traceable; the repository context file matches the
merged state.

---

## Out of scope

- Receipt signing (TICKET-011), notification delivery (TICKET-013, TICKET-020) and data-exchange
  triggering, which the revision removed from this ticket.
- `GET /consents` / `GET /consents/{id}` and the consent history read side.
- Any change to `ConsentStateMachine.TRANSITIONS`, to `ConsentEventState`, or to the database
  schema — all three already carry what these operations need.
