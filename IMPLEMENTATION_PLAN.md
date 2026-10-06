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

1. **Deregistration mirrors ADR-0007.** `DELETE /participants/me` rejects with `409` while
   any consent naming the participant as provider or consumer is `GRANTED`; it then removes
   all `user_participants` links and archives active privacy notices, and finally *deletes*
   the `participants` row when nothing still references it or *pseudonymises* it when a
   retained consent or privacy notice still points at it. One transaction. ADR-0008 records
   this. Because `privacy_notices` carries `ON DELETE RESTRICT` on both participant foreign
   keys, a participant that ever had a notice lands on the pseudonymise branch; outright
   deletion is the never-transacted case.
2. **Self-registration is made reachable by the minimal change.**
   `PrincipalResolutionFilter` stops turning an unregistered `PARTICIPANT` identifier into a
   `403` and instead resolves a `ParticipantPrincipal` carrying the identifier with its row
   absent. `POST /participants` is the one route that accepts that shape; every other
   participant-scoped route refuses when the row is absent. No new principal subtype, no
   route exemption from principal resolution, no new role, and
   `SpecSecurityConsistencyTest` keeps its rule that a secured operation declares a
   `ConsentManagerPrincipal` parameter.
3. **US-PM-008 / AC 12 (`GET /.well-known/jwks.json`) is out of scope** and deferred
   entirely to TICKET-011, which owns the key material.

### Scope notes

- AC 9 constrains *modification* only. `GET /participants/{identifier}` is deliberately
  readable by `USER`, `PARTICIPANT` and `CATALOG`; every mutation lives under
  `/participants/me` and is resolved from the token, so there is no identifier-accepting
  write route to guard.
- AC 2 is satisfied structurally: `identifier` is simply absent from
  `ParticipantRegistration` and `ParticipantUpdate`, so an identifier in the body is
  ignored rather than rejected.
- US-PM-002 is documentation only; the validation behaviour stays owned by TICKET-003.
- No Flyway migration is needed. `V1__initial_schema.sql` already carries `participants`
  with `endpoints`/`legal_person` JSONB and `uq_participants_identifier`. Participant
  pseudonymisation needs no verifier column — a participant is an organization, not a data
  subject, so ADR-0007's keyed `erasure_verifier` has no counterpart here.

### Constraints every step inherits

- Spec and implementation land in the **same** step. `SpecSecurityConsistencyTest` fails the
  build when a specified operation has no implementing route, so a spec-only step cannot be
  merged.
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
  `ForbiddenException` with a detail naming registration as the remedy. The compact
  constructor keeps `issuer`, `subject` and `identifier` non-null, so the identifier is
  always available.
- `src/main/java/com/seamware/consentmanager/security/PrincipalResolutionFilter.java` — the
  PARTICIPANT branch keeps the "no participant identifier claim" `403` and replaces the
  "not registered" `403` with `new ParticipantPrincipal(issuer, subject, identifier, null)`.
  Resolution still runs on `TaskExecutors.BLOCKING`.
- `src/main/java/com/seamware/consentmanager/api/ParticipantUserController.java` — every
  existing call site that reads `principal.participant()` switches to
  `principal.requireRegistered()`, preserving today's `403` for an unregistered caller.
  Sweep for any other `participant()` reader before finishing.

**Tests**

- `src/test/java/com/seamware/consentmanager/security/PrincipalResolutionIT.java` — add a
  case asserting that a `PARTICIPANT` token whose identifier is not in `participants`
  resolves a principal with the identifier set and the row absent, rather than `403`.
- `src/test/java/com/seamware/consentmanager/api/EndpointRoleMatrixIT.java` — assert the
  existing `/participants/{identifier}/users...` routes still answer `403` for an
  unregistered participant token.

**Acceptance criteria:** unregistered `PARTICIPANT` tokens reach their route; every
pre-existing participant-scoped route still refuses them with `403`; `./mvnw verify` green.

### Step 2: Participant representation schemas, mapper and `POST /participants`

Covers US-PM-001, AC 1, AC 2, AC 3.

**Spec** (`api/components/schemas/`, referenced from `openapi.yaml`'s `components`)

- `Participant.yaml` — `identifier` (required), `legalName` (required),
  `selfDescriptionUri`, `email`, `legalPerson`, `endpoints`, `createdAt`, `updatedAt`
  (`format: date-time`). No property is `nullable`, matching `User.yaml`'s rationale: a
  nullable response property forces `JsonNullable` on the model.
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
  the token and that a body-supplied identifier is ignored.

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
  the token's identifier; an `identifier` in the body is ignored; a second registration
  returns `409`.

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
  whose participant is not registered, and the descriptions say so.

**Implementation**

- `ParticipantService.update(Participant current, ParticipantUpdate body)` — `@Transactional`.
  Writes the mutable columns, leaves `identifier` untouched, and **re-reads the row** before
  returning it, so the response is the persisted state and not the in-memory object
  (original bug 05-PM-12.5).
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

- `api/components/schemas/ParticipantPage.yaml` — `content` (array of `Participant`),
  `page`, `size`, `totalElements`, `totalPages`. Because `items:` must resolve a named model,
  write this schema **inline** in `openapi.yaml` alongside `BulkUserRegistration`, with a
  comment naming the reason.
- `openapi.yaml` — `GET /participants` (`listParticipants`) with `page` (integer, min 0,
  default 0) and `size` (integer, min 1, `maximum` matching the configured ceiling, default
  matching the configured default) query parameters; `GET /participants/{identifier}`
  (`getParticipantByIdentifier`) with the URL-encoded global identifier as a path parameter
  and a `404` response. Both are `bearerAuth: [USER, PARTICIPANT, CATALOG]`,
  `x-roles: [USER, PARTICIPANT, CATALOG]`, `x-principal: ConsentManagerPrincipal` — the
  widest principal type, as `SpecSecurityConsistencyTest` requires the declared principal to
  hold every admitted role.

**Implementation**

- `config/ConsentManagerConfiguration.java` — add a `@ConfigurationProperties("participants")`
  nested class with `pageDefaultSize` and `pageMaxSize`, each backed by a named
  `DEFAULT_*` constant, following the `Users` nested class. Document both env vars in
  `.env.sample` and add them to `application.yml`.
- `ParticipantService.list(int page, int size)` — clamps `size` to the configured ceiling and
  delegates to `ParticipantRepository` (already a `PageableRepository`) via
  `Pageable.from(page, size)`. `findByIdentifier(...).orElseThrow(NotFoundException::new)`
  for the single-record lookup.
- `ParticipantController` — both handlers; the list maps a Micronaut Data `Page` onto
  `ParticipantPage`.

**Tests**

- `api/ParticipantDirectoryIT.java` — a paginated listing exposes the global identifier,
  legal name, self-description URI and email; page/size boundaries and the clamp behave;
  lookup by identifier resolves a URL-encoded DID and `404`s on an unknown one; all three
  roles can read, and an unregistered participant token can read too (the route needs no row).

**Acceptance criteria:** AC 4, AC 5, AC 14 on the read surface.

### Step 5: ADR-0008 and `DELETE /participants/me`

Covers US-PM-007, AC 10, AC 11.

**ADR**

- `docs/adr/0008-deregistration-pseudonymises-the-participant-and-retains-the-audit-trail.md`
  — same structure as ADR-0007 (H1 title, bold metadata bullets, `## Context`, `## Decision`
  with H3 subsections, `## Consequences`). Record: the `409` guard on `GRANTED` consents; why
  consents and consent events are retained; why the row is pseudonymised rather than deleted
  whenever anything still references it; why `ON DELETE RESTRICT` on `privacy_notices` makes
  the pseudonymise branch the normal one; and why a participant needs no counterpart to the
  user's keyed `erasure_verifier`.

**Spec**

- `api/components/schemas/DeregistrationSummary.yaml` — the pseudonymous identifier (present
  only when the row was retained), plus counts of links removed, notices archived and
  consents retained. Mirrors `ErasureSummary`.
- `openapi.yaml` — `DELETE /participants/me` (`deregisterCurrentParticipant`), `PARTICIPANT` /
  `ParticipantPrincipal`, responses `200` (`DeregistrationSummary`), `401`, `403`, `409`,
  `default`. The description states the cascade in order and links the ADR.

**Implementation**

- `ParticipantService.deregister(Participant)` — one `@Transactional` method:
  1. `ConflictException` if any consent names the participant as provider or consumer with
     status `GRANTED`.
  2. Delete every `user_participants` row for the participant.
  3. Archive every active privacy notice where the participant is provider or consumer
     (set `archivedAt`).
  4. Delete the `participants` row when nothing references it; otherwise pseudonymise:
     replace `identifier` with `DEREGISTERED_IDENTIFIER_PREFIX + UuidGenerator.uuidV7()`
     under a reserved prefix mirroring `urn:consent-manager:erased:`, set `legalName` to a
     named placeholder constant (the column is `NOT NULL`), null `selfDescriptionUri`,
     `email` and `legalPerson`, and reset `endpoints` to an empty map (the column is
     `NOT NULL DEFAULT '{}'`).
  5. Consents and consent events are untouched.
- `repository/ConsentRepository.java` — add `existsByProviderIdAndStatus`,
  `existsByConsumerIdAndStatus` and the reference-existence queries the delete-vs-pseudonymise
  branch needs.
- `repository/PrivacyNoticeRepository.java` — add the consumer-side and
  archive-by-participant queries missing today.
- `ParticipantController.deregisterCurrentParticipant` — resolves via
  `requireRegistered()`.

**Tests**

- `service/ParticipantDeregistrationIT.java` — parameterized over `GRANTED` as provider and
  as consumer, both `409`; links removed; active notices archived and already-archived ones
  untouched; consents and consent events still readable afterwards; the retained row carries
  the reserved-prefix identifier and no identity-bearing field; the delete branch fires for a
  participant with no references.
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
- State explicitly that `GET /.well-known/jwks.json` (US-PM-008) is owned by TICKET-011 and
  not served yet, so no reader expects it from this module.

**Acceptance criteria:** the token contract and a worked example are published in both the
API description and `docs/security.md`; `./mvnw verify` green (the spec is parsed by
`SpecSecurityConsistencyTest`).

### Step 8: Final verification and context refresh

- Run `./mvnw clean verify` from a clean target and confirm unit tests, integration tests,
  Spotless and the JaCoCo gate all pass.
- Walk the 15 acceptance criteria and record, for each, the test that pins it — AC 12
  recorded as deferred to TICKET-011.
- Update `AGENTS.md`: the participant module's files, the pagination convention introduced
  in Step 4, the nullable-row `ParticipantPrincipal` contract from Step 1, and ADR-0008.
- Confirm every new configuration key is in `.env.sample` and `application.yml`.

**Acceptance criteria:** clean `./mvnw clean verify`; `AGENTS.md` matches the merged state;
the acceptance-criteria walk is recorded on the ticket.
