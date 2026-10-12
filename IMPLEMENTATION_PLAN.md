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
- **Generated-model name collisions — two of them.** The response schema `Consent.yaml` generates
  `api.generated.model.Consent`, which collides with `domain.Consent`; `ConsentMapper` fully
  qualifies the generated side, as `PrivacyNoticeMapper` already does for `PrivacyNotice`. The
  request schema `ConsentRequest.yaml` (Step 5) would collide the same way with a service record
  named `ConsentRequest`, in a class — `ConsentRequestController` — that has to name both. Rather
  than qualify twice, the service-layer record is named **`ConsentRequestCommand`** (Step 4), so the
  second collision never arises and `ConsentRequest` means exactly one thing in Java.
- **Response schemas declare no `nullable: true`** (it forces `JsonNullable`); optional means absent.
  A schema whose `items:` refs another named model must be declared inline in `openapi.yaml` with an
  internal `#/components/schemas/...` ref — not needed here if every array is an array of strings.
- **Micronaut AOP does not apply to self-invocation**, so the transactions that revoke and re-grant
  live on a separate bean, mirroring `PrivacyNoticeWriter`.
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
  **Every factory entry point takes the acting identity** and passes it to the `actor` argument
  `ConsentEvent`'s constructor already declares (`consent_events.actor` is nullable `TEXT`): the
  user's identifier on `CONSENT_GIVEN`/`CONSENT_REFUSED`/`CONSENT_REVOKED`, the requesting
  participant's on `CONSENT_REQUESTED`. Because the factory is the single place an event is built,
  there is no path that writes a null actor, and the audit log does not record who *asked* while
  leaving the decision that actually binds the user anonymous.
- `domain/ConsentSnapshot.java` — align the vocabulary with `PrivacyNoticePayload`, which its own
  javadoc defers to "the ticket that first writes a consent". The snapshot freezes the *effective*
  terms: `purposes` and `dataCategories` hold the elected subset (the notice's full lists when the
  decision elects none), and the non-scope terms — `dataReferences`, `recipients`,
  `retentionPeriod`, `controllerDetails`, `piiPrincipalRights`, `internationalTransfers`,
  `withdrawalOfConsent` — are copied verbatim from the payload, together with the notice's
  `title` so the record stands alone after the notice is archived. The notice's `contractUri` is
  deliberately **not** copied in: `consents.contract_uri` is already a column and
  `Consent.withStatus(...)` already takes it, so a second copy in the snapshot JSONB would be two
  sources of truth that can disagree. The column is the frozen one, and it is what Step 3 renders.
  "Verbatim" forces two shape changes worth naming here: `recipients` becomes
  `List<PrivacyNoticePayload.Recipient>` rather than `List<String>`, and
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

- `repository/ConsentRepository.java` — add exactly two methods, each with a named call site:
  - `@Query("SELECT * FROM consents WHERE user_id = :userId AND privacy_notice_id ="
    + " :privacyNoticeId ORDER BY created_at DESC FOR UPDATE")
    List<Consent> findByUserIdAndPrivacyNoticeIdForUpdate(UUID userId, UUID privacyNoticeId)` — the
    writer's single read, following the precedent `ParticipantRepository.findByIdForUpdate`
    (`SELECT * FROM participants WHERE id = :id FOR UPDATE`) already sets. It locks **every** row
    for the pair, not just the open ones, because the writer needs both: the open consents to
    supersede and the most recent row of any status to link as parent. Newest first, so element 0
    is the parent candidate and the open rows are a filter over the same result.
  - `boolean existsByUserIdAndPrivacyNoticeIdAndStatusIn(UUID, UUID, Collection<ConsentStatus>)` —
    Step 4 rule 4's duplicate check, and nothing else reads it.
  `idx_consents_user_id_status` serves both; no migration. `OPEN_STATUSES = {GRANTED, PENDING}` is a
  constant on the service side, passed to the second method and used to filter the first's result.
- `service/ConsentDecision.java` — record `(UUID privacyNoticeId, ConsentStatus decision,
  List<String> purposes, List<String> dataCategories)`; the status is `GRANTED` or `REFUSED` and the
  state machine is what enforces that, not the record.
- `service/ConsentDetails.java` — record carrying the persisted `Consent` plus the provider and
  consumer `Participant` identifiers the response renders. **Not** `RecordedConsent`: Step 1 already
  owns that name for the write-side pair (`Consent` + its `ConsentEvent`, which are only ever saved
  together), so the read-side projection takes a name of its own rather than widening a record whose
  two fields are a write unit. `ConsentWriter` returns a `RecordedConsent`; `ConsentService` resolves
  the participants around it and returns a `ConsentDetails`.
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
  4. scope check against the notice's `purposes` and `categoriesOfData`. **Every component of
     `PrivacyNoticePayload` is `@Nullable`** — a contract need not carry a source for every heading
     — so a notice declaring no purposes or no categories is a branch, not an oversight, and a bare
     `noticePurposes.containsAll(elected)` would NPE into a 500. The four cases, in this order:
     - an election against a notice list that is null or empty → 400 ("nothing to narrow"),
       evaluated *before* the subset test so it never dereferences null;
     - an explicitly empty elected array → 400: electing nothing is not a narrowing. This diverges
       from the ticket's literal "verify each is a subset" — an empty list *is* a subset — so it is
       recorded in the Step 6 ADR alongside the other decisions rather than only here;
     - an elected list that is not a subset of the notice's → 400 naming the offending values;
     - an absent election takes the notice's full scope, and where that notice list is itself null
       the frozen scope is recorded as **absent** (null), not as an empty list: a consent against a
       notice that named no purposes says so, instead of claiming an empty set was elected. Both
       snapshot components are `@Nullable` already, so this needs no shape change;
  5. freeze `ConsentSnapshot.of(notice, electedPurposes, electedData)`;
  6. hand the result to the writer below.
- `service/ConsentWriter.java` — a separate `@Singleton` holding this flow's
  `jakarta.transaction.Transactional` methods — `decide(...)` here and `request(...)` in Step 4 —
  called across the bean boundary because Micronaut AOP skips self-invocation. Its **first** statement is the locking read
  `findByUserIdAndPrivacyNoticeIdForUpdate(userId, noticeId)` — every row for the pair, newest
  first, locked for the transaction. The lock is not decoration: the create path is guarded by
  `uq_consents_granted_per_user_notice`, but branch 2 below *updates a row in place* and no unique
  index covers that, so without it two concurrent decisions against one open `PENDING` would both
  pass `requireTransition(PENDING, GRANTED)` on their own stale read and both commit, leaving one
  consent carrying two `CONSENT_GIVEN` events. With it the second transaction blocks and, under
  `READ COMMITTED`, re-evaluates the row as the first left it, so it takes branch 1 or 3 against the
  real state. (Two *first* decisions for a pair lock nothing — there is no row yet — which is
  precisely the case the unique index and the 409 below cover.) `ParticipantRepository`'s
  self-service write already uses this shape. Over that locked list, filtered by
  `OPEN_STATUSES = {GRANTED, PENDING}`, the writer then:
  1. an existing `GRANTED` consent is revoked: `requireTransition(GRANTED, REVOKED)`,
     `updateStatus(REVOKED)`, saved with its `CONSENT_REVOKED` event;
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
  **Lineage is the general rule, not the revoke branch's private business.** The ticket states that
  `REVOKED`, `REFUSED`, `TERMINATED` and `EXPIRED` are terminal and "a new decision creates a new
  consent with a parent link". So: the `parentConsentId` of a newly created consent is **the most
  recent consent for (user, notice) whatever its status** — element 0 of the locked list, which is
  why the read locks every row rather than only the open ones. Revoking an open `GRANTED` is one
  case of that rule (the row it just revoked is also the most recent), not the only one: a user who
  `REFUSED` and later grants, or whose consent reached `EXPIRED` or `TERMINATED`, gets a chain
  TICKET-010 can walk back instead of a fresh root that loses the fact that the grant superseded a
  refusal. An *adopted* `PENDING` row (branch 2) is not a new consent: it keeps the parent it was
  requested with, except where branch 1 fired in the same transaction, in which case it is
  re-pointed at the row just revoked — the chain still records what the decision superseded.
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
  refused with 400. **Lineage, parameterized over the terminal statuses** `REFUSED`, `REVOKED`,
  `EXPIRED` and `TERMINATED`: a consent left in that status and a later grant produce a new consent
  whose `parentConsentId` is that row, not `null` — grant-after-refuse is the case the plan
  previously got wrong. A notice declaring null `purposes`/`categoriesOfData` is covered twice: an
  election against it is 400, an absent election freezes an absent scope.
- `service/ConsentGrantAtomicityIT` — modelled on `service/ParticipantDeregistrationAtomicityIT`,
  with a case per concurrency hazard:
  - two concurrent **first** grants for the same (user, notice) leave exactly one `GRANTED`
    consent, the loser answering 409, and no state in which the user has zero or two;
  - two concurrent **decisions against one open `PENDING`** — the hazard branch 2 introduces, which
    the first case cannot reach — leave no row still `PENDING`, exactly one `GRANTED` consent for
    the pair, and **no consent carrying two events of the same state**: the lock forces the second
    decision into the revoke-and-create branch rather than a second adoption of the same row.

### Step 3: `POST /consents` — specification, controller and mapper

- `api/components/schemas/GrantConsent.yaml` — `privacyNoticeId` (string, `format: uuid`, required),
  `decision` (inline `enum: [GRANTED, REFUSED]`, required), `purposes` (array of string, optional),
  `data` (array of string, optional). The body carries **no** user identifier, email or user key.
- `api/components/schemas/Consent.yaml` — `id`, `privacyNoticeId`, `parentConsentId` (uuid; absent
  when there is no parent), `status` (enum of the seven states), `consented` (boolean), `provider`
  and `consumer` (participant identifiers), `purposes` and `data` (arrays of string — the snapshot's
  elected `purposes` and `dataCategories`; the snapshot's `dataReferences` are not rendered here,
  the notice serves them), `contractUri` — read from the `consents.contract_uri` **column**, never
  from the snapshot, per the single-source decision in Step 1 — `createdAt`, `updatedAt`
  (`date-time`). No `nullable: true` anywhere.
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
  and expect `404` for the `USER` cell, exactly as `RETRIEVE_PRIVACY_NOTICE` does. For that `404` to
  be the status reached, the body must first pass bean validation — `privacyNoticeId` and `decision`
  are both required — so spell it out the way every body-carrying row already does (see
  `SEARCH_USERS`, `GENERATE_PRIVACY_NOTICE`):
  `HttpRequest.POST(PATH_CONSENTS, Map.of("privacyNoticeId", ABSENT_NOTICE, "decision", "GRANTED"))`.
  A row with an incomplete body answers `400` and the sweep stops meaning what it claims. The matrix
  is an authorization sweep; `ConsentGrantIT` owns the success path.

Tests:
- `api/ConsentGrantIT` (`@MicronautTest(transactional = false)` +
  `KeycloakAndPostgresTestResource`, real tokens from
  `KeycloakTestResource.accessToken(RealmPrincipal.USER)`) — 201 for `GRANTED` and for `REFUSED`
  (parameterized); the created consent belongs to the token's user even when the body also carries
  `userIdentifier`/`email`/`userKey` for a **different** user, and no consent exists for that other
  user afterwards. **The expected behaviour is settled, not empirical:** Micronaut Serde ignores
  unknown properties by default, and neither `application.yml` nor `application-test.yml` sets any
  `deserialization` / `fail-on-unknown-properties` key — the only `micronaut.serde` block in the
  repo is `serialization.inclusion: non-absent`. Three ITs already assert exactly this shape against
  generated models (`ParticipantRegistrationIT.anIdentifierInTheBodyIsIgnored`,
  `ParticipantSelfServiceIT`, `ParticipantUserRegistrationIT`), so model this test on them: expect
  `201` and assert the subject is the token's user. If it ever answers `400`, that is a
  configuration regression to fix rather than a behaviour to assert.
- The same IT covers 404 unknown notice, 400 archived notice, 400 out-of-scope purposes/data over
  HTTP, and that a `PARTICIPANT` token is refused — there is no endpoint by which a participant can
  create a `GRANTED` consent.

### Step 4: Consent requests — the participant-initiated service rules

- `service/ConsentRequestCommand.java` — record `(UUID privacyNoticeId, String userIdentifier)`.
  Named `...Command` rather than `ConsentRequest` because Step 5's `ConsentRequest.yaml` generates
  `api.generated.model.ConsentRequest`, and `ConsentRequestController` names both.
- `service/ConsentService.java` — `request(Participant caller, ConsentRequestCommand)`, with the
  check order fixed because the status codes depend on it:
  1. notice unknown → 404; archived → 400, for the same reason a grant against it is refused; a
     notice naming no consumer → 400, by the Step 2 decision, since the request would write the same
     `consents.consumer_id NOT NULL` column;
  2. caller is neither the notice's provider nor its consumer → `ForbiddenException` (403);
  3. user unknown **or** not linked to the caller
     (`UserParticipantRepository.existsByIdUserIdAndIdParticipantId`) → `NotFoundException` (404)
     with a message that does not disclose whether the identifier exists;
  4. a `GRANTED` or `PENDING` consent already exists for (user, notice) → `ConflictException` (409),
     through `existsByUserIdAndPrivacyNoticeIdAndStatusIn(userId, noticeId, OPEN_STATUSES)`;
  5. otherwise create via `ConsentFactory` with status `PENDING` (`consented = false`), the snapshot
     frozen at the notice's full scope (absent where the notice's own list is null, per Step 2's
     check 4), the `parentConsentId` taken by the same most-recent-row rule the grant path uses, and
     the single `CONSENT_REQUESTED` event with `actor` set to the requesting participant's
     identifier — the factory argument, not a special case here.
  Rules 4 and 5 run in a second `@Transactional` method on `ConsentWriter`, behind the same locking
  read the grant path uses, so two concurrent requests for one pair cannot both pass rule 4 and
  land two `PENDING` rows that no index would catch, and the parent rule is applied under that
  lock.
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
  `AbstractConsentRequestsController`, mapping the generated `ConsentRequest` body to the service's
  `ConsentRequestCommand` and delegating to `ConsentService.request(...)`. Because the service
  record carries the `...Command` suffix, neither type has to be fully qualified here.
- `EndpointRoleMatrixIT` — add the `REQUEST_CONSENT` row, again against `ABSENT_NOTICE` so the
  `PARTICIPANT` cell is a deterministic `404` without seeding a notice, and again with the body
  spelled out so validation is not what answers:
  `HttpRequest.POST(PATH_CONSENT_REQUESTS, Map.of("privacyNoticeId", ABSENT_NOTICE,
  "userIdentifier", LINKED_USER))`; `ConsentRequestSurfaceIT` owns the 201.
  `participantScopedEndpoints` picks the row up automatically and will fail unless the
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
  Record the further decisions this plan fixes, each a point where the implementation is not simply
  readable off the ticket:
  - a user's decision **adopts** an open `PENDING` request for the same (user, notice) rather than
    creating a second row beside it, so a request is always answered rather than stranded, and the
    locking read is what keeps two concurrent decisions from adopting the same row twice;
  - the `parentConsentId` of a new consent is the most recent consent for the pair **whatever its
    status**, so a grant after a refusal, an expiry or a termination extends the chain rather than
    starting a new one; revoking an open `GRANTED` is one case of that rule;
  - a privacy notice naming no consumer is refused with a 400 rather than relaxing
    `consents.consumer_id`, which no notice this service generates can be;
  - an explicitly empty elected `purposes`/`data` array is a 400, which **diverges from the
    ticket's literal "verify each is a subset"** — an empty list is a subset — because electing
    nothing is not a narrowing; an election against a notice that declares no purposes or
    categories is a 400 for the same reason, and an absent election against such a notice freezes
    an absent scope rather than an empty one;
  - `contractUri` is frozen once, in the `consents.contract_uri` column, and the snapshot does not
    duplicate it; every consent event carries an `actor`, so the decision is as attributable as the
    request.
  - **`DRAFT` is not a creatable status here**, although the ticket's own state table lists
    `- -> DRAFT` ("consent prepared, awaiting user action") beside `GRANTED`/`REFUSED`/`PENDING`.
    Nothing in this ticket prepares a draft, so admitting the creation would ship a state no
    endpoint can produce and no endpoint can answer; `DRAFT` keeps its *outgoing* edges and
    TICKET-009 adds it to `CREATABLE` and to the factory's event table together. When Step 6 walks
    the acceptance criteria, "the state machine rejects every invalid transition" is therefore read
    as covering transitions, not as covering that deferred creation.
  - **`PENDING`/`DRAFT` -> `TERMINATED` are edges in the table**, because erasure
    (`UserService`) and deregistration (`ParticipantService`) already close the consents nobody
    answered. The ticket's table is silent on the move rather than forbidding it, and a table that
    declared invalid what the service performs would be worse than no table.
  - **Every status change goes through `ConsentFactory`**, which is the only caller of
    `Consent.withStatus`/`updateStatus`; `ConsentStatusInvariantTest` scans `src/main/java` and
    fails the build on any other caller, so "no transition bypasses the guard" is enforced rather
    than documented. Erasure and deregistration were rewritten onto the factory for that reason.
  - **`ConsentEventTrigger` is the single decider of `consent_events.event_type`** (`EXPLICIT` ->
    `"explicit"`, `SYSTEM` -> `"system"`), so a job's expiry or a counterparty's departure is not
    filed as a deliberate act by the subject, and the string exists in one place.
- `docs/security.md` — extend the `@Secured` convention and spec-layout sections with the two
  operations and with why the request route demands `requireActive()` while the grant route is a
  pure `USER` route.
- `AGENTS.md` — update the structure listing with `ConsentService`, `ConsentWriter`,
  `ConsentStateMachine`, `ConsentFactory`, `ConsentRequestCommand`, the new controllers and
  schemas; raise the
  `SPECIFIED_OPERATION_COUNT` note (this plan's own commit already corrected it to **19**; it is
  **21** after Step 5); record the
  `ConsentSnapshot` vocabulary alignment and that the state machine is the only legal way to change a
  consent's status.
- Note in the ADR where TICKET-009/010/011/013/020 attach, without adding code for them.
- Run `./mvnw spotless:apply` then a full `./mvnw verify`, and walk the ticket's acceptance-criteria
  checklist, naming for each the test that proves it.
