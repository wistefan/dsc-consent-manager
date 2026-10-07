# Implementation Plan: Consent Manager - Reimplementation - Participant Management

Taiga ticket #69 (TICKET-005). Depends on TICKET-002 (schema) and TICKET-003 (token
validation), both already merged into `main`.

## Overview

Add self-service participant management to the Consent Manager: a `PARTICIPANT` token
registers, reads, updates and deregisters *its own* record, with the participant
identifier always taken from the configured token claim and never from the request body,
path or query. No credentials are stored, there is no login endpoint and no Data Space
Connector integration. Work is spec-first — `api/openapi.yaml` and
`api/components/schemas/` are authored by hand, controllers implement the generated
abstract classes, and a transactional `ParticipantService` holds the behaviour.

### Decisions carried in from the analysis comment

1. **Deregistration closes the participant's open business and keeps its identity legible.**
   `DELETE /participants/me` rejects with `409` while any consent naming the participant as
   provider or consumer is `GRANTED`; it terminates the participant's still-unanswered
   (`PENDING`/`DRAFT`) consents, removes all `user_participants` links and archives active
   privacy notices, and then either *deletes* the `participants` row when nothing still
   references it or *retains it intact, marked deregistered*, when a retained consent or
   privacy notice still points at it. One transaction. ADR-0008 records this.

   The row is **not** pseudonymised. ADR-0007 scrubs a *user*'s identifier because it is
   personal data and a data subject has an erasure right; a participant is an organization,
   which has neither — and there is no keyed `erasure_verifier` counterpart here, so a
   scrubbed participant could never be recovered by anyone, leaving every retained consent
   naming a counterparty nobody can identify. Since `privacy_notices` carries
   `ON DELETE RESTRICT` on both participant foreign keys, a participant that ever had a
   notice always lands on the retain branch, so that branch is the normal one and it is the
   one that has to stay legible. The single plausibly-personal field, `email` (a contact
   person's address rather than the organization's), is cleared; the organization's own
   identity fields are kept. Raised by review-agent-1 on PR #1, which was right that
   "mirror ADR-0007" is not an argument for an organization.
2. **Self-registration is made reachable by the minimal change.**
   `PrincipalResolutionFilter` stops turning an unregistered `PARTICIPANT` identifier into a
   `403` and instead resolves a `ParticipantPrincipal` carrying the identifier with its row
   absent. `POST /participants` is the one route that accepts that shape; every other
   participant-scoped route refuses when the row is absent. No new principal subtype, no
   route exemption from principal resolution, no new role, and
   `SpecSecurityConsistencyTest` keeps its rule that a secured operation declares a
   `ConsentManagerPrincipal` parameter.
3. **US-PM-008 / AC 12 (`GET /.well-known/jwks.json`) is out of scope** and deferred
   entirely to TICKET-011 — the route as well as the key material, not just the keys. Raised
   on PR #1 because it closes this ticket with a listed AC unmet, and confirmed there by the
   ticket owner (@wistefan, "Yes, agreed."). AC 12 is therefore struck from ticket #69 rather
   than left outstanding, and Step 8 records it as struck-by-agreement.

### Scope notes

- AC 9 constrains *modification* only. `GET /participants/{identifier}` is deliberately
  readable by `USER`, `PARTICIPANT` and `CATALOG`; every mutation lives under
  `/participants/me` and is resolved from the token, so there is no identifier-accepting
  write route to guard.
- AC 2 is satisfied structurally: `identifier` is simply absent from
  `ParticipantRegistration` and `ParticipantUpdate`, so an identifier in the body is
  ignored rather than rejected.
- US-PM-002 is documentation only; the validation behaviour stays owned by TICKET-003.
- One small Flyway migration is needed, and it lands in **Step 2**, not Step 5.
  `V1__initial_schema.sql` already carries `participants` with `endpoints`/`legal_person`
  JSONB and `uq_participants_identifier`; the migration adds a nullable
  `deregistered_at TIMESTAMPTZ` so a retained row can be marked without being scrubbed. It
  is scheduled in Step 2 because Steps 2 and 4 already depend on the column — Step 2's
  `Participant.yaml` exposes `deregisteredAt`, so `ParticipantMapper` must read a domain
  field that must already exist, and Step 4's listing filters deregistered rows out — and
  every step has to merge independently with `./mvnw verify` green. The migration is one
  nullable column plus a partial index and depends on nothing Step 5 adds, so moving it
  forward costs nothing; Step 5 keeps only the cascade behaviour that writes the column. No
  verifier column is needed — a participant is an organization, not a data subject, so
  ADR-0007's keyed `erasure_verifier` has no counterpart here.

### Constraints every step inherits

- Spec and implementation land in the **same** step. `SpecSecurityConsistencyTest` fails the
  build when a specified operation has no implementing route, so a spec-only step cannot be
  merged.
- **Every step that adds an operation raises `SPECIFIED_OPERATION_COUNT` in the same commit.**
  `SpecSecurityConsistencyTest` pins the constant at `10` today and asserts the parsed
  operation list `hasSize(SPECIFIED_OPERATION_COUNT)`, precisely so that a path item its
  parser cannot see costs a build rather than silently generating no cases. Each step from 2
  onward therefore fails `./mvnw verify` until the constant matches: Step 2 `+1` → `11`,
  Step 3 `+2` → `13`, Step 4 `+2` → `15`, Step 5 `+1` → `16`. The expected end state after
  Step 5 is **16**; a step that silently loses an operation still costs a build.
- Every new operation declares `security`, `x-roles` and `x-principal` consistently, and
  `$ref`s the shared `401`/`403` responses plus the inline `default` problem response.
- Operations are tagged `Participants`, which makes the generator emit
  `AbstractParticipantsController` — distinct from the existing `Participant Users` tag's
  `AbstractParticipantUsersController`.
- A schema whose `items:` refers to another named model must be written **inline** in
  `openapi.yaml`; a cross-file `$ref` under `items` degrades to `List<Object>`.
- `./mvnw spotless:apply` before every commit; `./mvnw verify` must pass.
- Doc comments stay terse — one line on what a type or public method is and why, a second
  only for a contract the signature cannot show. No named constant may be a magic value.

## Steps

### Step 1: Let an unregistered participant token through to registration

Today `PrincipalResolutionFilter` resolves a `PARTICIPANT` token by looking the identifier
claim up in `participants` and throwing `AuthorizationException` ("its participant
identifier is not registered") when the row is missing. That makes self-registration
unreachable: the caller is rejected before `POST /participants` can run.

**Changes**

- `src/main/java/com/seamware/consentmanager/security/ParticipantPrincipal.java` — make the
  `participant` component `@Nullable`. Add two accessors: `registered()` (the row is
  present) and `requireRegistered()`, which returns the `Participant` or throws
  `ForbiddenException` with a detail naming registration as the remedy. The record has no
  compact constructor today, so **add** one that rejects a null `issuer`, `subject` or
  `identifier`; the identifier is then always available even when the row is not. Rewrite the
  class javadoc too: it currently asserts the opposite of what this step makes true ("an
  identifier that is not registered never becomes a principal at all and the request is
  refused with `403`", and `@param participant ... never null`). It must instead say that an
  unregistered identifier *does* become a principal, that `POST /participants` is the only
  route that accepts one, and that every other participant-scoped caller goes through
  `requireRegistered()`.
- `src/main/java/com/seamware/consentmanager/security/PrincipalResolutionFilter.java` — the
  PARTICIPANT branch keeps the "no participant identifier claim" `403` and replaces the
  "not registered" `403` with `new ParticipantPrincipal(issuer, subject, identifier, null)`.
  Resolution still runs on `TaskExecutors.BLOCKING`. Rewrite the `participant(...)` javadoc
  in the same edit — it is the second place asserting the contract this step makes false
  ("Builds a `ParticipantPrincipal` from the **registered** participant the token's
  identifier resolves to", and `@throws AuthorizationException 403 when the identifier is
  absent or unregistered`). After this step only the *absent* identifier throws, and the two
  javadocs (here and on `ParticipantPrincipal`) must say the same thing.
- `src/main/java/com/seamware/consentmanager/api/ParticipantUserController.java` — every
  existing call site that reads `principal.participant()` switches to
  `principal.requireRegistered()`, preserving today's `403` for an unregistered caller.
  Today that is lines 72, 95, 183, 184 and 192.
- `src/main/java/com/seamware/consentmanager/service/CallerScope.java` — **the other
  `participant()` reader, and the one that fails open.** `CallerScope.of()` currently does
  `case ParticipantPrincipal caller -> new CallerScope(Role.PARTICIPANT, caller.participant())`,
  and in `CallerScope` a `null` participant is not "unknown", it is the encoding for *reads
  dataspace-wide*. Once the component is nullable, an unregistered participant would be
  **widened** rather than refused: `UserService.requiredParticipants()` (line 275) guards with
  `if (scope.participant() != null)` and so adds no participant constraint, and
  `UserService.participantIdentifiersFor(user, scope)` (line 367) falls through to the user's
  full affiliation list — exactly the disclosure `api/openapi.yaml` promises never happens ("A
  participant does not learn which other participants the user is affiliated with"). The
  reachable routes are `POST /users/search` and `GET /users/{identifier}`
  (`x-roles: [PARTICIPANT, CATALOG]`, `x-principal: ConsentManagerPrincipal`), which call
  `CallerScope.of(principal)` from `UserController` lines 88 and 99; today the filter's `403`
  makes the null case unreachable, and Step 1 removes that guarantee. So `CallerScope.of()`
  must call `caller.requireRegistered()`, keeping the `403` at the scoping boundary. Update the
  record's javadoc to say that `null` means `CATALOG` reads dataspace-wide and never a
  participant.
- After the two files above, grep `\.participant()` across **both** `src/main/java` and
  `src/test/java`, and confirm every remaining hit either sits in `CallerScope`/`UserService`'s
  dataspace-wide branch or is a call site that only ever sees a registered principal. Two tests
  dereference the component directly and would NPE rather than fail cleanly once it is nullable
  — `PrincipalResolutionIT.java:746` and `KeycloakRoleMatrixIT.java:343`, both
  `participant.participant().getLegalName()` — and this step adds a case that produces an
  unregistered principal, so guard or narrow them explicitly rather than assuming they are
  unreachable.

**Tests**

- `src/test/java/com/seamware/consentmanager/security/PrincipalResolutionIT.java` — add a
  case asserting that a `PARTICIPANT` token whose identifier is not in `participants`
  resolves a principal with the identifier set and the row absent, rather than `403`.
- `src/test/java/com/seamware/consentmanager/api/EndpointRoleMatrixIT.java` — assert the
  existing `/participants/me/users`, `/participants/me/users/{identifier}` and
  `/participants/me/users/bulk` routes still answer `403` for an unregistered participant
  token (those are the paths the spec actually declares; there is no
  `/participants/{identifier}/users...`, and a test written against that path would assert
  `403` against a `404`), **and** that `POST /users/search` and
  `GET /users/{identifier}` do too. The latter two are the routes Step 1 actually puts at
  risk: they take the widest principal type and reach `CallerScope.of()`, so without a case
  pinning them the scope widening above would land green. Assert the status, not just the
  absence of rows, so a future change that returns an empty page instead of refusing still
  fails.

**Acceptance criteria:** unregistered `PARTICIPANT` tokens reach their route; every
pre-existing participant-scoped route still refuses them with `403`, and none of them widens
to a dataspace-wide scope; `./mvnw verify` green.

### Step 2: Participant representation schemas, mapper and `POST /participants`

Covers US-PM-001, AC 1, AC 2, AC 3.

**Schema** (moved forward from Step 5, which depended on it)

- `src/main/resources/db/migration/V3__participant_deregistration.sql` — add
  `deregistered_at TIMESTAMPTZ` (nullable) to `participants`, plus a partial index on
  `deregistered_at IS NULL` for the directory listing's default filter.
  `domain/Participant` gains the field. It lands here rather than in Step 5 because this
  step's `Participant.yaml` already exposes `deregisteredAt` and Step 4 already filters on
  it, and every step must merge independently with `./mvnw verify` green. Step 5 adds only
  the cascade that *writes* the column.
- `ParticipantMapper` therefore maps `deregisteredAt` from day one; it is simply `null`
  until Step 5 can set it.

**Spec** (`api/components/schemas/`, referenced from `openapi.yaml`'s `components`)

- `Participant.yaml` — `identifier` (required), `legalName` (required),
  `selfDescriptionUri`, `email`, `legalPerson`, `endpoints`, `createdAt`, `updatedAt` and
  `deregisteredAt` (all three `format: date-time`; `deregisteredAt` is simply *absent* for an
  active participant, which is what Step 5's retain branch marks). No property is `nullable`,
  matching `User.yaml`'s rationale: a nullable response property forces `JsonNullable` on the
  model.
- `ParticipantLegalPerson.yaml` — `registrationNumber`, `headquartersAddress`,
  `legalAddress`, `parentOrganization`, `subOrganization`, all optional strings with
  `maxLength`.
- `ParticipantEndpoints.yaml` — `consentNotification`, optional, `maxLength` plus the
  absolute-`https` `pattern`.
- `ParticipantRegistration.yaml` — `legalName` (`minLength: 1`, `maxLength`, non-blank
  `pattern` as in `UserRegistration.yaml`), `selfDescriptionUri` (`https` pattern +
  `maxLength`), `email` (`format: email`, `maxLength: 254`), `legalPerson`, `endpoints`.
  **No `identifier` property** — that is what makes AC 2 structural.
- `openapi.yaml` — `POST /participants`, `operationId: registerParticipant`, tag
  `Participants`, `security: [bearerAuth: [PARTICIPANT]]`, `x-roles: [PARTICIPANT]`,
  `x-principal: ParticipantPrincipal`; responses `201` (`Participant`), `400`, `401`,
  `403`, `409`, `default`. The description states plainly that the identifier comes from
  the token and that a body-supplied identifier is ignored. Raise
  `SpecSecurityConsistencyTest.SPECIFIED_OPERATION_COUNT` from `10` to `11` in this commit.

**Implementation**

- `src/main/java/com/seamware/consentmanager/api/ParticipantMapper.java` — `@Singleton`,
  domain → generated model only, `Instant` → `OffsetDateTime` at UTC, mirroring
  `UserMapper`. Maps the `endpoints`/`legalPerson` JSONB maps onto the typed generated
  models and back.
- `src/main/java/com/seamware/consentmanager/service/ParticipantService.java` — `@Singleton`.
  `register(String identifier, ParticipantRegistration body)`: throw `ConflictException`
  when `participants.existsByIdentifier(identifier)`, otherwise persist. Catch the
  `uq_participants_identifier` unique-violation on the race and translate it to the same
  `ConflictException`, following `UserService`'s reason for not wrapping create paths in a
  transaction.
- `src/main/java/com/seamware/consentmanager/api/ParticipantController.java` — `@Controller`
  extending `AbstractParticipantsController`; `registerParticipant` takes the
  `ParticipantPrincipal` first and unannotated, returns `HttpResponse.created(body)`.

**Tests**

- `api/ParticipantMapperTest.java` — unit, round-trips the JSONB-backed nested objects.
- `api/ParticipantRegistrationIT.java` — registration from a token creates the record with
  the token's identifier; an `identifier` in the body is ignored; a second registration by an
  already-registered participant returns `409`; `deregisteredAt` is absent from the response
  for an active participant. Step 5 adds the reactivation case (a deregistered identifier
  re-registers instead of conflicting) once its cascade can actually set the column.

**Acceptance criteria:** AC 1, AC 2, AC 3; no response field is a credential (AC 14 for this
surface); `./mvnw verify` green.

### Step 3: `GET /participants/me` and `PUT /participants/me`

Covers US-PM-005, US-PM-006, AC 6, AC 7, AC 8.

**Spec**

- `api/components/schemas/ParticipantUpdate.yaml` — the same mutable fields as
  `ParticipantRegistration` with the same validation, and again **no `identifier`**.
- `openapi.yaml` — `/participants/me` `get` (`getCurrentParticipant`) and `put`
  (`updateCurrentParticipant`), both `PARTICIPANT` / `ParticipantPrincipal`, both returning
  `Participant`; `put` also declares `400`. Both declare `403` as the answer for a token
  whose participant is not registered, and the descriptions say so. Raise
  `SPECIFIED_OPERATION_COUNT` from `11` to `13` in this commit.

**Implementation**

- `ParticipantService.update(Participant current, ParticipantUpdate body)` — `@Transactional`.
  Writes the mutable columns, leaves `identifier` untouched, and **re-reads the row** before
  returning it, so the response is the persisted state and not the in-memory object
  (original bug 05-PM-12.2, "update `save()` not awaited").
- `ParticipantController` — both handlers resolve the row via
  `principal.requireRegistered()`.

**Tests**

- `service/ParticipantServiceIT.java` — update writes all mutable fields; `identifier` is
  unchanged; the returned entity equals a fresh read, including `updatedAt`.
- `api/ParticipantSelfServiceIT.java` — `GET /participants/me` returns the caller's record;
  an unregistered participant token gets `403` on both routes; `PUT` ignores an `identifier`
  in the body.

**Acceptance criteria:** AC 6, AC 7, AC 8.

### Step 4: `GET /participants` (paginated) and `GET /participants/{identifier}`

Covers US-PM-003, US-PM-004, AC 4, AC 5.

The repo has no pagination yet — `POST /users/search` is deliberately unpaginated — so this
step introduces the first paged endpoint and the convention for later ones.

**Spec**

- **Inline `ParticipantPage` schema in `openapi.yaml`**, alongside `BulkUserRegistration` and
  with a comment naming the reason — `content` (array of `Participant`), `page`, `size`,
  `totalElements`, `totalPages`. Do **not** create
  `api/components/schemas/ParticipantPage.yaml`: a cross-file `$ref` under `items:` degrades
  to `List<Object>`, so the externalised file would be dead weight and the `$ref` to it
  would silently lose the element type. Inline is the only form that works.
- `openapi.yaml` — `GET /participants` (`listParticipants`) with `page` (integer, min 0,
  default 0), `size` (integer, min 1, default matching the configured default) and
  `identifier` (string, optional, exact and case-sensitive) query parameters;
  `GET /participants/{identifier}` (`getParticipantByIdentifier`) with the global identifier
  as a path parameter and a `404` response. Both are `bearerAuth: [USER, PARTICIPANT, CATALOG]`,
  `x-roles: [USER, PARTICIPANT, CATALOG]`, `x-principal: ConsentManagerPrincipal` — the
  widest principal type, as `SpecSecurityConsistencyTest` requires the declared principal to
  hold every admitted role. Raise `SPECIFIED_OPERATION_COUNT` from `13` to `15` in this
  commit.
- **`size` declares no `maximum`.** A spec-level `maximum` and a runtime clamp are mutually
  exclusive: Bean Validation would reject an over-large `size` with `400` before the handler
  runs, making the clamp dead code, and a YAML literal cannot track a configurable ceiling, so
  the two would drift the first time an operator changed `page-max-size`. The configurable
  clamp wins; the operation description states that a `size` above the configured ceiling is
  clamped rather than rejected, and that the response's `size` field reports the applied value.
  `page`'s `minimum: 0` and `size`'s `minimum: 1` stay in the spec — those bounds are fixed.
- **`GET /participants/{identifier}` cannot dependably address a slash-bearing identifier, and
  the `identifier` query filter is the escape hatch.** This repo already settled the same question
  for users: `api/openapi.yaml`'s `/users/{identifier}` description (lines 291-292, 524-525)
  and `docs/user-identifiers.md` both state that a path variable never spans a `/` and the
  container percent-decodes before routing, so an encoded slash is still unaddressable, and
  both point callers at `POST /users/search`'s exact `identifier` criterion instead. A
  participant identifier may be a URI (`https://example.com/participant`) as well as a DID, so
  without a fallback AC 5 would hold only for slash-free identifiers. The `identifier` query
  parameter on `GET /participants` is that fallback, and the `{identifier}` operation
  description carries the same limitation note and points at it.

  Step 4 implemented this and found the premise too strong: asserted end to end, this stack
  *does* resolve `GET /participants/<percent-encoded URI>` with a `200`, because Netty matches
  the route before the path variable is decoded. Whether an encoded `/` survives is therefore a
  property of the deployment - an intermediary that refuses or decodes `%2F` ahead of routing
  still leaves such an identifier unaddressable - rather than of this service. The conclusion is
  unchanged, so the filter stays and the operation description now says "dependable" instead of
  "only"; what changed is that the specification no longer states a falsehood, and the IT
  asserts what the filter guarantees rather than what the path route happens to do here.
  `docs/user-identifiers.md` and the `/users/{identifier}` and
  `/participants/me/users/{identifier}` descriptions made the same too-strong claim. They were
  corrected in the same wording on review of PR #5 rather than deferred: the repo would otherwise
  say two different things about one routing mechanism, and the user-side behaviour did not
  change - only the sentence describing it. `POST /users/search` remains the documented escape
  hatch.
- **Deregistered participants are excluded from `GET /participants` by default** — the
  directory lists who can be transacted with — but remain resolvable by
  `GET /participants/{identifier}` and by the `identifier` filter, carrying `deregisteredAt`,
  so a retained consent's counterparty stays legible (Step 5).
- **Admitting `USER` means a directory read JIT-provisions a `users` row, and that is
  intended.** `PrincipalResolutionFilter.user(...)` calls `userService.provisionFromToken`
  for every USER-role request, so these are the first read-only routes where it fires. That
  is the repo's settled contract rather than a side effect to design around: `POST /users`'s
  own description already states that registration "is the same code path as the
  just-in-time provisioning **every other authenticated route** performs, so a user first
  seen through another endpoint is already registered". Making the directory the one
  exception would need a filter carve-out per route and would break that stated idempotence.
  The ticket mandates USER access, so `USER` stays admitted and provisioning stays on. Step
  4's IT asserts this deliberately — a USER token previously unseen by this service reads
  `GET /participants` and is afterwards resolvable through `GET /users/{identifier}` — so
  the behaviour is pinned rather than discovered.

**Implementation**

- `config/ConsentManagerConfiguration.java` — add a `@ConfigurationProperties("participants")`
  nested class with `pageDefaultSize` and `pageMaxSize`, each backed by a named
  `DEFAULT_*` constant, following the `Users` nested class. Document both env vars in
  `.env.sample` and add them to `application.yml`.
- `ParticipantService.list(int page, int size, @Nullable String identifier)` — clamps `size`
  to the configured ceiling (never rejects it), filters out deregistered rows, applies the
  exact `identifier` match when one is given, and delegates to `ParticipantRepository`
  (already a `PageableRepository`) via `Pageable.from(page, size)`.
  `findByIdentifier(...).orElseThrow(NotFoundException::new)` for the single-record lookup,
  which resolves a deregistered participant too.
- `repository/ParticipantRepository.java` — today it carries only `findByIdentifier`,
  `existsByIdentifier` and `findByIdIn`, none of which is deregistration-aware or paged by a
  filter. Add `Page<Participant> findByDeregisteredAtIsNull(Pageable pageable)` for the
  default listing and `Page<Participant> findByIdentifier(String identifier, Pageable
  pageable)` for the filtered one, so the exclusion and the exact match are both expressed as
  derived queries rather than filtered in memory after a full page read. The filtered query
  deliberately does **not** also exclude deregistered rows, which an earlier draft of this
  step had it do: the spec section above makes the `identifier` filter the escape hatch for
  an identifier containing a `/`, so excluding deregistered rows there would leave a
  retained consent's URI-shaped counterparty unreachable on every route - the one case the
  escape hatch exists for. A caller naming one identifier asks about that record, not about
  who may be transacted with. The single-argument `findByIdentifier` already serves the
  single-record lookup and must keep resolving deregistered rows.
- `ParticipantController` — both handlers; the list maps a Micronaut Data `Page` onto
  `ParticipantPage`.

**Tests**

- `api/ParticipantDirectoryIT.java` — a paginated listing exposes the global identifier,
  legal name, self-description URI and email; page boundaries behave; a `size` above the
  configured ceiling returns **`200`** with the response's `size` equal to the ceiling (assert
  the status explicitly — "the clamp behaves" and "`400`" are different tests, and this one
  pins which of the two the spec chose), while `size: 0` and `page: -1` are `400` from the
  declared minima; lookup by identifier resolves a DID and `404`s on an unknown one; a
  URI-shaped identifier containing a `/` is resolved through `GET /participants?identifier=...`; a deregistered participant is
  absent from the unfiltered listing but still resolvable by identifier with `deregisteredAt`
  set; all three roles can read, and an unregistered participant token can read too (the route
  needs no row).

**Acceptance criteria:** AC 4, AC 5, AC 14 on the read surface.

### Step 5: ADR-0008 and `DELETE /participants/me`

Covers US-PM-007, AC 10, AC 11.

**ADR**

- `docs/adr/0008-deregistration-retains-the-participant-and-closes-its-open-business.md`
  — same structure as ADR-0007 (H1 title, bold metadata bullets, `## Context`, `## Decision`
  with H3 subsections, `## Consequences`). It must argue the participant case **on its own
  terms**, not by analogy to ADR-0007, because the premise differs: ADR-0007 scrubs a user
  because the identifier is personal data of a data subject with an erasure right. Record:
  - the `409` guard on `GRANTED` consents, and why a participant may not unilaterally end a
    permission a user granted;
  - why `PENDING`/`DRAFT` consents are terminated instead, carrying over ADR-0007's principle
    that nothing may be left in a state a later lifecycle operation could still advance — a
    `PENDING` consent naming a departed participant could otherwise be moved to `GRANTED`,
    handing a data-sharing basis to an organization that is gone;
  - why consents and consent events are retained;
  - why the retained row keeps its identity **intact** rather than being pseudonymised: an
    organization has no erasure right, the ticket's own reason for retaining consents is that
    "the participant reference remains resolvable", and there is no `erasure_verifier`
    counterpart, so a scrubbed participant would be unrecoverable by anyone — leaving every
    retained consent naming a counterparty nobody can identify;
  - why `email` is nonetheless cleared: it is a contact person's address, the one field on
    `participants` that can be personal data of a natural person;
  - why the ticket's "the row is deleted" cannot be honoured literally — `ON DELETE RESTRICT`
    on `privacy_notices` and `consents` forbids it whenever either exists — and why outright
    deletion is therefore kept only for the never-transacted participant;
  - and what `deregistered_at` means to readers: excluded from the directory listing, still
    resolvable by identifier, not a soft-deleted record pretending to be erased.

**Schema** — none. `V3__participant_deregistration.sql` and the `domain/Participant` field
landed in Step 2, because Steps 2 and 4 already read the column. This step is the first to
*write* it.

**Spec**

- `api/components/schemas/DeregistrationSummary.yaml` — `deregisteredAt` (absent when the row
  was deleted outright), plus counts of links removed, notices archived, consents terminated
  and consents retained. Mirrors `ErasureSummary`.
- `openapi.yaml` — `DELETE /participants/me` (`deregisterCurrentParticipant`), `PARTICIPANT` /
  `ParticipantPrincipal`, responses `200` (`DeregistrationSummary`), `401`, `403`, `409`,
  `default`. The description states the cascade in order and links the ADR. Raise
  `SPECIFIED_OPERATION_COUNT` from `15` to `16` in this commit — `16` is the expected end
  state for this ticket.

**Implementation**

- `ParticipantService.deregister(Participant)` — one `@Transactional` method:
  1. `ConflictException` if any consent names the participant as provider or consumer with
     status `GRANTED`. The message names the blocking consents, since the remedy is for the
     users to revoke them.
  2. Move every `PENDING` and `DRAFT` consent naming the participant as provider or consumer
     to `TERMINATED`, each with a `CONSENT_TERMINATED` event attributed to the service and a
     reason naming the deregistration. Without this the cascade would leave an open offer that
     a later lifecycle operation could still advance to `GRANTED` — the exact failure ADR-0007
     names and closes for users. Terminating rather than widening the `409` is deliberate: a
     draft the participant itself opened must not be able to block the participant's own exit.
  3. Delete every `user_participants` row for the participant.
  4. Archive every active privacy notice where the participant is provider or consumer
     (set `archivedAt`).
  5. Delete the `participants` row when nothing references it; otherwise set
     `deregistered_at` to the current instant and clear `email`, leaving `identifier`,
     `legalName`, `selfDescriptionUri` and `legalPerson` intact so retained consents stay
     legible. `endpoints` resets to an empty map (the column is `NOT NULL DEFAULT '{}'`) —
     a departed participant must not keep advertising a callback.
  6. Consent events are append-only and are never rewritten; the consents themselves are
     retained apart from the status change in step 2.
- `ParticipantService.register` (Step 2) gains one rule this step introduces: an identifier
  whose row exists but is deregistered **reactivates** that row — clearing `deregistered_at`
  and overwriting the mutable fields from the body — rather than returning `409`.
  `uq_participants_identifier` makes a second row impossible, and refusing outright would
  permanently bar an organization from rejoining the dataspace. Only an *active* row is a
  `409`. Step 2's test list gains this case.
- `repository/ConsentRepository.java` — add `existsByProviderIdAndStatus`,
  `existsByConsumerIdAndStatus`, the by-participant-and-status finder step 2 needs, and the
  reference-existence queries the delete-vs-retain branch needs.
- `repository/PrivacyNoticeRepository.java` — add the consumer-side and
  archive-by-participant queries missing today.
- `repository/UserParticipantRepository.java` — add `long deleteByIdParticipantId(UUID
  participantId)`. It has `deleteByIdUserId(UUID)` but no participant-side equivalent, and
  cascade step 3 needs one: the foreign key's `ON DELETE CASCADE` only fires on the delete
  branch, so the retain branch would otherwise leave every affiliation in place.
- `ParticipantController.deregisterCurrentParticipant` — resolves via
  `requireRegistered()`.

**Tests**

- `service/ParticipantDeregistrationIT.java` — parameterized over `GRANTED` as provider and
  as consumer, both `409`; parameterized over `PENDING` and `DRAFT` as provider and as
  consumer, all four terminated with a `CONSENT_TERMINATED` event and none left advanceable;
  `REVOKED`/`EXPIRED`/`TERMINATED`/`REFUSED` consents untouched; links removed on the retain
  branch as well as the delete branch; active notices archived and already-archived ones
  untouched; consents and consent events still readable afterwards; the retained row keeps its
  identifier, legal name and self-description URI, carries `deregisteredAt`, and has `email`
  and `endpoints` cleared; the delete branch fires for a participant with no references;
  re-registering a deregistered identifier reactivates the row and clears `deregisteredAt`.
- `service/ParticipantDeregistrationAtomicityIT.java` — a failure mid-cascade rolls the whole
  transaction back, mirroring `UserErasureAtomicityIT`.

**Acceptance criteria:** AC 10, AC 11.

### Step 6: Validation matrix and the authorization matrix

Covers AC 13, AC 15, AC 9, AC 14.

- `api/ParticipantValidationIT.java` — a `@ParameterizedTest` matrix over registration and
  update payloads: blank and over-length `legalName`; `http`, relative and malformed
  `selfDescriptionUri` and `endpoints.consentNotification`; malformed `email`; over-length
  legal-person fields. Each asserts `400` with `application/problem+json` naming the
  offending field, and each valid variant asserts `201`/`200`.
- `api/EndpointRoleMatrixIT.java` — extend the existing matrix with every new operation ×
  every role (`USER`, `PARTICIPANT`, `CATALOG`, `OUTSIDER`, unauthenticated), asserting the
  spec's roles exactly. Explicitly assert that a participant token cannot modify another
  participant's record: there is no identifier-accepting write route, and `/participants/me`
  always resolves from the token.
- A test that walks every `Participant`-family generated model and asserts no property name
  matches a credential vocabulary (`clientId`, `clientSecret`, `password`, `secret`, `token`,
  `key`), pinning AC 14 against future spec edits.

**Acceptance criteria:** AC 9, AC 13, AC 14, AC 15; JaCoCo gate still passes.

### Step 7: Publish the participant token contract (US-PM-002)

Documentation only — no code. The validation behaviour stays owned by TICKET-003.

- `api/components/security.yaml` — extend the `bearerAuth` description with how a
  participant obtains a token: a worked `client_credentials` example against a registered
  IDP's token endpoint, the required participant-identifier claim, and the
  `Authorization: Bearer <token>` presentation.
- `api/openapi.yaml` `info.description` — a short "Participant onboarding" subsection: the
  IDP is the gatekeeper, there is no login endpoint and no approval workflow, and the first
  `POST /participants` call is the whole onboarding.
- `docs/security.md` — the same contract in prose, with the configurable claim name and a
  pointer to ADR-0006.
- State explicitly that `GET /.well-known/jwks.json` (US-PM-008) is owned end-to-end by
  TICKET-011 — route and key material both — and not served yet, so no reader expects it
  from this module.

**Acceptance criteria:** the token contract and a worked example are published in both the
API description and `docs/security.md`; `./mvnw verify` green (the spec is parsed by
`SpecSecurityConsistencyTest`).

### Step 8: Final verification and context refresh

- Run `./mvnw clean verify` from a clean target and confirm unit tests, integration tests,
  Spotless and the JaCoCo gate all pass.
- Walk the 15 acceptance criteria and record, for each, the test that pins it — AC 12
  recorded as struck by owner agreement on PR #1 and owned end-to-end (route and keys) by
  TICKET-011, not merely deferred.
- The `AGENTS.md` refresh is **not** performed here. A step agent is forbidden by its
  operating rules from editing `AGENTS.md`: the file is appended to the agent system prompt,
  so any edit invalidates the cached prompt prefix for every later session. The refresh is
  carved out into Step 10, which a plan-mode agent performs; Step 10 specifies the content.
- Confirm every new configuration key is in `.env.sample` and `application.yml`.
- Confirm `SpecSecurityConsistencyTest.SPECIFIED_OPERATION_COUNT` reads `16` and that the
  parsed operation list actually has that many entries — the constant is only a tripwire if
  it tracks reality, and a step that dropped an operation while bumping the count would
  otherwise pass.

**Acceptance criteria:** clean `./mvnw clean verify`; the acceptance-criteria walk is
recorded on the ticket. `AGENTS.md` moves to Step 10.

### Step 9: Correct the `-DskipUTs` claim in `docs/security.md`

Carved out of the former Step 9 so that the part a **step** agent may do lands without
waiting on the part it may not. `docs/security.md`'s closing line on integration tests
documented `./mvnw verify -DskipUTs` as an integration-only run. It is not one: `skipUTs` is
defined nowhere in `pom.xml` or `.mvn/` — Surefire and Failsafe are both declared with no
`<configuration>` at all — and nowhere in the inherited `micronaut-parent` /
`micronaut-platform` 5.2.0 poms either. Maven silently ignores an undefined `-D`, so the
documented command ran the whole suite.

Corrected to what is true rather than by substituting another flag: `./mvnw verify` runs both
suites, `./mvnw test` runs the unit tests alone, and there is no integration-only flag because
Surefire and Failsafe share the `skipTests` property and no property separating them is wired
in `pom.xml`. Wiring one is a build change, and therefore a step of its own rather than part
of a documentation pass.

**Acceptance criteria:** `docs/security.md` no longer documents a flag the build does not
define. No other file changes. No build run — Spotless is configured for `<java>` only, so a
Markdown-only change cannot affect it.

### Step 10: Refresh `AGENTS.md` (plan-mode agent)

Owned by a **plan-mode** agent rather than a step agent, because `AGENTS.md` is appended to
the agent system prompt and a step agent may not edit it. That restriction is the whole
reason this step exists separately, and it is why Step 9 was split off ahead of it.
Documentation only: `AGENTS.md` alone, no code, spec, test or configuration change.

Points 1-9 are `AGENTS.md`'s staleness against the merged state of ticket #69; points 10-12
are pre-existing staleness that predates this ticket, folded in because the file is being
edited anyway and each is a statement a future agent would otherwise act on. The list is not
a cap — anything else found stale while editing is in scope.

**Stale against ticket #69**

1. The `service/` line names `UserProvisioningService`, which does not exist — JIT
   provisioning lives in `UserService` and returns a `ProvisionedUser`. Raised on PR #1. It
   should read `UserService (registration, JIT provisioning, search, links, ADR-0007
   erasure), ParticipantService (registration, self-service, ADR-0008 deregistration),
   ErasureVerifier, CallerScope`.
2. The participant module's files are unlisted: `ParticipantRegistration`,
   `ParticipantUpdate` and `DeregistrationResult` under `service/`, and `ParticipantMapper`
   plus `ParticipantController` under `api/`.
3. The pagination convention from Step 4 is undocumented: `GET /participants` clamps an
   oversized `size` to `consent-manager.participants.page-max-size` and answers `200` rather
   than refusing, the spec carries no `maximum` because the ceiling is per deployment, and
   the response reports the size it actually applied.
4. Neither documented limitation of `GET /participants/{identifier}` is recorded, and the
   wording must match what the merged tree already says rather than restating the
   too-strong premise PR #5 removed from the spec. `api/openapi.yaml` and
   `docs/user-identifiers.md` both say a path variable never spans a `/`, so a URI-shaped
   identifier reaches the route **only percent-encoded**, and whether an encoded slash
   survives is a property of the deployment rather than of this service — this stack happens
   to route `%2F` through because the path variable is decoded after the route is matched.
   The `identifier` query filter on `GET /participants` is therefore the *dependable* way to
   reach such a record, not the only one. `AGENTS.md` must not say "no path segment can
   carry a slash": that would contradict the spec and `docs/user-identifiers.md` and seed
   the contradiction in the file every later agent loads as system prompt. The second
   limitation belongs in the same sentence: the literal identifier `me` is unreachable on
   that route in **every** deployment, because `GET /participants/me` claims the path.
5. The nullable-row `ParticipantPrincipal` contract from Step 1 is undocumented:
   `ParticipantPrincipal` may carry an identifier whose `participants` row is absent, which is
   what makes self-registration reachable, and `POST /participants` is the only route
   accepting that shape.
6. `ParticipantPrincipal`'s two accessors are a **three**-state contract, and the third state
   is the one a reader is least likely to infer. `requireRegistered()` admits any registered
   row and is what a route merely reading or writing the caller's own record uses
   (`ParticipantController`, and `CallerScope.of()`, which is why every participant-scoped
   read fails closed for an unregistered token). `requireActive()` is stricter by one
   condition — it also refuses a row carrying `deregistered_at` — and is what every route
   acting in the dataspace in the participant's name uses (`ParticipantUserController`),
   because letting a departed participant's token keep writing would let it re-create the
   very affiliations deregistration removed, while read-back of its own record stays open.
   This is what makes Step 5's retain branch safe.
7. `V3__participant_deregistration.sql` is missing from the migration list, as are the
   `deregistered_at` semantics: nullable, null for every active participant, set only on the
   retain branch, with a partial index keeping the directory listing to active rows.
8. The `api/components/schemas/` listing omits the six schemas this ticket added —
   `Participant.yaml`, `ParticipantEndpoints.yaml`, `ParticipantLegalPerson.yaml`,
   `ParticipantRegistration.yaml`, `ParticipantUpdate.yaml`, `DeregistrationSummary.yaml`.
   In an API-first repo this is the more load-bearing half of point 2. The
   `config/ConsentManagerConfiguration.java` line still reads "nested Erasure / Users
   classes"; Step 4 added a third, `@ConfigurationProperties("participants")`. And the ADR
   range reads `0001-0007`; it should read `0001-0008`.
9. `SpecSecurityConsistencyTest` is named as the only build-failing spec test. This ticket
   added two more plain surefire unit tests that fail the build on a bad spec edit and that a
   future agent should know about before touching the participant schemas:
   `ParticipantSchemaParityTest` (registration/update parity) and
   `ParticipantCredentialAbsenceTest` (AC 14).

**Pre-existing staleness, folded in**

10. Package listings are incomplete. `error/` omits `ApiExceptionHandler.java`,
    `ConstraintViolationProblemHandler.java` and `ProblemType.java`; `domain/` omits
    `PrivacyNoticePayload.java`; `src/main/resources/` omits
    `static/swagger-ui/index.html`, which is what makes the Swagger UI story in "Important
    Files" work.
11. `SpecReferenceResolutionTest` is a second build-failing spec test not called out: it
    asserts every relative `$ref` resolves in both the authored `api/` tree and the
    `target/classes/static/` copy Swagger UI loads, and that no path item is externalised.
12. **`./mvnw verify -DskipUTs` does not skip unit tests — confirmed, not assumed.**
    `skipUTs` appears nowhere in `pom.xml` or `.mvn/`, and not in the inherited
    `micronaut-parent` 5.2.0 or `micronaut-platform` 5.2.0 poms either (both fetched and
    grepped; neither wires a surefire skip to such a property, and `micronaut-platform` has
    no further parent). Maven silently ignores an undefined `-D`, so the documented command
    runs the whole suite. The claim appeared twice; Step 9 already corrected the
    `docs/security.md` occurrence, leaving `AGENTS.md`'s Build & Test block, whose
    `./mvnw verify -DskipUTs  # integration tests only` line is still wrong. Correct it to
    what is true — `./mvnw verify` runs both suites, `./mvnw test` runs unit tests only —
    rather than substituting another flag: Surefire and Failsafe share the `skipTests`
    property, so an integration-only run needs a property wired in `pom.xml` that does not
    exist today. Wiring one is a build change and therefore a separate step.

**Acceptance criteria:** `AGENTS.md` matches the merged state on every point above; the
enumeration is a floor, not a cap. No other file changes.
