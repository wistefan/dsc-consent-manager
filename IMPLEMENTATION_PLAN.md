# Implementation Plan: Consent Manager - Reimplementation - Consent Querying

## Overview
Add the read/query side of the Consent Manager: seven endpoints for consent listing,
retrieval and validity checking (for data subjects and participants) plus contract
discovery, ISO/IEC 29184 + Kantara consent receipts rendered from the frozen consent
snapshot, detached-JWS receipt signing with a published JWKS, and bounded lineage
traversal. The query API is the service's sole runtime control point — a participant
consults it to answer "may I process this user's data for this purpose right now?" — so
correctness, predicate-level access scoping, and index-backed performance are the
load-bearing requirements.

## Ground Truth (reconciled against the live codebase — read before starting)
- **Package is `com.seamware.consentmanager`**, not the `eu.prometheusx.consentmanager`
  the ticket's "Files to Create" lists. Use the real package.
- **Paths are authored inline in `api/openapi.yaml`.** The ticket lists
  `api/paths/contracts.yaml`; do **not** create it — `SpecReferenceResolutionTest` fails
  the build on any externalised path item (`api/paths/*.yaml`). Only schemas, responses,
  and the security scheme are externalised under `api/components/`.
- **`SpecSecurityConsistencyTest.SPECIFIED_OPERATION_COUNT` is `26`** today
  (`src/test/java/com/seamware/consentmanager/api/SpecSecurityConsistencyTest.java:87`),
  not the `21` AGENTS.md states. This ticket adds 8 operations → final value **34**
  (the per-step bumps run 26→28→30→31→33→34: Step 1 +2, Step 2 +2, Step 3 +1, Step 4 +2,
  Step 6 +1). Bump it in the same commit as each operation's spec addition, and add the
  matching `EndpointRoleMatrixIT.Endpoint` row + `roleMatrix()` row for every new operation,
  or both tests fail the build.
- **Foundation already present on the base branch:** `domain/Consent` (with the frozen
  `snapshot` JSONB column + `domain/ConsentSnapshot` record and its
  `PrivacyNoticePayload` vocabulary), `domain/ConsentStatus`,
  `repository/ConsentRepository` (`PageableRepository`, with paginated
  `findByUserId`/`findByProviderId`/`findByConsumerId`, status-filtered variants,
  `findByUserIdAndPrivacyNoticeIdForUpdate`, and the TICKET-002 indexes
  `idx_consents_user_id_status`, `idx_consents_provider_id`, `idx_consents_consumer_id`,
  `idx_consents_privacy_notice_id`), `client/ContractGateway` + `ContractServiceClient`
  (`fetchContract`, `fetchBilateralContract`) with `client/dto/ContractResponse`.
- **No outbound signing infrastructure exists.** Everything under `security/` is inbound
  IdP token *verification* (`JwksSignatureConfigurationAdapter`, `IssuerSignatureVerifier`,
  `ConsentManagerTokenValidator`). There is no private key, no `.well-known` endpoint this
  service publishes, and no code that signs an outbound payload. Receipt JWS signing
  (TICKET-011) is therefore greenfield and is built as its own step here (Step 6).
- **Pagination convention:** there is no generic `Page` schema. `GET /participants` uses an
  inline `ParticipantPage` wrapper in `api/openapi.yaml` (`content[]`, `page`, `size`,
  `totalElements`, `totalPages`) with `page`/`size` query params, clamping an oversized
  `size` to a **configured per-deployment ceiling** rather than rejecting — the property
  `consent-manager.participants.page-max-size` (env `PARTICIPANTS_PAGE_MAX_SIZE`, default
  `100` at `application.yml`), with **no `maximum` in the spec** so the YAML never drifts from
  the configured value. Mirror this convention for consents: a sibling config property under
  `consent-manager` (default documented in `.env.sample` + `application.yml`), clamp-not-reject,
  the response reporting the size actually applied, and no `maximum` literal in the spec.
  Do **not** bake a magic `100`/`200` into either spec or code.
- **Controller convention:** concrete `@Controller` extends the generated
  `Abstract<Tag>Controller`; the principal (`UserPrincipal` / `ParticipantPrincipal`) is the
  handler's first parameter; handlers return `HttpResponse<GeneratedModel>`; errors are
  thrown as `NotFoundException`/`BadRequestException`/`UpstreamServiceException` (RFC 7807).

## Steps

### Step 1: Query DTOs, pagination scaffolding, and data-subject listing/retrieval (`GET /consents/me`, `GET /consents/me/{id}`)
Establish the query foundation and ship the two USER-facing read endpoints.

- **Spec (`api/openapi.yaml` + `api/components/schemas/`):**
  - Add `ConsentResponse.yaml` — the full consent representation returned by queries
    (id, status, privacyNoticeId, providerId, consumerId, parentConsentId, contractUri,
    createdAt, updatedAt, and the snapshot-derived scope). This is the `format=full` shape.
  - Add an inline `ConsentPage` wrapper in `api/openapi.yaml` mirroring `ParticipantPage`
    (`content[]` → `$ref ConsentResponse`, `page`, `size`, `totalElements`, `totalPages`).
    Author it **inline in `openapi.yaml`** with `items` an internal
    `#/components/schemas/ConsentResponse` ref — the only item-ref form proven to generate a
    typed `List<ConsentResponse>` rather than `List<Object>`. Assert the generated field
    type before relying on it.
  - Add query params `page`, `size`, `status` (repeatable), `privacyNoticeId`,
    `format` (`full`|`receipt`|`receipt-kantara`, default `full`), `includeLineage`
    (boolean, default `false`). `format`'s receipt values and `includeLineage` are accepted
    now but only `full`/no-lineage behaviour is implemented here; receipt rendering lands in
    Step 5, lineage in Step 7. (Author the enum with all three format values up front so the
    generated model is stable across steps.)
  - `GET /consents/me`: `x-roles: [USER]`, `x-principal: UserPrincipal`,
    `security: [{bearerAuth: [USER]}]`, `200` → `ConsentPage`, shared 400/401/403/404 refs +
    `default` → `ProblemDetail`.
  - `GET /consents/me/{id}`: same auth, `200` → `ConsentResponse`, `404` when the consent is
    unknown **or not owned by the caller** (no distinction — do not leak existence).
  - Bump `SPECIFIED_OPERATION_COUNT` 26 → 28.
- **Repository:** add a paginated query filtering by `userId` + optional `status IN (...)`
  + optional `privacyNoticeId`, backed by `idx_consents_user_id_status`. The page's
  `totalElements` must come from the **same predicate** as the content (fixes original bug
  18.3: the original counted the whole collection and reported it as the user's total).
- **Service (`service/ConsentQueryService.java`):** `listForUser(UserPrincipal, criteria)`
  returning a domain page, and `getForUser(UserPrincipal, id)` returning the owned consent or
  raising `NotFoundException`.
- **Mapper:** extend/author a `ConsentMapper` method `toResponse(Consent)` →
  `ConsentResponse`.
- **Controller (`api/ConsentQueryController.java`):** `@Controller` extending the generated
  abstract for the new `Consents`/query tag; wire both handlers.
- **Tests:** `EndpointRoleMatrixIT` rows for both new operations (USER allowed,
  PARTICIPANT/CATALOG/OUTSIDER/anonymous refused); a service/repository test proving the
  total matches the filtered predicate; an IT proving `GET /consents/me/{id}` returns 404 for
  a consent owned by another user. Parameterized where the role/ownership axes repeat.
- **Acceptance:** filtered-total correct; cross-user `me/{id}` → 404.

### Step 2: Participant-scoped querying (`GET /consents`, `GET /consents/{id}`)
Ship the service's primary integration point with access control enforced in the query
predicate.

- **Spec:** add `GET /consents` (`x-roles: [PARTICIPANT]`,
  `x-principal: ParticipantPrincipal`) with query params `userIdentifier` (optional),
  `privacyNoticeId` (optional), `status` (repeatable, **default `GRANTED`**), `page`, `size`;
  `200` → `ConsentPage`. Add `GET /consents/{id}` (same auth), `200` → `ConsentResponse`,
  `404` unless the caller is provider or consumer. Bump `SPECIFIED_OPERATION_COUNT` 28 → 30.
- **Repository:** add a paginated query that scopes rows to
  `provider_id = caller OR consumer_id = caller`, with optional `userId` (resolved from
  `userIdentifier`), optional `privacyNoticeId`, and `status IN (...)`. Scoping is **in the
  predicate**, never post-filtered — there must be no window where another participant's row
  is loaded. Backed by `idx_consents_provider_id` / `idx_consents_consumer_id`.
- **Service:** resolve `ParticipantPrincipal` via `requireRegistered()` (reading the caller's
  own scope; `CallerScope`). Resolve `userIdentifier` → user id; **if the caller is not
  linked to that identifier, return an empty page — never 403** (a 403 would let a
  participant probe which identifiers exist). `getForParticipant(principal, id)` returns the
  consent only when the caller is provider or consumer, else `NotFoundException`.
- **Tests:** `EndpointRoleMatrixIT` rows (both are PARTICIPANT-only, so both are swept by
  `participantScopedEndpoints` → must 403 for an unregistered participant). ITs: a
  participant sees only consents where it is provider/consumer; an unlinked `userIdentifier`
  yields an empty page (status 200); `GET /consents/{id}` for a consent the caller is not a
  party to → 404. Parameterized `(caller role × party-or-not × expected status)` matrix.
- **Acceptance:** predicate-level scoping; unlinked identifier → empty page not 403;
  non-party `/consents/{id}` → 404.

### Step 3: Validity check (`GET /consents/check`)
A purpose-built, cache-hostile yes/no the participant asks before processing.

- **Spec:** add `ConsentValidity.yaml` (`valid` boolean, `consentId` nullable-absent,
  `status`, `grantedAt`, `purposes[]`, `checkedAt`, and a `reason` for the false cases).
  Add `GET /consents/check` (`x-roles: [PARTICIPANT]`,
  `x-principal: ParticipantPrincipal`) with **required** `userIdentifier` and
  `privacyNoticeId`, optional `purpose`; `200` → `ConsentValidity`. Bump
  `SPECIFIED_OPERATION_COUNT` 30 → 31.
- **Service (`ConsentQueryService`):** a single indexed lookup on
  `(user_id, privacy_notice_id)` restricted to GRANTED. `valid = true` only when a GRANTED
  consent exists for that (user, notice) **and**, if `purpose` is supplied, the consent's
  snapshot `purposes` cover it. Every other case → `valid: false` with a `reason`
  (including "no consent found"), and the response must reveal **nothing** about whether the
  user exists — an unknown user and a known user with no consent return the same shape and
  status. Scope the lookup to the calling participant (provider or consumer) so one
  participant cannot read another's grant.
- **Controller:** return `HttpResponse.ok(body).header("Cache-Control", "no-store")` — a
  stale "yes" is the worst possible answer.
- **Tests:** `EndpointRoleMatrixIT` row (PARTICIPANT-only, participant-scoped). ITs: GRANTED
  → `valid:true`; REVOKED/absent → `valid:false`; `purpose` outside the consent's scope →
  `valid:false`; unknown user vs known-but-no-consent are indistinguishable; response carries
  `Cache-Control: no-store`. Parameterized over status × purpose-coverage.
- **Acceptance:** all five `/consents/check` acceptance criteria.

### Step 4: Contract discovery (`GET /contracts/available`, `GET /contracts/available/{role}`)
Resolve the caller's participant links and list bilateral + ecosystem contracts, marking the
caller's side.

- **Source of the contract set (resolves the Step-4 feasibility gap).** The Contract Service
  client cannot enumerate: `ContractServiceClient` exposes only `fetchContract(id)`
  (`GET /contracts/{id}`) and `fetchBilateralContract(id)` (`GET /bilaterals/{id}`), and
  `ContractGateway.fetch(uri)` wraps a single by-id lookup — there is **no** "list all
  contracts this participant is party to" upstream call. Discovery therefore draws its
  **candidate set of contract URIs from this service's own data**: the `contract_uri` column,
  carried on both `privacy_notices` (`NOT NULL`, `V1__initial_schema.sql:72`) and `consents`
  (nullable, `:107`), scoped to the caller's participant via `provider_id`/`consumer_id`.
  `privacy_notices.contract_uri` is the primary source (always present); `consents.contract_uri`
  is a secondary source for contracts under which a consent exists. **New repository capability
  required:** neither `PrivacyNoticeRepository` nor `ConsentRepository` exposes a distinct-URI
  projection today — add a `SELECT DISTINCT contract_uri ... WHERE provider_id = :id OR
  consumer_id = :id` query (primarily on `privacy_notices`) returning the distinct URIs for a
  participant. The flow is then: resolve the caller's participant(s) → gather distinct
  `contract_uri`s for each from the repository → hydrate each via `ContractGateway.fetch(uri)`
  → mark the caller's side. (If a future Contract Service listing endpoint lands, the candidate
  set can switch to it without changing the response shape.)
- **Spec:** add `AvailableContract.yaml` (contract id/uri, provider, consumer, shape
  bilateral/ecosystem, and which side the caller's participant(s) are on). Add
  `GET /contracts/available` (`x-roles: [USER]`, `x-principal: UserPrincipal`) and
  `GET /contracts/available/{role}` (`x-roles: [PARTICIPANT]`,
  `x-principal: ParticipantPrincipal`) with `{role}` a path var; `200` → list of
  `AvailableContract`. Add a shared `502`/Bad Gateway response ref (reuse
  `UpstreamServiceException`'s existing mapping). Bump `SPECIFIED_OPERATION_COUNT` 31 → 33.
- **Service (`service/ContractDiscoveryService.java`) + controller
  (`api/ContractDiscoveryController.java`):**
  - User endpoint: resolve the participants the caller is linked to, fetch bilateral +
    ecosystem contracts for each via `ContractGateway`, **deduplicate**, mark the caller's
    side.
  - Participant endpoint: `{role}` must be `provider` or `consumer`; any other value →
    `BadRequestException` (400). Return contracts where the caller participates in that role.
  - **An unavailable Contract Service → 502 with no partial list.** If any required gateway
    call fails, raise `UpstreamServiceException`; never return a truncated set (a partial
    list reads as "these are all of them").
- **Tests:** `EndpointRoleMatrixIT` rows (user endpoint USER-only; role endpoint
  PARTICIPANT-only → participant-scoped). WireMock-backed ITs: dedup across participants;
  side marking; `{role}=garbage` → 400; Contract Service down → 502 and no body list.
- **Acceptance:** `{role}` validation → 400; upstream down → 502 with no partial list.

### Step 5: Consent receipts — ISO/IEC 29184 + Kantara (`format=receipt`, `format=receipt-kantara`)
Render a receipt from the frozen snapshot so history is never rewritten.

- **Spec:** author the receipt response schemas
  (`api/components/schemas/ConsentReceipt.yaml` for ISO/IEC 29184 and
  `ConsentReceiptKantara.yaml` for KI-CR-v1.1.0) from typed fields that mirror the
  `ConsentSnapshot` record — so a field that does not exist is a **compile error**, not a
  runtime one (fixes original bug 18.5). The `me`, `me/{id}`, and participant `GET /consents`
  / `/consents/{id}` operations gain `format=receipt`/`receipt-kantara` behaviour; the
  response content type for a receipt is negotiated by `format` (schema `oneOf` or a distinct
  media shape — verify generation produces usable types before committing the schema shape).
- **Service (`service/ConsentReceiptService.java`):** `toIso29184(Consent)` and
  `toKantara(Consent)` reading **exclusively from `consent.snapshot`** (title, purposes,
  dataCategories, dataReferences, recipients, controllerDetails, retentionPeriod,
  piiPrincipalRights, internationalTransfers, withdrawalOfConsent) plus
  `consent.contractUri`/timestamps — never from the live privacy notice. Map snapshot fields
  to the two receipt vocabularies.
- **Wiring:** `ConsentQueryController` selects the receipt renderer when `format != full` for
  the single-consent reads; decide and document whether list endpoints support receipt format
  (ticket implies single-consent receipts; keep list `format` to `full` unless a receipt list
  is explicitly required, and reject an unsupported combination with 400).
- **Tests:** a receipt rendered from a consent is **unchanged after its privacy notice is
  archived and regenerated** (the defining snapshot test); ISO/IEC 29184 field coverage;
  Kantara KI-CR-v1.1.0 structure. (JWS signature assertions come in Step 6.)
- **Acceptance:** `format=receipt` → valid 29184; `format=receipt-kantara` → valid Kantara;
  receipt stable across notice regeneration.

### Step 6: Detached-JWS receipt signing + published JWKS (`GET /.well-known/jwks.json`)
Greenfield outbound-signing support (TICKET-011 is absent from the codebase) so every receipt
is verifiable.

- **Key material & config:** add a signing key (RSA or EC) loaded from configuration/secret
  (document new env vars in **both** `.env.sample` and `application.yml`; never hardcode a
  key). Add a `security/ReceiptSigner` producing a **detached JWS** over the canonical receipt
  bytes, and a JWKS projection of the public key.
- **Endpoint:** add `GET /.well-known/jwks.json` — public (`security: []`) — returning the
  published JWKS. Author it in the spec (`200` → a JWKS schema) and bump
  `SPECIFIED_OPERATION_COUNT` 33 → 34, with an `EndpointRoleMatrixIT` row marking it
  anonymous-allowed. (If authoring a `.well-known` path through the generator proves
  impractical, fall back to a plain `@Controller` and document why it is spec-exempt — but
  prefer the spec-first route to keep the consistency tests total.)
- **Wiring:** `ConsentReceiptService` attaches the detached JWS to each rendered receipt
  (both formats).
- **Tests:** every receipt carries a JWS signature that **verifies against the key published
  at `GET /.well-known/jwks.json`**; a tampered receipt fails verification; the JWKS endpoint
  is reachable anonymously.
- **Acceptance:** every receipt carries a JWS verifiable against the published JWKS.
- **Note:** if the orchestrator decides receipt signing belongs wholly to TICKET-011, this
  step may be descoped to only the cross-ticket dependency flag — but the acceptance criteria
  require it, so the default is to build the minimal signer + JWKS here.

### Step 7: Lineage traversal, performance guarantees, and pagination-boundary tests
Complete `includeLineage`, prove index usage, and cover paging edges.

- **Lineage (`service/ConsentLineageService.java` or a method on `ConsentQueryService`):**
  when `includeLineage=true`, walk `parentConsentId` upward from the requested consent and
  return the chain **newest-first**. The walk is bounded by a **configurable maximum depth**
  (default `20`, as a named config property documented in `.env.sample` + `application.yml`,
  never a magic literal) **and** by a visited-ID set, so a cycle — which
  `consents_parent_not_self` constrains but does not fully prevent across rows — terminates
  rather than hanging. Each hop is access-checked the same way the entry point is (a user sees
  only their own chain; a participant only consents it is a party to). Wire `includeLineage`
  into the `me/{id}` and participant `/consents/{id}` responses (and `me`/`/consents` list
  entries if specified).
- **Performance:** add an IT that seeds a dataset and asserts the consent list query and the
  validity-check query use an **index scan, not a sequential scan** (`EXPLAIN` against the
  Testcontainers Postgres, asserting the plan mentions the relevant `idx_consents_*`). The
  validity check must resolve in a single indexed lookup.
- **Pagination boundaries:** ITs covering empty result, single page, exact multiple of page
  size, and a partial last page — asserting `page`/`size`/`totalElements`/`totalPages` for
  each, and that an oversized `size` is clamped (not rejected) consistent with the
  `ParticipantPage` convention.
- **Acceptance:** lineage newest-first; terminates at configured depth and on a cycle;
  queries use index scans; pagination boundaries covered.

### Step 8: Documentation and consistency sweep
Reconcile docs and the recorded conventions with what shipped.

- Update `AGENTS.md`: correct the stale `SPECIFIED_OPERATION_COUNT` note (now **34**), add
  the query endpoints, the receipt/JWS/JWKS additions, and the lineage depth config to the
  conventions and important-files sections.
- Add an ADR under `docs/adr/` recording the query-side access model (predicate-level
  scoping, empty-page-not-403 for unlinked identifiers, `no-store` on validity, receipts from
  the frozen snapshot, and the detached-JWS/JWKS decision).
- Document every new config key in `.env.sample` and `application.yml`.
- Run `./mvnw spotless:apply` then `./mvnw verify` and ensure the full suite (including
  `SpecSecurityConsistencyTest`, `EndpointRoleMatrixIT`, and the new ITs) is green.
