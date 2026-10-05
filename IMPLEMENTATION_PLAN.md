# Implementation Plan: Consent Manager - Reimplementation - User Management

## Overview
Implement the user record and the user↔participant link for the reimplemented Consent Manager:
one `UserService` built around the deterministic registration rule (create-or-link on the global
identifier, never merge, never match on email) plus nine HTTP operations across
`/users/*` and `/participants/me/users/*`, authored spec-first in `api/` and implemented against
the generated abstract controllers. The database schema, the domain entities, the repositories,
the typed principals and just-in-time provisioning already exist from tickets #65–#67, so this
ticket adds the service layer, the API surface, the error mapping those endpoints need, and the
erasure path for the data subject.

## Context and Constraints

Facts established by the merged groundwork that every step below depends on:

- **Schema is complete — no Flyway migration is needed.** `V1__initial_schema.sql` already has
  `users` (`uq_users_identifier`, partial `idx_users_email_lower` on `lower(email)`),
  `user_participants` (PK `(user_id, participant_id)`, nullable `local_identifier`,
  `ON DELETE CASCADE` on both FKs), `consents` (`ON DELETE RESTRICT` on `user_id`) and
  `consent_events` (`ON DELETE CASCADE` on `consent_id`). A step that believes it needs DDL has
  misread the schema; `ON DELETE RESTRICT` on `consents.user_id` is precisely why erasure
  pseudonymises the user row instead of deleting it.
- **Package root is `com.seamware.consentmanager`**, not the `eu.prometheusx.…` paths quoted in the
  ticket. Follow the repo. Generated API code lands in `target/generated-sources/openapi/` (never
  committed) under `com.seamware.consentmanager.api.generated` for the abstract controllers and
  `com.seamware.consentmanager.api.generated.model` for the request and response models — that is
  what every controller added by this ticket imports from.
- **Spec-first is enforced by a test.** `SpecSecurityConsistencyTest` reads the bundled
  `static/openapi.yaml` and fails the build when a specified operation has no implementing
  controller method, when `@Secured` disagrees with the operation's `security` requirement, when a
  secured operation does not declare a `ConsentManagerPrincipal`-typed parameter, or when a secured
  operation does not reference `#/components/responses/Unauthorized` and `.../Forbidden`.
  **Consequence: a step must never add paths to the spec without the controller that implements
  them in the same step.** This is why the plan slices vertically by endpoint rather than
  "all schemas first, all controllers later".
- **…but only for operations the test can see.** `specOperations()` parses the bundled spec with
  plain SnakeYAML and never resolves `$ref`: it walks `paths`, and for each path item collects the
  keys in `OPERATION_KEYS` (`get`, `post`, …). A path item written as
  `/users/register: {$ref: "./paths/users.yaml#/~1users~1register"}` has exactly one key — `$ref` —
  and therefore contributes **zero** operations. The test would keep passing on `/api-status`
  alone (the `isNotEmpty()` assertion is satisfied) while every endpoint this ticket adds goes
  unchecked, and the generator resolves the `$ref` so nothing else would complain either. The
  build-enforced spec↔`@Secured` guarantee the whole plan rests on would evaporate silently. See
  decision 5 below for the rule this imposes.
- **Role restriction comes from the spec.** `security: [{bearerAuth: [PARTICIPANT, CATALOG]}]`
  makes the `java-micronaut-server` generator emit `@Secured("PARTICIPANT", "CATALOG")` on the
  routed method in the generated abstract controller. `PrincipalResolutionFilter` reads those role
  names off the matched route to decide which role a multi-role token acts as. Never hand-annotate
  the concrete subclass: the rule on the routed wrapper wins (see `ApiStatusController`'s notes).
- **Principal binding.** `PrincipalArgumentBinders` already provides binders for `UserPrincipal`,
  `ParticipantPrincipal`, `CatalogPrincipal` and the sealed supertype `ConsentManagerPrincipal`.
  An operation open to both `PARTICIPANT` and `CATALOG` takes `ConsentManagerPrincipal` and
  branches on `role()`. `ParticipantPrincipal` already carries the resolved `Participant` row.
- **Generated models only.** Request and response bodies are authored under
  `api/components/schemas/` and generated into `com.seamware.consentmanager.api.generated.model`.
  Nothing under `api/dto/` is hand-written for these endpoints.
- **Formatting and build.** `./mvnw spotless:apply` before committing; `./mvnw verify` runs unit
  tests (Surefire, `*Test`) and integration tests (Failsafe, `*IT`). JaCoCo coverage gates are
  active — new service code needs tests to pass the build, not merely to be correct.
- **`KeycloakRoleMatrixIT` tests synthetic probe routes**, not real endpoints. Each endpoint step
  therefore carries its own authorization assertions for its own routes.

### Cross-cutting design decisions fixed here (so steps do not relitigate them)

1. **Identifiers in path segments.** `GET /users/{identifier}` and
   `PATCH|DELETE /participants/me/users/{identifier}` take the URL-encoded global identifier.
   Micronaut path variables do not match `/`, so an identifier containing a slash (a URI- or
   DID-shaped identifier) is not addressable this way even when percent-encoded, because the
   container decodes before routing. `POST /users/search` with `identifier` set is the documented
   escape hatch for those, and Step 4 must say so in the spec description of both operations.
2. **The registration rule never writes attributes onto an existing user.** `email`, `firstName`
   and `lastName` supplied by a participant are stored only on the insert that creates the user.
   IDP claims (via `provisionFromToken`) are the only source allowed to refresh them afterwards.
3. **Race safety is by constraint, not by pre-check.** `registerForParticipant` inserts and catches
   the `uq_users_identifier` violation, then re-reads — the pattern `UserProvisioningService`
   already uses. The same applies to the link insert against `pk_user_participants`. A leading
   `findByIdentifier` is *allowed* as a fast path that skips a doomed insert — `provision` has one
   today and keeps it — but correctness must never rest on it; the constraint is the only
   serialisation point. **The catch is only reachable outside an enclosing transaction**: under
   PostgreSQL a constraint violation aborts the whole transaction, so a re-read issued inside one
   fails with "current transaction is aborted, commands ignored until end of transaction block".
   Step 2 pins down what this means for a method that must insert a user *and* a link.
4. **404, not 403, for a participant not linked to a user.** Returning 403 would confirm the
   identifier exists. This applies to lookup, link update and unlink alike.
5. **Operations stay inline in `api/openapi.yaml`; only schemas and responses are externalised.**
   This keeps the externalisation pattern the repo already uses (`components/schemas/`,
   `components/responses/`) and keeps every operation visible to `SpecSecurityConsistencyTest`.
   No `api/paths/` directory is created by this ticket. Step 1 additionally hardens the test to
   fail outright on a path item whose only key is `$ref`, so that a later change cannot
   reintroduce the blind spot by accident.

## Steps

### Step 1: Problem-detail mapping for the client-error statuses the endpoints return

The endpoints in this ticket return 400, 403, 404, 409 and a 207 multi-status. Today
`GlobalExceptionHandler` is declared `ExceptionHandler<Exception, …>` — a catch-all that logs at
ERROR and renders 500. `BadRequestException`, `NotFoundException` and `ForbiddenException` exist but
nothing maps them, so every one of them would currently surface as a 500. Fix that before any
endpoint depends on it.

- Add `src/main/java/com/seamware/consentmanager/error/ConflictException.java` (409), following the
  shape of the three existing exceptions.
- Add an `ExceptionHandler` that maps the four domain exceptions to their statuses and renders
  `application/problem+json` using the generated `ProblemDetail`, with `type` URIs under
  `https://consent-manager.example/problems/<slug>` and `instance` set from the request path, to
  match what `AuthorizationProblemHandler` already emits for 401/403. Keep
  `GlobalExceptionHandler` as the unchanged last-resort 500 handler; the narrower handler wins by
  exception type.
- Map `jakarta.validation.ConstraintViolationException` to a 400 problem detail so bean-validation
  failures on generated request models (e.g. a blank required `identifier`) do not leak a stack
  trace or a Micronaut-shaped error body. `micronaut-validation` already ships an
  `ExceptionHandler` for this exception type, so the new one must `@Replaces` it rather than being
  declared as a parallel singleton — two handlers for one exception type is ambiguous bean
  resolution at best. Look up the built-in handler's exact class against the Micronaut version
  resolved by the `micronaut-parent` in `pom.xml` (currently
  `io.micronaut.validation.exceptions.ConstraintExceptionHandler`) and name *that* class in
  `@Replaces`; a stale guess fails fast at startup, which is the intended outcome.
- Author the shared responses `api/components/responses/BadRequest.yaml`, `NotFound.yaml` and
  `Conflict.yaml` and wire them into `components.responses` of `api/openapi.yaml`, so later steps
  reference them rather than redeclaring inline bodies. Declaring unreferenced response components
  is safe: `SpecSecurityConsistencyTest` constrains only the 401/403 references.
- **Close the `$ref` blind spot in `SpecSecurityConsistencyTest` before any later step writes a
  path item.** Add an assertion that fails when a path item carries a `$ref` key or carries no key
  in `OPERATION_KEYS`, naming the path and pointing at decision 5. The test cannot resolve an
  external path-item reference (it reads the spec with plain SnakeYAML, not a bundler), so the
  guard is a refusal, not a resolution: an operation it cannot see must break the build rather than
  be skipped. Do this here, in the step that also establishes the shared response components later
  steps reference, so that no window exists in which an unchecked operation can be merged.

**Files:** `error/ConflictException.java`, a new handler in `error/`, `api/openapi.yaml`,
`api/components/responses/{BadRequest,NotFound,Conflict}.yaml`,
`api/SpecSecurityConsistencyTest.java`, tests under `src/test/java/.../error/`.

**Acceptance:** parameterized unit test asserting (exception → status, `type`, `title`) for all four
domain exceptions plus the constraint-violation case; a test controller-backed IT confirming the
response content type is `application/problem+json` and that the body carries no stack trace; and
`SpecSecurityConsistencyTest` failing on a fixture path item whose only key is `$ref`, so the new
guard is itself tested rather than merely present.

### Step 2: `UserService` — the registration rule, race-safe, with no HTTP surface

Introduce the service all later steps call, with no endpoint attached, so the rule and its
concurrency behaviour are settled and tested on their own.

- Create `service/UserService.java` and fold `UserProvisioningService` into it: `provisionFromToken(UserPrincipal)`
  is the existing `provision` method, unchanged in behaviour (find-or-insert, catch the unique
  violation, re-read, refresh only asserted claims). Update `PrincipalResolutionFilter`'s
  constructor and `UserProvisioningServiceTest` / `UserProvisioningIT` accordingly — renaming them
  to `UserServiceTest` / `UserServiceIT` keeps the test names honest. Making
  `provisionFromToken` the single path shared by just-in-time provisioning and `POST /users/register`
  is what makes acceptance criterion 2 true by construction rather than by assertion.
- Add `findByIdentifier(String)` and the link-aware read used by later steps
  (`participantIdentifiersFor(User)` or equivalent, built on `UserParticipantRepository.findByIdUserId`).
- Add `registerForParticipant(Participant, UserRegistration)` implementing the rule and returning a
  `RegistrationOutcome` enum — `CREATED`, `LINKED`, `ALREADY_LINKED` — alongside the resulting
  `User`. Attributes are written on create only (decision 2 above). `localIdentifier` is stored on
  the link, and updated on the link when the link already exists and a value is supplied.
- Internal value types live in `service/`: a `UserRegistration` record (identifier, localIdentifier,
  email, firstName, lastName) that the controllers map the generated model onto, plus the
  `RegistrationOutcome` enum. Keeping the service free of generated API types is what lets the
  erasure and bulk paths reuse it.
- Existence is never *decided* by a pre-check: insert, catch `DataAccessException` from the unique
  constraint, re-read (decision 3). The same for the link insert — `ALREADY_LINKED` is the re-read
  outcome, not an `existsBy…` result. A leading read is permitted purely as a fast path, as
  `provision` does today.
- **Pin the transaction boundary, because this is where the pattern breaks.** `provision` is safe
  only because it runs outside any transaction — its javadoc says so: "Each repository call is its
  own transaction". `registerForParticipant` touches two tables and so invites a blanket
  `@Transactional`, which would be wrong: under PostgreSQL the `uq_users_identifier` violation
  aborts the enclosing transaction, and the re-read in the catch block then fails with "current
  transaction is aborted, commands ignored until end of transaction block" — the race path becomes
  a 500 instead of a `LINKED`. The rule for this step: **`registerForParticipant` must not be
  annotated `@Transactional`.** Each racing insert runs in its own transaction, as `provision`
  does, and the method is written to be safe when re-entered rather than to be atomic across the
  two tables. The user insert and the link insert are independently idempotent on their
  constraints, so the worst interleaving leaves a user with no link, which the next call repairs.
  If a future requirement genuinely needs the pair to be atomic, the only correct form is a
  `SAVEPOINT` around each racing insert (Micronaut Data does not provide one declaratively, so it
  would be explicit JDBC) — do not reach for it here.
- The concurrency IT below is what holds this honest: it must assert the *outcomes* of the losing
  threads (every caller returns `CREATED` or `LINKED`, none throws), not merely the final row
  count, because an aborted-transaction failure still leaves exactly one user row.

**Files:** `service/UserService.java`, `service/UserRegistration.java`,
`service/RegistrationOutcome.java`, delete `service/UserProvisioningService.java`,
`security/PrincipalResolutionFilter.java`, renamed tests, `AGENTS.md`.

Deleting `UserProvisioningService` makes the `AGENTS.md` project-structure line that names it
wrong, so correct that line in this step rather than waiting for Step 9's documentation sweep — a
stale path in the file that is appended to every agent's system prompt misleads every later step.

**Acceptance (AC 9, 10, 11, 15, 19):** a `@ParameterizedTest` over the four entry states — unknown
identifier, known identifier unlinked to this participant, already linked, already linked with a
changed `localIdentifier` — asserting outcome, user count and that attributes on a pre-existing user
are untouched. A Testcontainers IT firing N concurrent `registerForParticipant` calls for the same
identifier and asserting exactly one `users` row and one link.

### Step 3: `POST /users/register` and `GET /users/me`

The two `USER`-role self-service reads/writes, both backed by `provisionFromToken`.

- Author `api/components/schemas/User.yaml`: `identifier` (required), `email`, `firstName`,
  `lastName`, `participants` (array of participant identifiers the user is linked to), timestamps.
  Document on the schema that no credential, token or secret is ever part of this representation.
- Add `POST /users/register` (no request body; `201` on create, `200` on existing, both returning
  `User`) and `GET /users/me` (`200` with `User`) to `paths:` in `api/openapi.yaml`, written out
  inline beside `/api-status` (decision 5 — a `$ref`'d path item is invisible to
  `SpecSecurityConsistencyTest`). Both declare `security: [{bearerAuth: [USER]}]` and reference
  the shared `Unauthorized`/`Forbidden` responses. Only the request and response schemas are
  externalised, under `api/components/schemas/`.
- Implement `api/UserController.java` extending the generated abstract controller, taking
  `UserPrincipal`. Returning `HttpResponse<User>` is required to vary the status between 200 and
  201 — check that the generated signature permits it and, if the generator returns the bare model,
  declare the return type the generator produces and set the status via the response wrapper the
  generator supports. Settle this in this step; later steps (207 in Step 6) reuse the answer.
- The response is assembled from the `User` row plus the participant identifiers from the link
  table; a mapper from domain `User` to generated `User` belongs next to the controller and is
  reused by Steps 4–7.

**Acceptance (AC 1, 2, 3):** IT with a real Keycloak `USER` token — first call returns 201, second
returns 200 with an identical body; a user first seen through any other authenticated route (JIT)
then calling `/users/register` gets 200 and the same record; `GET /users/me` returns the record with
its participant links and the response body contains no token-shaped field. Authorization
assertions for `PARTICIPANT` and `CATALOG` tokens (403) on both routes.

### Step 4: `GET /users/{identifier}` and `POST /users/search`

The `PARTICIPANT`-or-`CATALOG` read surface, where caller scoping is the whole point.

- Author `api/components/schemas/UserSearch.yaml` (`identifier`, `email`, `participantIdentifier`,
  all optional) and add both operations inline to `paths:` in `api/openapi.yaml` under
  `security: [{bearerAuth: [PARTICIPANT, CATALOG]}]`. `POST /users/search` returns an array of
  `User` and references the shared `BadRequest` response; `GET /users/{identifier}` references
  `NotFound`. Document on both operations that `{identifier}` is the URL-encoded global identifier
  and that identifiers containing `/` must be looked up through `POST /users/search`
  (decision 1).
- Add `search(UserSearchCriteria, CallerScope)` to `UserService`. `CallerScope` carries the caller's
  role and, for a participant, its `Participant`; a `PARTICIPANT` caller's results are intersected
  with its own links regardless of the `participantIdentifier` in the body, a `CATALOG` caller's are
  not. An empty criteria set raises `BadRequestException`.
- Email matching is case-insensitive exact and **may match several users** — the spec must say
  email is neither unique nor authoritative. The existing `UserRepository.findByEmailIgnoreCase`
  returns `Optional<User>` and so cannot serve this: Micronaut Data raises
  `NonUniqueResultException` as soon as two users share an address, which the schema deliberately
  permits (`idx_users_email_lower` is not a unique index) and which acceptance criterion 6
  explicitly requires. Add `List<User> findAllByEmailIgnoreCase(String)` and use it; drop the
  `Optional` variant, which currently has no callers.
- `findByIdentifier` + link check in the controller for the lookup: absent user **and** unlinked
  user both raise `NotFoundException` (decision 4).

**Acceptance (AC 4, 5, 6, 7, 8):** ITs with real tokens — a participant not linked to an existing
user gets 404 and a body indistinguishable from the genuinely-absent case; exact-identifier search
hits; a case-varied email search returns every matching user; an empty body yields 400; a
participant passing another participant's `participantIdentifier` still gets only its own linked
users; a catalog token sees across participants.

### Step 5: `POST /participants/me/users` — single registration

- Author `api/components/schemas/UserRegistration.yaml` (`identifier` required and non-blank via
  bean validation, `localIdentifier`, `email`, `firstName`, `lastName` optional) and a small
  registration-result schema carrying the `User` plus the `RegistrationOutcome`, so the body states
  which of create/link/no-op happened as the ticket requires.
- Add the operation inline to `paths:` in `api/openapi.yaml` under
  `security: [{bearerAuth: [PARTICIPANT]}]`, `201` on user creation and `200` otherwise, referencing
  the shared `BadRequest` response.
- Implement `api/ParticipantUserController.java` taking `ParticipantPrincipal` and delegating
  straight to `UserService.registerForParticipant`. The participant comes from
  `principal.participant()` — never from the body.
- **`participantIdentifier` is ignored, not rejected.** Acceptance criterion 14 says such a field is
  *ignored*, so the contract is: the property is simply absent from the `UserRegistration` schema
  and unknown properties are ignored on deserialisation — a client that sends one gets the normal
  `200`/`201` and the field changes nothing. Do **not** set `additionalProperties: false`, which
  would answer `400` instead and invert the criterion. Verify that the generated model really does
  ignore unknown properties under the configured `micronaut-serde-jackson` settings rather than
  failing on them, and state the ignore-don't-reject contract in the operation description.
- Document the integrator-facing trade-off in `docs/` (a short section, referenced from the
  operation description): a participant addresses users by the data space's **global** identifier;
  `localIdentifier` is a convenience column on the caller's own link, never a lookup key for anyone
  else and never part of matching.

**Acceptance (AC 9, 10, 11, 14):** ITs covering the three outcomes end-to-end with their statuses,
an assertion that attributes on a pre-existing user are unchanged after a second participant
registers it with different attributes, and an assertion that an unknown/foreign participant
identifier smuggled into the body changes nothing.

### Step 6: `POST /participants/me/users/bulk` — per-entry outcomes and partial success

- Author `api/components/schemas/BulkUserRegistration.yaml` (`users`: array of `UserRegistration`)
  and `BulkRegistrationResult.yaml` (`results[]` of `{identifier, outcome, reason?}` with
  `outcome ∈ {CREATED, LINKED, ALREADY_LINKED, REJECTED}`, plus a `summary` object counting each).
  Add the operation inline to `paths:` in `api/openapi.yaml`, returning `207`.
- Add `registerBulkForParticipant(Participant, List<UserRegistration>)` to `UserService`: each entry
  is applied in **its own transaction** so one failure cannot roll back the batch, and a failed
  entry is reported as `REJECTED` with a reason rather than aborting.
- **Say how that isolation is obtained, or partial success will not work.** Micronaut's
  transactional AOP is compile-time and bypassed on self-invocation: a loop calling
  `this.registerForParticipant(...)` gets no new transaction, every entry runs in whatever
  transaction the caller is in, and under PostgreSQL one entry's constraint violation aborts that
  transaction and poisons every entry after it — the same failure mode as the insert-and-catch rule
  in Step 2. Use one of exactly two mechanisms: an injected collaborator bean whose method carries
  `@Transactional(propagation = REQUIRES_NEW)`, because crossing a bean boundary is what makes the
  interceptor fire, or programmatic `TransactionOperations.executeWrite` per entry. Catch per entry
  *outside* the per-entry transaction, so a rolled-back entry's exception is translated to
  `REJECTED` on a connection that is still usable. Validation of individual entries
  happens inside the loop, not through bean validation on the collection, because a batch-level
  constraint violation would reject the whole request and defeat partial success.
- Add a configurable maximum batch size to `ConsentManagerConfiguration` (a nested
  `@ConfigurationProperties` for users, e.g. `consent-manager.users.bulk-max-size`, with a named
  default constant — no magic number), wire it in `application.yml` as
  `${USERS_BULK_MAX_SIZE:…}` and document it in `.env.sample` in that file's existing comment style.
  A batch above the maximum is rejected whole with 400, which is a request-level error and therefore
  not a per-entry outcome.
- Confirm in this step whether the generator's `207` handling needs the response wrapper settled in
  Step 3; if `207` is not expressible through the generated signature, return
  `HttpResponse<BulkRegistrationResult>` with the status set explicitly.

**Acceptance (AC 12, 13):** a parameterized IT over a mixed batch (new, existing-unlinked,
already-linked, blank identifier) asserting 207, one result per entry in request order, correct
outcomes and a summary consistent with the results. The rejected entry must sit in the **middle**
of the batch, and the test must assert that the entries after it still succeeded and are visible in
the database — that is the assertion that catches the self-invocation and aborted-transaction
failures above, which a batch with the bad entry last would pass by accident. A batch of `max + 1`
entries yields 400 and writes nothing.

### Step 7: `PATCH` and `DELETE /participants/me/users/{identifier}` — link update and unlink

- Add both operations inline to `paths:` in `api/openapi.yaml` under
  `security: [{bearerAuth: [PARTICIPANT]}]`, referencing `NotFound`, and `Conflict` for the delete.
  `PATCH` takes a body carrying only `localIdentifier` (a dedicated small schema — do not reuse
  `UserRegistration`, whose `identifier` is required) and returns the updated link view.
- `UserService.updateLink(Participant, String identifier, String localIdentifier)` updates only the
  caller's link row and **returns the re-read entity**, never the pre-update object (this is the
  concrete form of resolved bug 17.5). It cannot touch the `users` row. No link → `NotFoundException`.
- `UserService.unlink(Participant, String identifier)` deletes only the link. The `users` row and all
  consents survive — the deletion goes through
  `UserParticipantRepository.deleteByIdUserIdAndIdParticipantId`, never through `UserRepository`.
  Before deleting, reject with `ConflictException` when any consent for that user has status
  `GRANTED` and names the caller as `providerId` or `consumerId`
  (`ConsentRepository.findByUserIdAndStatus(userId, GRANTED)` filtered by participant id). No link →
  `NotFoundException`.

**Acceptance (AC 16, 17):** ITs asserting that after an unlink the user row and its consents are
still present and other participants' links are untouched; that a `GRANTED` consent naming the
caller as provider blocks the unlink with 409 while a `REVOKED` one does not; that a `GRANTED`
consent between two *other* participants does not block it; that `PATCH` on a link the caller does
not hold returns 404; and that the `PATCH` response carries the new `localIdentifier`.

### Step 8: `DELETE /users/me` — erasure with a retained audit trail

- Add the operation inline to `paths:` in `api/openapi.yaml` under `security: [{bearerAuth: [USER]}]`,
  returning
  `200` with an erasure-summary schema (counts of consents revoked and links removed, and the
  pseudonym assigned).
- `UserService.erase(User)` runs the documented policy in one transaction: revoke every `GRANTED`
  consent and append a `CONSENT_REVOKED` `consent_events` row for each; delete every
  `user_participants` row for the user; null out `email`, `first_name`, `last_name`; replace
  `identifier` with a non-reversible pseudonym. Consents and consent events are **retained** —
  `consents.user_id` is `ON DELETE RESTRICT` precisely so that the audit trail cannot be destroyed
  by this path.
- The pseudonym must be unrelated to the original identifier, not derived from it: a hash is
  reversible by dictionary attack over a space of email addresses and subject IDs. Use a freshly
  generated opaque value under a reserved prefix (e.g. `urn:consent-manager:erased:<UUIDv7>`) built
  from the existing `UuidGenerator`, with the prefix as a named constant. It must satisfy
  `uq_users_identifier` and the `NOT NULL` constraint.
- Record the policy as `docs/adr/0007-erasure-pseudonymises-the-user-and-retains-the-audit-trail.md`
  in the style of the existing ADRs: what is erased, what is kept, why keeping the consent record is
  the point of the service, and why the pseudonym is opaque rather than hashed.

**Acceptance (AC 18):** an IT that grants consents across two participants, calls `DELETE /users/me`
with a real `USER` token, then asserts: every previously-`GRANTED` consent is `REVOKED` with a new
`CONSENT_REVOKED` event; the consent and event rows still exist with their original ids; all links
are gone; attributes are null; the identifier no longer matches the original and does not collide;
and a subsequent token for the erased subject provisions a *new* user rather than resurrecting the
old one.

### Step 9: End-to-end authorization sweep, documentation and spec polish

A closing pass that no earlier step can do, because it needs every route to exist.

- Extend the role coverage to the real routes: a parameterized `(route × role × expected status)` IT
  in the shape of `KeycloakRoleMatrixIT` but over the nine operations added here, with real
  Keycloak-issued tokens from `KeycloakAndPostgresTestResource` (AC 20). This is the single place
  that proves no endpoint was left on `IS_AUTHENTICATED` by accident.
- Verify `SpecSecurityConsistencyTest` enumerates **every** one of the nine new operations, not
  merely that it passes: assert the generated case count, since a silently-skipped operation is the
  exact failure decision 5 and the Step 1 guard exist to prevent. Confirm no `api/paths/` directory
  has crept in and no path item is a bare `$ref`.
- Check that Swagger UI still resolves the enlarged `$ref` tree from `/static/openapi.yaml` — the
  `copy-openapi-spec` execution copies `api/**/*.yaml`, so new files under `api/components/` are
  picked up automatically, but the relative `$ref`s from `openapi.yaml` into `components/` and
  between component files must resolve from both the source layout and the copied layout.
- Update `docs/security.md` (the `@Secured` convention section lists `/api-status` as the only
  implemented operation) and `AGENTS.md`: the `service/` and `api/` package contents, the new
  `consent-manager.users.bulk-max-size` property, the erasure ADR, decision 5 (operations stay
  inline in the root spec, and why), and one line naming the package root and the generated
  packages — `com.seamware.consentmanager.api.generated[.model]` under
  `target/generated-sources/openapi/` — since the ticket text misnames the package root as
  `eu.prometheusx.consentmanager` and every controller here imports the generated models.
- Final `./mvnw spotless:apply && ./mvnw verify`.

**Acceptance:** `./mvnw verify` green; every operation in the spec appears in the role matrix with an
explicit expected status for all three roles and for an anonymous request.
