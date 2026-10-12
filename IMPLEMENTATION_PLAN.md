# Implementation Plan: Consent Manager - Reimplementation - Consent Grant Flow

## Overview
Add the two operations that create consents: `POST /consents`, where the data subject — identified
solely from their token — grants or refuses against a privacy notice, and `POST /consents/requests`,
where a participant *asks* a linked user for consent and gets back a `PENDING` record that confers
nothing. Both funnel through one table-driven `ConsentStateMachine` and one factory that derives
`consented` from the target status, so no code path can record a refusal as a grant or a participant
self-grant.

The repository already carries the whole substrate: `Consent` (with `withStatus(...)`/
`updateStatus(...)` deriving `consented`, and `parentConsentId`), `ConsentStatus` (all seven states),
`ConsentEventState` (including `CONSENT_REQUESTED`), `ConsentSnapshot`, both consent repositories,
the partial unique index `uq_consents_granted_per_user_notice`, `PrivacyNotice` with `archivedAt`
and provider/consumer, `UserParticipantRepository.existsByIdUserIdAndIdParticipantId`, typed
principals and the RFC 7807 error layer. **No Flyway migration is required** — V1 already defines
`consents`, `consent_events`, the FK policies, the status check constraint and every index this flow
queries.

## Constraints this plan is bound by

These override the ticket text where they disagree; the ticket was written against a different repo.

- **Package is `com.seamware.consentmanager`**, not `eu.prometheusx.consentmanager`.
- **No `api/paths/*.yaml`.** `SpecReferenceResolutionTest.noPathItemIsExternalised` fails the build
  on an externalised path item. Path items go **inline** in `api/openapi.yaml`; only schemas are
  externalised under `api/components/schemas/`, and every authored component file must be reachable
  by `$ref` from the root document or the same test fails.
- **`SpecSecurityConsistencyTest.SPECIFIED_OPERATION_COUNT` is 19 today** (file line 87). It must be
  raised in the same commit that adds an operation: to 20 in Step 3, to 21 in Step 5. Each operation
  declares agreeing `security: [{bearerAuth: [ROLE]}]`, `x-roles:` and `x-principal:`, and refs the
  shared `#/components/responses/Unauthorized` and `/Forbidden`.
- **`EndpointRoleMatrixIT` is total over the specification** (`everySpecifiedOperationIsCovered`).
  Every new operation needs a row in its `Endpoint` enum with the expected status per caller role,
  and a PARTICIPANT-only route is additionally swept by `participantScopedEndpoints`, which demands
  it refuse an unregistered participant.
- **Generated-model name collision.** The response schema `Consent.yaml` generates
  `api.generated.model.Consent`, which collides with `domain.Consent`; the mapper fully qualifies
  one side, as `PrivacyNoticeMapper` already does for `PrivacyNotice`.
- **Response schemas declare no `nullable: true`** (it forces `JsonNullable`); optional means absent.
  A schema whose `items:` refs another named model must be declared inline in `openapi.yaml` with an
  internal `#/components/schemas/...` ref — not needed here if every array is an array of strings.
- **Micronaut AOP does not apply to self-invocation**, so the one transaction that revokes and
  re-grants lives on a separate bean, mirroring `PrivacyNoticeWriter`.
- Doc comments stay terse, named constants only, parameterized tests where the shape repeats.

## Out of scope (explicit seams, no placeholder code)

Receipt signing (TICKET-011), user and state-change notifications (TICKET-013 / TICKET-020),
`POST /consents/{id}/resume` and the refuse-a-pending operation (TICKET-009), the lineage endpoint
(TICKET-010), `DRAFT` creation, and the TTL expiry job. None is covered by an acceptance criterion
here. Do not add empty interfaces or TODO beans for them; Step 6 records where they will attach.

## Steps

### Step 1: Consent state machine, factory, and snapshot vocabulary

The single transition guard and the single creation path, with no API surface yet.

- `service/ConsentStateMachine.java` — a table-driven `@Singleton`. Two named tables: the statuses a
  consent may be *created* in (`GRANTED`, `REFUSED`, `PENDING` — **not** `DRAFT`) and an `EnumMap`
  of permitted transitions: `PENDING` → `{GRANTED, REFUSED}`, `DRAFT` → `{GRANTED, REFUSED}`,
  `GRANTED` → `{REVOKED, TERMINATED, EXPIRED}`, and `REVOKED`/`REFUSED`/`TERMINATED`/`EXPIRED` → `{}`
  (terminal). Public surface: `permits(from, to)`, `requireCreatable(target)` and
  `requireTransition(from, to)`, the latter two raising `BadRequestException` whose message names
  both the current state and the attempted target, built from one message-template constant.
- `service/ConsentFactory.java` — the only place a `Consent` is constructed or re-statused. It calls
  the state machine, then `Consent.withStatus(...)`, and returns the matching `ConsentEvent` from one
  `EnumMap` of target status → event state: `GRANTED`→`CONSENT_GIVEN`, `REFUSED`→`CONSENT_REFUSED`,
  `PENDING`→`CONSENT_REQUESTED`, `REVOKED`→`CONSENT_REVOKED`, `TERMINATED`→`CONSENT_TERMINATED`,
  `EXPIRED`→`CONSENT_EXPIRED`. `DRAFT` is absent from the creatable table *and* from the event
  table, so `requireCreatable(DRAFT)` and the factory give one answer rather than two: a `DRAFT`
  consent cannot be created in this ticket, and TICKET-009 adds it to both tables together with its
  event mapping rather than bypassing either. `DRAFT` keeps its *outgoing* transitions
  (`DRAFT` → `{GRANTED, REFUSED}`), which is a statement about resuming a draft, not creating one.
- `domain/ConsentSnapshot.java` — align the vocabulary with `PrivacyNoticePayload`, which its own
  javadoc defers to "the ticket that first writes a consent". The snapshot freezes the *effective*
  terms: `purposes` and `dataCategories` hold the elected subset (the notice's full lists when the
  decision elects none), and the non-scope terms — `dataReferences`, `recipients`,
  `retentionPeriod`, `controllerDetails`, `piiPrincipalRights`, `internationalTransfers`,
  `withdrawalOfConsent` — are copied verbatim from the payload, together with the notice's
  `contractUri` and `title` so the record stands alone after the notice is archived. "Verbatim"
  forces two shape changes worth naming
  here: `recipients` becomes `List<PrivacyNoticePayload.Recipient>` rather than `List<String>`, and
  the payload's `data` (`List<DataReference>`, the concrete offerings and resources) is **kept**,
  under the name `dataReferences` so it cannot be read as `dataCategories`. Keeping it is the point
  of the snapshot: the elected categories say what *kind* of data was consented to, the references
  say *which documents*, and an auditor reading a consent whose notice has since been archived needs
  both. Drop `jurisdiction`: no payload field feeds it and inventing one would be a fabricated audit
  record. Add a static factory
  `ConsentSnapshot.of(PrivacyNotice, electedPurposes, electedDataCategories)`.
  **Ripple:** all **seven** existing call sites of the 5-argument constructor must be updated
  (`grep -rl 'new ConsentSnapshot(' src/`) — `api/ParticipantUserLinkIT`, `api/UserErasureIT`,
  `api/ParticipantDeregistrationSurfaceIT`, `service/UserServiceTest`,
  `service/ParticipantDeregistrationAtomicityIT`, `service/ParticipantDeregistrationIT`,
  `service/UserErasureAtomicityIT`.

Tests (`src/test/java/com/seamware/consentmanager/`):
- `service/ConsentStateMachineTest` — parameterized over the **full 7×7 matrix** of
  `ConsentStatus.values()`: every pair either is in the table and is permitted, or is refused with a
  `BadRequestException` whose message contains both state names. A second parameterized case covers
  creatable versus non-creatable statuses over `@EnumSource`.
- `service/ConsentFactoryTest` — `consented` is `true` exactly for `GRANTED`; every creatable status
  yields exactly one event of the mapped state; a non-creatable status is refused.
- `domain/ConsentStatusInvariantTest` — a source-scanning build-failing test, modelled on
  `api/PrivacyNoticeNoBase64Test`: no file under `src/main/java` outside `domain/Consent.java` calls
  `setConsented(` or `setStatus(`. This is what makes "`consented` cannot be set independently of
  `status` anywhere in the codebase" enforceable rather than aspirational.

Acceptance: `./mvnw verify` green; the matrix test fails if a transition is added to the table
without being added to the test's expectation.

### Step 2: Consent lookups and the grant/refuse service

The whole grant path behind the API, driven by service-layer records.

- `repository/ConsentRepository.java` — add `Optional<Consent>
  findByUserIdAndPrivacyNoticeIdAndStatus(UUID, UUID, ConsentStatus)` and
  `List<Consent> findByUserIdAndPrivacyNoticeIdAndStatusIn(UUID, UUID, Collection<ConsentStatus>)`.
  `idx_consents_user_id_status` already serves both; no migration. The writer below reads the open
  consents for a pair through the second one with `OPEN_STATUSES = {GRANTED, PENDING}`.
- `service/ConsentDecision.java` — record `(UUID privacyNoticeId, ConsentStatus decision,
  List<String> purposes, List<String> dataCategories)`; the status is `GRANTED` or `REFUSED` and the
  state machine is what enforces that, not the record.
- `service/RecordedConsent.java` — record carrying the persisted `Consent` plus the provider and
  consumer `Participant` identifiers the response renders.
- `service/ConsentService.java` — `decide(User subject, ConsentDecision)`:
  1. load the notice by id from `PrivacyNoticeRepository` — **not** through
     `PrivacyNoticeService.get`, which would trigger self-description resolution this flow has no use
     for; absent → `NotFoundException` (404);
  2. `archivedAt != null` → `BadRequestException` (400) — consent cannot be granted against terms no
     longer in force;
  3. resolve provider and consumer participants from the notice. `privacy_notices.consumer_id` is
     nullable (V1 line 75) but `consents.consumer_id` is `NOT NULL` (V1 line 103) and
     `Consent.consumerId` is `@NonNull`, so a consumer-less notice cannot be written through — taken
     literally it would be a NOT NULL violation surfacing as a 500. **Decision: refuse such a notice
     with a 400** naming it, in `decide(...)` and in `request(...)` alike, rather than adding a
     migration that relaxes the column. Both generation paths in `PrivacyNoticeService` already
     require a consumer (`require(consumerIdentifier)`, `require(derived.dataConsumer())`), so no
     notice this service writes can reach that branch; the 400 covers rows that predate the
     invariant, and it is what keeps the "no Flyway migration is required" claim above true;
  4. scope check: an elected `purposes`/`data` list must be a non-empty subset of the notice's
     `purposes`/`categoriesOfData`, otherwise 400 naming the offending values; absent means the
     notice's full scope; an explicitly empty array is a 400, since electing nothing is not a
     narrowing;
  5. freeze `ConsentSnapshot.of(notice, electedPurposes, electedData)`;
  6. hand the result to the writer below.
- `service/ConsentWriter.java` — a separate `@Singleton` holding the one
  `jakarta.transaction.Transactional` method, called across the bean boundary because Micronaut AOP
  skips self-invocation. In one transaction it reads the **open** consents for (user, notice) —
  `OPEN_STATUSES = {GRANTED, PENDING}` — and then:
  1. an existing `GRANTED` consent is revoked: `requireTransition(GRANTED, REVOKED)`,
     `updateStatus(REVOKED)`, saved with its `CONSENT_REVOKED` event, and its id becomes the
     `parentConsentId` of the consent the decision lands on;
  2. an open `PENDING` consent for the same pair is **adopted, not left stranded**: the writer
     transitions that row in place through `requireTransition(PENDING, <decision>)`, replaces its
     snapshot with the one frozen now and appends its single `CONSENT_GIVEN`/`CONSENT_REFUSED`
     event. The row keeps its id, so a request a participant opened is *answered* by the user's
     grant instead of sitting `PENDING` forever — which would otherwise 409 every later request for
     that pair permanently (Step 4 rule 4) and leave the state machine's `PENDING` → `GRANTED` edge
     dead in this ticket. This is the only code path here that exercises that edge; `POST
     /consents/{id}/resume` (TICKET-009) later answers a `PENDING` from the other direction.
     The two branches compose: a pair carrying both (reachable only for rows predating this flow,
     since the partial unique index covers `GRANTED` alone) has the `GRANTED` revoked and the
     `PENDING` adopted and linked to the revoked id;
  3. with neither open, a new consent is saved with its single creation event.
  A `23505` unique violation on `uq_consents_granted_per_user_notice` (a concurrent second grant) is
  **not** caught inside the transactional method. As `PrivacyNoticeWriter`'s javadoc spells out, the
  `DataAccessException` is let out so the caller handles it *outside* the rolled-back transaction:
  `ConsentService.decide(...)` wraps the writer call, detects the unique violation with the pattern
  `PrivacyNoticeService` already uses (named constant, no literal SQL state anywhere else) and maps
  it to `ConflictException` (409) rather than a 500.

Tests:
- `service/ConsentServiceIT` (`PostgresTestResource`) — grant; refuse yields `status = REFUSED` and
  `consented = false`; unknown notice 404; archived notice 400; purposes outside scope 400; data
  outside scope 400; empty elected array 400; re-grant revokes the prior consent, links the new one
  via `parentConsentId` and leaves exactly one `GRANTED` row; the snapshot still reports the notice
  terms after the notice row is archived; **exactly one event per creation**, parameterized over the
  two decisions; and — parameterized over `GRANTED` and `REFUSED` — **deciding while a participant's
  `PENDING` request is open** adopts that row: same consent id, status equal to the decision, no
  second row for the pair, exactly one new event, and a later `POST /consents/requests` for the pair
  is admitted again rather than 409ing against a stranded `PENDING`. A consumer-less notice is
  refused with 400.
- `service/ConsentGrantAtomicityIT` — modelled on `service/ParticipantDeregistrationAtomicityIT`:
  two concurrent grants for the same (user, notice) leave exactly one `GRANTED` consent, the loser
  answering 409, and no state in which the user has zero or two.

### Step 3: `POST /consents` — specification, controller and mapper

- `api/components/schemas/GrantConsent.yaml` — `privacyNoticeId` (string, `format: uuid`, required),
  `decision` (inline `enum: [GRANTED, REFUSED]`, required), `purposes` (array of string, optional),
  `data` (array of string, optional). The body carries **no** user identifier, email or user key.
- `api/components/schemas/Consent.yaml` — `id`, `privacyNoticeId`, `parentConsentId` (uuid; absent
  when there is no parent), `status` (enum of the seven states), `consented` (boolean), `provider`
  and `consumer` (participant identifiers), `purposes` and `data` (arrays of string — the snapshot's
  elected `purposes` and `dataCategories`; the snapshot's `dataReferences` are not rendered here,
  the notice serves them), `contractUri`, `createdAt`, `updatedAt` (`date-time`). No
  `nullable: true` anywhere.
- `api/openapi.yaml` — add the `/consents` path item inline, copying the `registerParticipant`
  operation skeleton: `operationId: grantConsent`, `tags: [Consents]`, `security:
  - bearerAuth: [USER]`, `x-roles: [USER]`, `x-principal: UserPrincipal`, `requestBody` →
  `GrantConsent`, responses `201` → `Consent`, `400`/`401`/`403`/`404`/`409` → the shared responses,
  plus the `default` problem response. The `409` is not optional: Step 2 maps the concurrent-grant
  unique violation to `ConflictException`, and `SpecSecurityConsistencyTest` enforces only the
  401/403 refs, so omitting it would ship a spec that lies about a status the endpoint returns.
- `SpecSecurityConsistencyTest.SPECIFIED_OPERATION_COUNT` 19 → 20.
- `api/ConsentMapper.java` — `domain.Consent` + identifiers → the generated model; fully qualifies
  the colliding `Consent` type; maps the generated decision enum to `ConsentStatus`.
- `api/ConsentController.java` — `@Controller`-annotated concrete subclass of the generated
  `AbstractConsentsController` (name follows the `Consents` tag). It reads the subject from
  `UserPrincipal.user()` — the token, and nothing in the body — and returns `201`.
- `EndpointRoleMatrixIT` — add the `GRANT_CONSENT` row with the per-role expectations. The cell for
  the role a route serves carries that route's *own* status, and the fixture seeds users and
  participants but **no privacy notice** — so point the row at the existing `ABSENT_NOTICE` constant
  and expect `404` for the `USER` cell, exactly as `RETRIEVE_PRIVACY_NOTICE` does. The matrix is an
  authorization sweep; `ConsentGrantIT` owns the success path.

Tests:
- `api/ConsentGrantIT` (`@MicronautTest(transactional = false)` +
  `KeycloakAndPostgresTestResource`, real tokens from
  `KeycloakTestResource.accessToken(RealmPrincipal.USER)`) — 201 for `GRANTED` and for `REFUSED`
  (parameterized); the created consent belongs to the token's user even when the body also carries
  `userIdentifier`/`email`/`userKey` for a **different** user, and no consent exists for that other
  user afterwards. Determine empirically whether serde rejects the unknown properties outright: if it
  answers 400, assert that and record in the step's PR description that a rejected body cannot be
  honoured either; if it ignores them, assert the subject is the token's user. Either way the
  criterion "ignored, not honoured" is satisfied and the observed behaviour is documented.
- The same IT covers 404 unknown notice, 400 archived notice, 400 out-of-scope purposes/data over
  HTTP, and that a `PARTICIPANT` token is refused — there is no endpoint by which a participant can
  create a `GRANTED` consent.

### Step 4: Consent requests — the participant-initiated service rules

- `service/ConsentRequest.java` — record `(UUID privacyNoticeId, String userIdentifier)`.
- `service/ConsentService.java` — `request(Participant caller, ConsentRequest)`, with the check
  order fixed because the status codes depend on it:
  1. notice unknown → 404; archived → 400, for the same reason a grant against it is refused; a
     notice naming no consumer → 400, by the Step 2 decision, since the request would write the same
     `consents.consumer_id NOT NULL` column;
  2. caller is neither the notice's provider nor its consumer → `ForbiddenException` (403);
  3. user unknown **or** not linked to the caller
     (`UserParticipantRepository.existsByIdUserIdAndIdParticipantId`) → `NotFoundException` (404)
     with a message that does not disclose whether the identifier exists;
  4. a `GRANTED` or `PENDING` consent already exists for (user, notice) → `ConflictException` (409);
  5. otherwise create via `ConsentFactory` with status `PENDING` (`consented = false`), the snapshot
     frozen at the notice's full scope, and append the single `CONSENT_REQUESTED` event with
     `actor` set to the requesting participant's identifier.
  The caller is obtained with `ParticipantPrincipal.requireActive()`, not `requireRegistered()`: a
  request acts in the dataspace in the participant's name, so a deregistered participant must not be
  able to open new business.

Tests:
- `service/ConsentRequestIT` — the five rules above, each asserting the status and that no consent
  or event row was written on the refusal paths; the success case asserts `status = PENDING`,
  `consented = false`, exactly one event, and `actor` equal to the caller's identifier.

### Step 5: `POST /consents/requests` — specification, controller and role matrix

- `api/components/schemas/ConsentRequest.yaml` — `privacyNoticeId` (uuid, required),
  `userIdentifier` (string, required, the global identifier).
- `api/openapi.yaml` — `/consents/requests` path item inline: `operationId: requestConsent`,
  `tags: [Consent Requests]`, `security: - bearerAuth: [PARTICIPANT]`, `x-roles: [PARTICIPANT]`,
  `x-principal: ParticipantPrincipal`, `201` → the same `Consent` schema, `400`/`401`/`403`/`404`/
  `409` → shared responses, plus `default`.
- `SpecSecurityConsistencyTest.SPECIFIED_OPERATION_COUNT` 20 → 21.
- `api/ConsentRequestController.java` — `@Controller` subclass of the generated
  `AbstractConsentRequestsController`, delegating to `ConsentService.request(...)`.
- `EndpointRoleMatrixIT` — add the `REQUEST_CONSENT` row, again against `ABSENT_NOTICE` so the
  `PARTICIPANT` cell is a deterministic `404` without seeding a notice; `ConsentRequestSurfaceIT`
  owns the 201. `participantScopedEndpoints` picks the row up automatically and will fail unless the
  route refuses an unregistered participant — which `requireActive()` does before the notice is
  looked up, so the sweep still sees a refusal rather than that `404`.

Tests:
- `api/ConsentRequestSurfaceIT` with real tokens — 201 with `consented = false` and
  `status = PENDING`; 403 for a participant that is not a party to the notice; 404 for a participant
  not linked to the user and for an unknown identifier, asserted to be indistinguishable; 409 on a
  duplicate against an existing `PENDING` and against an existing `GRANTED` (parameterized); the
  `CONSENT_REQUESTED` event records the caller as `actor`; a deregistered participant is refused;
  a `USER` token is refused.

### Step 6: Documentation, ADR and full verification

- `docs/adr/0009-only-the-data-subject-grants-consent.md` — records the decision the ticket turns on:
  a participant may ask and never answer, the `x-user-key` grant path is gone, `PENDING` confers
  nothing, and lineage is a single upward `parentConsentId` link with no reciprocal child column.
  Record two further decisions this plan fixes: a user's decision **adopts** an open `PENDING`
  request for the same (user, notice) rather than creating a second row beside it, so a request is
  always answered rather than stranded; and a privacy notice naming no consumer is refused with a
  400 rather than relaxing `consents.consumer_id`, which no notice this service generates can be.
- `docs/security.md` — extend the `@Secured` convention and spec-layout sections with the two
  operations and with why the request route demands `requireActive()` while the grant route is a
  pure `USER` route.
- `AGENTS.md` — update the structure listing with `ConsentService`, `ConsentWriter`,
  `ConsentStateMachine`, `ConsentFactory`, the new controllers and schemas; raise the
  `SPECIFIED_OPERATION_COUNT` note (this plan's own commit already corrected it to **19**; it is
  **21** after Step 5); record the
  `ConsentSnapshot` vocabulary alignment and that the state machine is the only legal way to change a
  consent's status.
- Note in the ADR where TICKET-009/010/011/013/020 attach, without adding code for them.
- Run `./mvnw spotless:apply` then a full `./mvnw verify`, and walk the ticket's acceptance-criteria
  checklist, naming for each the test that proves it.
