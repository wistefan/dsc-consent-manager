# Implementation Plan: Consent Manager - Reimplementation - Privacy Notice

Taiga ticket #70 (TICKET-007). Depends on TICKET-002 (schema), TICKET-003 (token validation)
and TICKET-005 (participant management), all merged into `main`.

## Overview

Add the privacy-notice module: generate a notice from a contract held by the Contract
Service, archive a notice the contract has superseded, and serve a notice with its external
references resolved. Three operations — `POST /privacy-notices`,
`POST /privacy-notices/by-contract` and `GET /privacy-notices/{id}?resolve=` — plus a
declarative `ContractServiceClient` (bounded timeouts, retry on 5xx and timeouts only, a
Caffeine-cached read), a `SelfDescriptionClient` for the catalog documents a contract points
at, and a `ContractContentExtractor` that turns a contract into the stored notice payload.

Privacy notices are the bridge between contracts (owned by the Contract Service) and consents
(owned by this service). Everything here is spec-first: `api/openapi.yaml` and
`api/components/schemas/` are authored by hand, controllers implement the generated abstract
classes, and the behaviour lives in `PrivacyNoticeService`.

### What already exists, and what this ticket must not re-do

TICKET-002 landed the whole persistence side, so **no Flyway migration is needed**:

- `privacy_notices` (`V1__initial_schema.sql:70`) with `contract_uri`, `title`, `provider_id`,
  nullable `consumer_id`, `payload JSONB NOT NULL DEFAULT '{}'`, `archived_at`, `created_at`,
  `updated_at`; both participant foreign keys are `ON DELETE RESTRICT`.
- `uq_privacy_notices_active_contract ON (contract_uri, provider_id) WHERE archived_at IS NULL`
  — **AC 6 is already enforced by the database**; this ticket only has to lose the race
  gracefully rather than invent the guarantee.
- `domain/PrivacyNotice`, `domain/PrivacyNoticePayload`, `repository/PrivacyNoticeRepository`
  (which already has `findByContractUriAndProviderIdAndArchivedAtIsNull`).

TICKET-003 landed the typed principals, TICKET-005 landed
`ParticipantRepository.findByIdentifier` and `ParticipantPrincipal.requireActive()`.
`UpstreamServiceException` → `ProblemType.UPSTREAM_SERVICE` → `502` already exists and is
already pinned by `ProblemMappingTest`. `micronaut-http-client`, `micronaut-cache-caffeine`
and `wiremock-standalone` are already dependencies; **`micronaut-retry` is not** and Step 1
adds it.

### Decisions carried in from the analysis comment

1. **A contract party is a global participant identifier**, resolved with
   `ParticipantRepository.findByIdentifier`. No `selfDescriptionURL` matching, no fallback
   lookup, and **nothing is base64-encoded anywhere** — not on this service's own API and not
   on the calls it makes to the Contract Service. An unresolvable party is `404` on the
   generation paths; on `GET /privacy-notices/{id}?resolve=true` it degrades the affected
   section to `UNAVAILABLE` rather than failing the request.
2. **`contractUri` is persisted verbatim** in `privacy_notices.contract_uri`. It is the key of
   the partial unique index, so normalising it would silently split the uniqueness guarantee.
   The upstream contract id is *derived* from it for the fetch (last path segment, or the whole
   value when it carries no `/`), and the derivation never touches what is stored.
3. **Both contract shapes are supported** behind one `ContractResponse`: the ecosystem contract
   (`GET /contracts/{id}`) is tried first and the bilateral contract (`GET /bilaterals/{id}`) is
   the fallback. Unknown on both → `404`; unreachable → `502`, on generation only.
4. **The four fields with no source in the contract** are left **absent** rather than empty.
   Map only what the contract actually carries; do not emit `""` or `[]` and do not fill them
   from configuration. Absent is the honest encoding and matches the repo's global
   `serde.serialization.inclusion: non-absent`.

### Decisions this plan adds, with their reasons

These are not in the ticket text. Each is called out so a reviewer can overrule it cheaply.

5. **The contract schema is known, and it has no `title`.** The upstream Contract Service is
   the Prometheus-X contract manager. Its ecosystem contract carries
   `uid`/`_id`, `profile`, `ecosystem`, `orchestrator`, `serviceOfferings[]`
   (`participant`, `serviceOffering`, `policies[]`), `rolesAndObligations[]`,
   `dataProcessings[]`, `purpose[]`, `members[]` (`participant`, `role`, `signature`, `date`),
   `revokedMembers[]`, `status`, `jsonLD`, `createdAt`, `updatedAt`. Its bilateral contract
   carries `uid`/`_id`, `dataProvider`, `dataConsumer`, `serviceOffering`, `profile`,
   `policy[]`, `purpose[]`, `signatures[]`, `revokedSignatures[]`, `negotiators[]`, `status`,
   `terminationAndValidity` (`effectiveDate`, `terminationPeriod`), `limitationOfLiability`,
   `termsAndConditions`, `jsonLD`, `createdAt`, `updatedAt`.

   **Neither shape has a `title`.** The ticket's mapping table says `title` ← "Contract title",
   and there is no such field. `title` is therefore *derived* by a documented precedence —
   bilateral: `serviceOffering`, then `uid`/`_id`; ecosystem: `ecosystem`, then `uid`/`_id` —
   and left `null` when none is present. The precedence is pinned by the Step 2 matrix rather
   than left to whoever reads the extractor next.

6. **`purposes` comes from `purpose[]`, not from the policies, when `purpose[]` is present.**
   The ticket's table says "Service offering policies", which was written before the schema was
   known. Both contract shapes carry a dedicated `purpose[]` array whose entries are literally
   GDPR purposes (`purpose`, `purposeCategory`, `piiCategory`, `consentType`, `termination`,
   `thirdPartyDisclosure`, `thirdPartyName`). A privacy notice that described ODRL permissions
   instead of those would be a worse notice. Precedence: `purpose[]` entries first (`purpose`,
   falling back to `uid`); when `purpose[]` is absent or empty, the policy descriptions
   (`serviceOfferings[].policies[].description` / bilateral `policy[].description`). Both
   branches are pinned by the matrix.

7. **Generation stores what the contract itself says; resolution fills in what other documents
   say.** The contract names a service offering and its data resources only by catalog URI. A
   notice must still be generatable when the catalog is down — the ticket is explicit that the
   *only* hard failure is an unreachable Contract Service. So the payload persisted at
   generation holds the raw identifiers and the contract's own text, and
   `GET /privacy-notices/{id}?resolve=true` is the step that dereferences the catalog
   documents. This is also what makes AC 9 reachable at all: an unavailable external document
   can only degrade a response if the response is where it was going to be used.

8. **Resolving catalog documents needs a second client, two hops, and a relaxable SSRF guard.**
   AC 8 requires `dataResources` to resolve, and a data resource is an absolute URL taken from a
   document this service does not own. The ticket's "Files to Create" lists only
   `ContractServiceClient`, so `client/SelfDescriptionClient.java` is an addition. It fetches an
   absolute URL through the low-level `HttpClient`, with the same timeouts and its own Caffeine
   cache — read through an injected `SyncCache` and written only on a successful parse, for the
   reason decision 13 gives, which binds harder here because this client's empty result can mean
   a transient failure rather than "unknown".

   **Two hops, because a contract names offerings and not resources.** The payload's `data[].uri`
   holds *service-offering* URIs — that is all a contract carries. The data resources are one hop
   further: the offering's self-description → its `dataResources[]` → each resource's own
   self-description, which is where a resource title and description actually live. Resolution
   walks both hops. Stopping at the offering and reporting `RESOLVED` would claim AC 8 while
   serving offering-level text, so the section reports `RESOLVED` only when both hops completed
   for every entry.

   **The guard, and the single way it can be relaxed.** The scheme must be `https` (`http` only
   when `allow-plaintext-http` is set), the host must match `allowed-hosts` when that list is
   non-empty, and a loopback, link-local or private-range address is refused **unless the host
   appears verbatim in `allowed-hosts`** — an explicit allow-list entry is an operator decision
   and outranks the address heuristic. That exception is not a convenience. The only stub
   mechanism in this repo is WireMock, which binds on `127.0.0.1`, so without it every
   resolution test in Step 5 would be asserting the guard rather than the feature, and the
   contradiction would only surface at the end of the step. A deployment that leaves
   `allowed-hosts` empty still refuses every private address. The client never throws out of the
   service: every failure, refusal included, becomes `Optional.empty()` and therefore an
   `UNAVAILABLE` section.

9. **`PrivacyNoticePayload` is renamed to the ticket's vocabulary, and that costs nothing.**
   The record TICKET-002 landed uses `dataCategories`, `controllerName`/`controllerContact` and
   `withdrawalMethod`; the ticket's mapping table names `categoriesOfData`, `controllerDetails`
   and `withdrawalOfConsent`, and adds `data`, which has no counterpart at all. Nothing in
   `src/main` writes a privacy notice today — this ticket is the table's **first writer**, so
   there are no rows in any deployment and no JSONB migration to perform. Keeping two
   vocabularies would mean the stored form and the served form of a published legal document
   disagree on field names, and TICKET-008's `ConsentSnapshot` would then have to pick one.
   Align now: rename the three components, add `data`, and fold `controllerName`/
   `controllerContact` into a nested `ControllerDetails` record so the provider's name and
   contact travel together. **`piiPrincipalRights` also changes type**, `List<String>` →
   `String`: its only source in either contract shape is `termsAndConditions`, one block of free
   text, and splitting that into a singleton list would dress a paragraph up as an enumeration
   that no caller can rely on. It therefore matches `withdrawalOfConsent`, which is a `String`
   already and comes from the same field. The **six** test files that construct a
   `PrivacyNoticePayload` positionally are updated in the same commit.

10. **The `/for/{participantIdentifier}` client methods are not declared.** The ticket's
    snippet lists `fetchContractsFor` and `fetchBilateralsFor`, but none of this ticket's three
    flows calls them. Declaring them would add two untested methods with no caller. They are
    omitted; TICKET-008/009 can add them when a flow needs them, at which point they can also
    be tested. Reversible in one commit if a reviewer disagrees.

11. **`GET /privacy-notices/{id}` is not scoped to the parties.** The ticket grants `USER`,
    `PARTICIPANT` and `CATALOG` with no further condition, and a user must be able to read the
    notice they are being asked to consent to before any link to it exists. No party filter is
    added. `x-principal` is therefore `ConsentManagerPrincipal`, the widest type, which is what
    `SpecSecurityConsistencyTest` requires of an operation admitting all three roles.

12. **Generation uses `requireActive()`, retrieval uses the principal as given.** Generation
    acts in the dataspace in the participant's name, which is the condition
    `ParticipantUserController` already uses `requireActive()` for; a deregistered participant
    must not be able to mint new notices. Retrieval reads, and needs no row at all.

13. **A negative contract lookup is never cached, which rules `@Cacheable` out of the gateway.**
    Micronaut's `@Cacheable` has no `unless`/`condition` attribute, so it would store a returned
    `Optional.empty()` like any other value: a contract created upstream one second after a
    `404` would stay invisible for the whole `cache-ttl`, and nothing in the service could tell
    the difference between "unknown" and "unknown an hour ago". `ContractGateway` therefore uses
    the injected `SyncCache` directly — read through on every call, `put` **only** on a hit —
    and neither an empty result nor a failure is written. The cache annotation stays off the
    `@Client` interface for the separate reason given in Step 1. `ContractCacheIT` asserts the
    two cases apart: a hit is served from the cache, and a `404` followed by an upstream create
    is visible on the very next call.

14. **One active notice per `(contractUri, provider)` is the schema's guarantee, and the parties
    are validated against the contract.** `uq_privacy_notices_active_contract` is keyed on
    `(contract_uri, provider_id)` and nothing else, so the table cannot hold two active notices
    for one contract and provider even when they name different consumers. Generation therefore
    also **verifies that both declared parties are named in the fetched contract** (Step 3,
    point 3, the membership check) — without it the request body could name any consumer, and the
    stored notice would misname a party in a published legal document. With the check in place,
    a `consumerId` change can only come from the contract itself changing, which is exactly the
    archive-and-recreate AC 5 asks for. The one residual case is an ecosystem contract naming
    more than one possible consumer against the same provider: a notice for consumer B
    supersedes the one for consumer A, and two consumers regenerating in turn would archive each
    other. That is a property of the index this ticket inherits, not of the comparison, and
    widening it to `(contract_uri, provider_id, consumer_id)` is a migration against TICKET-002's
    schema; it is named here rather than silently absorbed, and Step 3's IT pins the behaviour
    so a later ticket can change it deliberately.

### Repo-specific constraints every step inherits

- **`api/paths/privacy-notices.yaml` must not be created.** The ticket's file list names it,
  but `SpecReferenceResolutionTest` fails the build on an externalised path item: path items
  are inline in `api/openapi.yaml` and only schemas, responses and security schemes live under
  `api/components/`.
- **The package is `com.seamware.consentmanager`**, not the `eu.prometheusx.consentmanager` the
  ticket's file list names.
- **Spec and implementation land in the same step.** `SpecSecurityConsistencyTest` fails the
  build when a specified operation has no implementing route, so a spec-only step cannot merge.
- **Every step that adds an operation raises `SPECIFIED_OPERATION_COUNT` in the same commit.**
  It reads `16` today (`SpecSecurityConsistencyTest.java:87`) and the test asserts the parsed
  operation list `hasSize(SPECIFIED_OPERATION_COUNT)`. Step 3 `+1` → `17`, Step 4 `+1` → `18`,
  Step 5 `+1` → `19`. **The expected end state is 19.**
- Every new operation declares `security`, `x-roles` and `x-principal` consistently, `$ref`s the
  shared `401` and `403` responses, and carries the inline `default` problem response.
- Operations are tagged `Privacy Notices`, which makes the generator emit
  `AbstractPrivacyNoticesController`.
- **A schema whose `items:` `$ref`s another named model must be written inline in
  `openapi.yaml`** — a cross-file `$ref` under `items` degrades to `List<Object>`. Whether
  object items declared inline *within* a single component file escape that is **unverified**:
  no schema under `api/components/schemas/` declares one today, and the only form this repo has
  proven is `BulkUserRegistration`/`ParticipantPage` — the wrapper inline in `openapi.yaml`, its
  `items` an internal `#/components/schemas/...` ref. `PrivacyNotice.yaml`'s `recipients` and
  `data` would be the first to try the inline form, so Step 3 **probes the generated field types
  before committing to it** and falls back to the proven form if either degrades; Step 6's
  parity test then holds the outcome.
- **No response property is `nullable`** — a nullable response property forces `JsonNullable`
  onto the model. Optional means absent.
- `./mvnw spotless:apply` before every commit; `./mvnw verify` must pass.
- Doc comments stay terse: one line on what a type or public method is and why, a second only
  for a contract the signature cannot show. No `@param`/`@return` blocks echoing the signature.
  Named constants, never magic values.

## Steps

### Step 1: Contract Service client — timeouts, bounded retry, cached reads

Covers AC 12 and AC 13 at the client level. No API surface changes, so
`SPECIFIED_OPERATION_COUNT` stays at `16`.

**Build**

- `pom.xml` — add `io.micronaut:micronaut-retry` (compile). `@Retryable` lives there and the
  module is not on the classpath today. `micronaut-http-client` and `micronaut-cache-caffeine`
  are already present and need no change.

**Configuration**

- `config/ConsentManagerConfiguration.java` — replace the flat `contractServiceUrl` field with
  a nested `@ConfigurationProperties("contract-service")` class, matching the `Users`,
  `Erasure` and `Participants` precedent and the `consent-manager.contract-service.url` key the
  ticket names. It holds `url`, `connectTimeout`, `readTimeout`, `retryAttempts`, `retryDelay`,
  `retryMultiplier` and `cacheTtl`, each defaulted from a named `DEFAULT_*` constant. Keeping
  both `contract-service-url` and a `contract-service` block would leave two keys for one
  setting and invite relaxed-binding ambiguity; the **env var `CONTRACT_SERVICE_URL` is
  unchanged**, so no deployment has to move. Nothing in `src/main` reads the old getter today —
  only its own javadoc does — so the rename has no call sites to fix.
- `src/main/resources/application.yml` — move line 312 under a `contract-service:` block, add
  the new keys with their env-var indirections, and add a `micronaut.caches.contracts` entry
  next to the existing `jwks` one (`expire-after-write`, `maximum-size`).
- `.env.sample` — document every new variable in the existing comment style, next to the
  `CONTRACT_SERVICE_URL` entry at line 50.

**Implementation**

- `client/dto/ContractResponse.java` — one `@Serdeable` record covering both contract shapes,
  with nested records for `ServiceOffering`, `Policy`, `Purpose`, `Member`, `Signature` and
  `TerminationAndValidity`. Every component is `@Nullable`: a bilateral contract has no
  `members`, an ecosystem contract has no `termsAndConditions`, and the serialized form omits
  unset fields. It accepts **both `uid` and `_id`** for the identifier, because the schema
  declares `uid` while the observed wire form carries mongo's `_id`. Unknown properties are
  ignored (Micronaut Serde's default), which is what lets the DTO survive an upstream addition.
  `ContractResponse` exposes `contractId()` and `shape()` (`ECOSYSTEM` / `BILATERAL`) so callers
  do not re-derive either.
- `client/ContractServiceClient.java` — `@Client("${consent-manager.contract-service.url}")`
  declaring exactly two methods:
  `Optional<ContractResponse> fetchContract(String contractId)` on `GET /contracts/{contractId}`
  and `Optional<ContractResponse> fetchBilateralContract(String contractId)` on
  `GET /bilaterals/{contractId}`. The `Optional` return type is load-bearing: Micronaut maps a
  `404` to an empty `Optional` rather than an exception, which is exactly the "unknown
  contract" case, and it keeps a `404` from ever looking like an outage. Identifiers travel as
  ordinary path variables and Micronaut URL-encodes them; nothing is base64-encoded.
  `@Retryable` on the interface carries the configured attempts, delay and multiplier and a
  `predicate`.
- `client/UpstreamRetryPredicate.java` — the `RetryPredicate` implementing AC 13: retry a
  `HttpClientResponseException` only when its status is 5xx, retry a `ReadTimeoutException` /
  `HttpClientException` caused by a connect or read timeout, and **return false for every 4xx**.
  A `404` already became an empty `Optional` and never reaches the predicate; a `400` or `403`
  is a defect that retrying cannot fix and would only triple the upstream load.
- `client/ContractGateway.java` — `@Singleton`, the bean the services actually depend on, and
  the only place caching happens. The cache is **not** on the `@Client` interface: `@Cacheable`
  is around-advice and `@Client` is introduction advice, and stacking the two on one interface
  makes the applied order an implementation detail rather than something this repo states.
  Caching in a separate bean also puts it in front of the *whole* ecosystem-then-bilateral
  resolution, so AC 12's "a repeated generation makes no second upstream call" holds for the
  fallback path too. It is **not** `@Cacheable` either — see decision 13: the annotation has no
  `unless`, so it would cache `Optional.empty()` and freeze an unknown contract for the whole
  `cache-ttl`. The gateway injects the `contracts` `SyncCache` and uses it explicitly: look up
  the `contractUri` key, and on a miss derive the contract id (last path segment, or the whole
  value when it carries no `/`), try `fetchContract`, fall back to `fetchBilateralContract`, and
  `put` **only** when a contract came back. An empty result is returned uncached; a transport
  failure or an exhausted retry becomes an `UpstreamServiceException` naming the upstream and
  the URI, also uncached.

**Tests**

- `client/ContractGatewayTest.java` — WireMock-backed, following `OidcDiscoveryStub`'s pattern.
  Parameterized over the retry matrix: `500`, `502`, `503` and a read timeout are each retried
  up to the configured attempt count and then surface as `UpstreamServiceException`; `400`,
  `401`, `403` and `409` are **not** retried (assert the exact request count at the stub, not
  just the final status — "not retried" and "failed" are different claims); a `404` on both
  paths yields an empty `Optional` with no retry; a `500` followed by a `200` succeeds within
  the budget.
- `client/ContractCacheIT.java` — two gateway calls for the same `contractUri` produce exactly
  one upstream request; two different URIs produce two. The two negative cases are asserted
  **apart**, because "a failed fetch is not cached" does not distinguish them (decision 13):
  after a `404` on both paths, a second call with the stub now returning the contract gets the
  contract, not a cached empty; after an exhausted retry, a second call with the stub recovered
  reaches upstream and succeeds.
- `client/ContractResponseTest.java` — parameterized deserialization over a bilateral body, an
  ecosystem body, a body carrying `_id` instead of `uid`, and a body carrying an unknown
  top-level property; each asserts `contractId()` and `shape()`.
- `src/test/resources/` — add the contract fixture bodies as JSON resources rather than inline
  string literals, so Step 2's extraction matrix can reuse the same documents.

**Acceptance criteria:** AC 12, AC 13; no new operation; `./mvnw verify` green.

### Step 2: `ContractContentExtractor` and the notice payload vocabulary

Covers AC 15. Still no API surface change, so `SPECIFIED_OPERATION_COUNT` stays at `16`.

**Domain**

- `domain/PrivacyNoticePayload.java` — rename `dataCategories` → `categoriesOfData`,
  `withdrawalMethod` → `withdrawalOfConsent`; replace `controllerName` + `controllerContact`
  with a nested `@Nullable ControllerDetails` record (`name`, `contact`, `identifier`,
  `selfDescriptionUri`); add `@Nullable List<DataReference> data`, where `DataReference` is a
  nested record of `uri` plus the optional `title`/`description` that resolution fills in; and
  change `piiPrincipalRights` from `List<String>` to `String`, because its only source is the
  single free-text `termsAndConditions` block (decision 9). Every component stays `@Nullable`
  and the record stays `@Serdeable`. See decision 9 for why this is free: nothing writes the
  table yet.
- The **six** test files constructing the record positionally —
  `api/ParticipantUserLinkIT`, `api/UserErasureIT`, `api/ParticipantDeregistrationSurfaceIT`,
  `service/ParticipantDeregistrationAtomicityIT`, `service/ParticipantDeregistrationIT`,
  `service/UserErasureAtomicityIT` — are updated in the same commit. They only seed fixtures, so
  the edits are mechanical, but every one of them breaks: merging `controllerName` and
  `controllerContact` into `ControllerDetails` and adding `data` leaves the arity at nine while
  changing the order and two component types, so a positional call that still compiles would be
  worse than one that does not. The build stays red until all six are done.
- `domain/ConsentSnapshot.java` is **not** touched. It is TICKET-008's model, it is not written
  by anything yet either, and widening this step to cover it would couple two tickets.

**Implementation**

- `service/ContractContentExtractor.java` — `@Singleton`, pure, no I/O, no repository. Takes a
  `ContractResponse` plus the resolved provider `Participant` and returns a
  `PrivacyNoticePayload` and a derived title. The mapping, with the ticket's table on the left
  and the real schema on the right:

  | Notice field | Ecosystem contract | Bilateral contract |
  |---|---|---|
  | `title` (column, not payload) | `ecosystem`, else `uid`/`_id` | `serviceOffering`, else `uid`/`_id` |
  | `purposes` | `purpose[].purpose` (else `.uid`); when absent, `serviceOfferings[].policies[].description` | `purpose[].purpose` (else `.uid`); when absent, `policy[].description` |
  | `categoriesOfData` | distinct `purpose[].piiCategory` | distinct `purpose[].piiCategory` (declared as an array here, as a scalar in the ecosystem shape — both are accepted) |
  | `data` | distinct `serviceOfferings[].serviceOffering` URIs — service *offerings*, which is all a contract names; the resources behind them are resolution's two hops (decision 8) | the single `serviceOffering` URI, title unresolved |
  | `recipients` | `members[].participant`, `revokedMembers` excluded | `dataProvider` and `dataConsumer` |
  | `controllerDetails` | the provider `Participant` row (`legalName`, `email`, `identifier`, `selfDescriptionUri`) | same |
  | `retentionPeriod` | `purpose[].termination` when all entries agree | `terminationAndValidity.terminationPeriod` |
  | `internationalTransfers` | `purpose[]` entries with `thirdPartyDisclosure` true, named by `thirdPartyName` | same |
  | `piiPrincipalRights` (a `String`, decision 9) | **absent** — no source | `termsAndConditions` when present, else absent |
  | `withdrawalOfConsent` | **absent** — no source | `termsAndConditions` when present, else absent |

  Every "absent" is a real absence: no `""`, no `[]`, no configured placeholder (decision 4).
  Ordering is deterministic — lists are de-duplicated preserving first-seen order — because a
  payload that reshuffles would make Step 3's unchanged-contract comparison report a change
  that did not happen.

**Tests**

- `service/ContractContentExtractorTest.java` — the parameterized extraction matrix AC 15 asks
  for, over the Step 1 JSON fixtures plus the shapes that exercise the gaps: a full bilateral
  contract; a full ecosystem contract; a contract with no `purpose[]` (falls back to policy
  descriptions); a contract with no `policy`/`policies` either (purposes absent, not empty); a
  bilateral contract with no `termsAndConditions` (`piiPrincipalRights` and
  `withdrawalOfConsent` **absent**); an ecosystem contract, which never has it; a contract with
  no `serviceOffering` (`data` absent); a contract whose `purpose[]` entries disagree on
  `termination` (`retentionPeriod` absent rather than arbitrarily picked); a contract with
  `piiCategory` as a scalar and as an array; a contract carrying only `_id`. Each case asserts
  the whole payload, so a field this plan says is absent cannot quietly become `""`.
- `service/ContractContentExtractorOrderTest.java` — extracting the same contract twice yields
  an `equals` payload, and shuffling a contract's array members changes it only where the
  contract genuinely differs. This is what Step 3's change detection rests on.

**Acceptance criteria:** AC 15; the extraction matrix is pinned including every missing
optional section; `./mvnw verify` green.

### Step 3: `POST /privacy-notices` — generation, deduplication and archival

Covers AC 1, AC 2, AC 3, AC 4, AC 5, AC 6, AC 10. Raises `SPECIFIED_OPERATION_COUNT` from
`16` to `17` in the same commit.

**Spec**

- `api/components/schemas/GeneratePrivacyNotice.yaml` — `contractUri` (required,
  `minLength: 1`, `maxLength`), `dataProvider` and `dataConsumer` (both required, global
  participant identifiers, `minLength: 1`, `maxLength`). The description states that the two
  are identifiers, not URLs, and that nothing here is base64-encoded.
- `api/components/schemas/PrivacyNotice.yaml` — `id`, `contractUri`, `title`, `dataProvider`,
  `dataConsumer`, `purposes`, `categoriesOfData`, `data`, `recipients`, `controllerDetails`,
  `retentionPeriod`, `piiPrincipalRights`, `internationalTransfers`, `withdrawalOfConsent`,
  `createdAt`, `updatedAt`, `archivedAt`, and the `resolution` block Step 5 fills in. `data`
  and `recipients` are arrays of objects. A cross-file `items: $ref` degrades to `List<Object>`,
  and whether inline object items *within* a component file escape that is **unverified** — no
  schema in `api/components/schemas/` declares one today. So this step **probes it first**: write
  the two arrays with inline object items, run `./mvnw generate-sources`, and read the generated
  `PrivacyNotice`'s field types. If they are `List<DataReference>` / `List<Recipient>`, keep the
  form; if either is `List<Object>`, fall back to the only form this repo has proven — named
  `DataReference` and `Recipient` schemas with the wrapper declared inline in `openapi.yaml`
  using internal `#/components/schemas/...` refs, exactly as `BulkUserRegistration` and
  `ParticipantPage` do. Either way the outcome is locked by an assertion in
  `PrivacyNoticeSpecParityTest` (Step 6) that the generated accessors return the element type and
  not `Object`, so a later spec edit that degrades them fails the build instead of silently
  serving untyped arrays. No property is `nullable`; optional means absent, which is how
  `archivedAt` reports "not archived".
- `api/components/schemas/PrivacyNoticeResolution.yaml` — `contract`, `dataResources`,
  `purposes` and `recipients`, each an enum of `RESOLVED`, `UNAVAILABLE` and `NOT_REQUESTED`.
  The ticket's example shows only the first two; the third is what `resolve=false` honestly
  reports, and is better than omitting the block and leaving a client to guess. Step 3 always
  emits `NOT_REQUESTED` for all four — generation does not resolve — and Step 5 is where the
  other two values appear.
- `api/openapi.yaml` — `POST /privacy-notices`, `operationId: generatePrivacyNotice`, tag
  `Privacy Notices`, `security: [bearerAuth: [PARTICIPANT]]`, `x-roles: [PARTICIPANT]`,
  `x-principal: ParticipantPrincipal`; responses `201` and `200` (both `PrivacyNotice`), `400`,
  `401`, `403`, `404`, `502` and `default`. The description states the five flow steps in
  order, that `200` means an identical active notice already existed, and that `502` is the one
  place a Contract Service outage is fatal. Register the three new schemas in `components`.

**Implementation**

- `service/PrivacyNoticeService.java` — `@Singleton`, **not** `@Transactional` at the entry
  point. `generate(ParticipantPrincipal caller, String contractUri, String providerIdentifier,
  String consumerIdentifier)`:
  1. Resolve both identifiers with `ParticipantRepository.findByIdentifier`;
     `NotFoundException` naming the unresolved one when either is unknown.
  2. `ForbiddenException` unless `caller.requireActive().getIdentifier()` equals one of the two
     (AC 3). The check is on the *resolved* caller, so a deregistered participant cannot mint a
     notice (decision 12).
  3. `ContractGateway.fetch(contractUri)`; empty → `NotFoundException`; unreachable →
     `UpstreamServiceException` → `502` (AC 10), which the gateway already raises.
     Then the **membership check**: `BadRequestException` unless both declared parties are
     named in the fetched contract (bilateral: `dataProvider`/`dataConsumer`; ecosystem:
     `members[].participant` minus `revokedMembers`, the provider also appearing in
     `serviceOfferings[].participant`). Without it the request body could name any consumer at
     all and the stored notice would misname a party in a published legal document — and it is
     what makes the `consumerId` comparison in point 5 meaningful (decision 14).
     `ContractPartyResolver` (Step 4) derives the same sets, so Step 4 moves this check into it
     rather than duplicating it.
  4. Extract the payload and title.
  5. `findByContractUriAndProviderIdAndArchivedAtIsNull(contractUri, provider.getId())`. When a
     notice exists and its `(title, consumerId, payload)` **equals** the freshly extracted
     triple, return it with `200` and write nothing (AC 4). Otherwise archive it and create a
     new one, `201` (AC 5).

  Comparing the *extracted payload* rather than hashing the raw contract is deliberate: an
  upstream edit that does not change the notice — a `signatures[]` entry, an `updatedAt` bump —
  must not archive a notice that consents already point at. It also needs no new column and
  therefore no migration. Step 2's ordering test is what makes the comparison stable.

  `consumerId` stays in the comparison, and with the membership check in place a mismatch means
  the contract changed party — an archive-and-recreate, which is AC 5. The residual case that
  decision 14 names is an ecosystem contract admitting two consumers against one provider: the
  index holds one active notice per `(contractUri, provider)`, so the second consumer's notice
  supersedes the first and two consumers regenerating in turn would archive each other. That is the inherited index's
  shape rather than the comparison's, and it is pinned by a test rather than left to be found.
- `service/PrivacyNoticeWriter.java` — `@Singleton` holding the one `@Transactional` method,
  `archiveAndCreate(...)`, so that archival and creation are atomic. It is a **separate bean on
  purpose**: Micronaut AOP does not apply to self-invocation, so a `@Transactional` method
  called from a sibling method of the same class would silently run without a transaction.
- The race (AC 6): `uq_privacy_notices_active_contract` rejects the second concurrent insert.
  `generate` catches that unique-constraint violation, lets the writer's transaction roll back,
  re-reads the active notice **outside** any transaction and returns it with `200`. The re-read
  cannot happen inside the failed transaction — Postgres forbids it — which is the same reason
  `UserService`'s create-or-link path is not transactional. Exactly one active notice survives,
  and the loser returns the winner rather than an error.
- `api/PrivacyNoticeMapper.java` — `@Singleton`, domain → generated model, `Instant` →
  `OffsetDateTime` at UTC, mirroring `UserMapper` and `ParticipantMapper`. It takes the
  resolution block as a parameter so Step 5 does not have to change its shape.
- `api/PrivacyNoticeController.java` — `@Controller` extending
  `AbstractPrivacyNoticesController`; the principal is the first, unannotated parameter;
  returns `HttpResponse.created(body)` or `HttpResponse.ok(body)` per the outcome.
- `repository/PrivacyNoticeRepository.java` — add only what is missing. The provider-side active
  lookup already exists.

**Tests**

- `api/PrivacyNoticeGenerationIT.java` (`@MicronautTest(transactional = false)` +
  `KeycloakAndPostgresTestResource`, Contract Service stubbed with WireMock — AC 14): a notice
  is generated from a contract for two named participants and persists the extracted payload
  (AC 1); the request body carries plain identifiers and no parameter is base64-encoded, and
  a base64-encoded identifier is **not** accepted as an alias (AC 2 — assert the `404`, so the
  claim is pinned rather than assumed); a caller that is neither party gets `403` (AC 3); an
  unknown provider or consumer gets `404`; a deregistered caller gets `403`; generating twice
  for an unchanged contract returns the same `id` with `200` and leaves one row (AC 4);
  generating after the contract changes returns `201` with a new `id`, and the old row carries
  `archivedAt` (AC 5); an upstream edit that does not alter the extracted payload returns `200`
  and does **not** archive; an unreachable Contract Service returns `502` with
  `application/problem+json` (AC 10); an unknown contract returns `404`, not `502`; a request
  naming a consumer the fetched contract does not name returns `400` and writes nothing
  (point 3's membership check); and, for an ecosystem contract admitting two consumers against
  one provider, generating for the second consumer archives the first and returns `201` — the
  residual case of decision 14, asserted so it is recorded rather than a surprise.
- `service/PrivacyNoticeConcurrencyIT.java` — N threads generate for the same
  `(contractUri, provider)` at once; afterwards exactly one row has `archived_at IS NULL`, every
  caller got a `2xx`, and every response names that same `id` (AC 6). Assert the row count at
  the database, not through the API.

**Acceptance criteria:** AC 1-6, AC 10, AC 14 for this surface.

### Step 4: `POST /privacy-notices/by-contract`

Covers AC 7. Raises `SPECIFIED_OPERATION_COUNT` from `17` to `18`.

**Spec**

- `api/components/schemas/GenerateByContract.yaml` — `contractUri` only, required.
- `api/openapi.yaml` — `POST /privacy-notices/by-contract`,
  `operationId: generatePrivacyNoticeByContract`, same security, principal and response set as
  Step 3. The description states how the two parties are derived and that an ambiguous contract
  is a `400` pointing at `POST /privacy-notices`.

**Implementation**

- `service/ContractPartyResolver.java` — `@Singleton`, pure. Derives the two parties from a
  fetched `ContractResponse`:
  - **Bilateral**: `dataProvider` and `dataConsumer` directly. Either missing → `400`.
  - **Ecosystem**: the provider side is the distinct set of
    `serviceOfferings[].participant`; the consumer side is `members[].participant` minus the
    provider side, with `revokedMembers` excluded. Exactly one on each side → those two.
    Otherwise `400` naming how many parties were found on each side and pointing at
    `POST /privacy-notices`.

  An ecosystem contract names N members with roles and has no inherent two-party split, so
  guessing would be worse than refusing. Deriving the provider from who actually offers the
  service needs no role vocabulary and no configuration, which is why it is preferred to
  matching `members[].role` against a configured list.
- `ContractPartyResolver` also exposes the membership predicate Step 3's point 3 introduced —
  "is this identifier a party to this contract" — so the explicit and derived forms share one
  definition of who a contract's parties are instead of drifting apart.
- `PrivacyNoticeService.generateByContract(caller, contractUri)` — fetches, resolves the
  parties, then delegates to exactly the same path as Step 3 so that the duplicate, archival,
  `403`-for-a-non-party and race handling cannot drift between the two operations. The caller
  must still be one of the parties *named in the fetched contract* (AC 7 and AC 3 together).
- `PrivacyNoticeController.generatePrivacyNoticeByContract` — thin.

**Tests**

- `service/ContractPartyResolverTest.java` — parameterized: a bilateral contract resolves both
  parties; a bilateral contract missing `dataConsumer` is `400`; an ecosystem contract with one
  offering participant and one other member resolves; an ecosystem contract with two offering
  participants is `400`; one with three members is `400`; one whose extra member is in
  `revokedMembers` still resolves.
- `api/PrivacyNoticeByContractIT.java` — the operation derives both parties and produces the
  same notice as the explicit form for the same contract (compare the persisted row, not just
  the status); a caller that is neither derived party gets `403`; a contract naming a party with
  no `participants` row gets `404`; an ambiguous ecosystem contract gets `400`; the duplicate
  and archival behaviour matches Step 3's, asserted through this operation too.

**Acceptance criteria:** AC 7.

### Step 5: `GET /privacy-notices/{id}` with partial resolution

Covers AC 8, AC 9, AC 11. Raises `SPECIFIED_OPERATION_COUNT` from `18` to `19` — the expected
end state.

**Spec**

- `api/openapi.yaml` — `GET /privacy-notices/{id}`, `operationId: getPrivacyNotice`, tag
  `Privacy Notices`, `security: [bearerAuth: [USER, PARTICIPANT, CATALOG]]`,
  `x-roles: [USER, PARTICIPANT, CATALOG]`, `x-principal: ConsentManagerPrincipal` (decision 11);
  query parameter `resolve` (boolean, `default: true`); responses `200`, `401`, `403`, `404`,
  `default`. **No `502`** — the description states plainly that resolution never fails the
  request, and that an unreachable external document is reported in `resolution` instead.
  It also states that an archived notice stays retrievable and carries `archivedAt`, and that
  the operation is deliberately not scoped to the parties.

**Implementation**

- `client/SelfDescriptionClient.java` — decision 8. `@Singleton` over the low-level
  `HttpClient`: `Optional<SelfDescription> fetch(String url)`, the contract-service timeouts,
  and the scheme/host/address guard. It returns `Optional.empty()` on **every** failure —
  refusal, timeout, non-2xx, unparseable body — and never throws, because a thrown exception
  here is exactly the `5xx` AC 9 forbids.

  **It caches the same way `ContractGateway` does, and for a stronger reason: the injected
  `self-descriptions` `SyncCache` read through on every call, `put` only after a successful
  parse.** `@Cacheable` would be wrong here for exactly decision 13's reason — the annotation has
  no `unless`, so it would store the `Optional.empty()` this method returns on *every* failure.
  For the gateway an empty at least means "unknown"; here it can equally mean "the network
  blipped", so the annotation would pin a momentarily unreachable document to `UNAVAILABLE` for
  the whole `cache-ttl`. A refusal by the guard is not cached either — it is a configuration
  decision, not a fetch result, and re-evaluating it costs nothing.
- `config/ConsentManagerConfiguration` — a nested
  `@ConfigurationProperties("self-description")` class with `allowedHosts` (empty = any public
  host; **an entry here also waives the private-address refusal for that host**, decision 8),
  `allowPlaintextHttp` (default `false`) and `cacheTtl`; `application.yml`,
  `micronaut.caches.self-descriptions` and `.env.sample` updated. `application-test.yml` sets
  `allowed-hosts: [localhost, 127.0.0.1]` and `allow-plaintext-http: true`, which is what lets a
  WireMock-stubbed self-description be fetched at all.
- `service/PrivacyNoticeResolver.java` — `@Singleton`. Given a `PrivacyNotice` it produces the
  four-valued resolution block and an enriched payload view:
  - `contract` — re-fetch through `ContractGateway` for the live title and terms. Unreachable
    or unknown → `UNAVAILABLE`, and the stored payload is served as-is. **The resolver catches
    `UpstreamServiceException`**, which Step 1's gateway raises on a transport failure or an
    exhausted retry; letting it propagate would turn a GET against an unreachable Contract
    Service into the `502` AC 9 forbids. `SelfDescriptionClient` never throws, so the obligation
    is one-sided: the gateway is the only resolution dependency that can.
  - `dataResources` — two hops (decision 8): fetch each `data[].uri`, which is a *service
    offering* self-description, then fetch each `dataResources[]` URL it names and fill that
    resource's `title` and `description`. The offering's own name and description fill the
    `data[]` entry itself. **Any** failure at **either** hop marks the section `UNAVAILABLE`
    while still returning whatever did resolve, because a partially-filled list that claims
    `RESOLVED` would misreport itself — and an offering that resolved while its resources did
    not is exactly that case, not a success.
  - `purposes` — resolved from the contract when the contract resolved, otherwise the stored
    text with `UNAVAILABLE`. **`purposes` is therefore dependent on `contract` by construction:
    a failed contract fetch degrades both sections, and no input degrades `purposes` alone.**
    That is reported rather than hidden — serving the stored text while claiming `RESOLVED`
    would make the section say the live contract was consulted when it was not.
  - `recipients` — resolved from the local `participants` rows by identifier (decision 1);
    any identifier with no row marks the section `UNAVAILABLE` and that recipient keeps its
    bare identifier.
  - With `resolve=false` no external call is made at all and all four read `NOT_REQUESTED`.
  - Resolution is read-only. It never writes back to `privacy_notices` — a GET that mutates on
    behalf of whoever happens to call it would make the stored payload depend on read traffic.
- `PrivacyNoticeService.get(UUID id, boolean resolve)` — load or `NotFoundException`; an
  archived notice resolves normally and reports `archivedAt` (AC 11).
- `PrivacyNoticeController.getPrivacyNotice` — thin; the principal is accepted and unused
  beyond authorization, which is what decision 11 specifies.

**Tests**

- `api/PrivacyNoticeRetrievalIT.java` — `resolve=true` fills contract title and terms, the
  offering descriptions **and the data-resource descriptions behind them** (assert a field that
  only exists in the second-hop document, so one-hop resolution cannot pass), purposes and
  recipient details, and reports all four `RESOLVED` (AC 8); `resolve=false` makes **zero**
  upstream requests (assert at the WireMock stub) and reports four `NOT_REQUESTED`; an unknown
  id is `404`.
- `api/PrivacyNoticePartialResolutionIT.java` — parameterized over which external document
  fails and how (`500`, timeout, connection refused, malformed body, a second-hop resource
  document failing while its offering resolves, and for recipients a party with no local row):
  each yields `200` with the notice body still present and only the affected sections degraded
  (AC 9). The expectation is **not** uniformly "one section": a contract-failure case reports
  `contract` **and** `purposes` `UNAVAILABLE` with the other two `RESOLVED`, because `purposes`
  resolves from the contract by construction; every other case degrades exactly one section.
  A case asserts that a *partially* resolved `data` list reports `UNAVAILABLE`, not `RESOLVED`.
  **Connection-refused against the Contract Service is one of the contract cases**, and it is
  what pins the `UpstreamServiceException` catch above — without it that case is a `502`, not a
  `200`.

  **The contract cases need a cold cache, and will silently pass without one.** `ContractGateway`
  caches on `contractUri`, so a case that generates the notice through `POST /privacy-notices`
  and then reconfigures the stub to fail never reaches the stub: the resolver is served from the
  cache and reports `RESOLVED` whatever the stub does. This IT therefore (a) uses a **distinct
  `contractUri` per case**, so no two cases share a key, and (b) seeds the `privacy_notices` row
  **directly through `PrivacyNoticeRepository`** rather than generating it through the API, so
  nothing warms the cache before the GET. A test that asserts a cache miss reached the stub —
  `verify(exactly(1), ...)` on the failing stub — is part of each contract case, so a future
  change that re-warms the cache fails the test instead of hollowing it out.
- `api/PrivacyNoticeArchivedRetrievalIT.java` — a notice archived by a regeneration is still
  `200` by id, carries `archivedAt`, and resolves (AC 11).
- `client/SelfDescriptionGuardTest.java` — parameterized, with `allowed-hosts` set explicitly
  per case rather than inherited from the test profile: `http://` with `allow-plaintext-http`
  false; a loopback host **not** in `allowed-hosts`; a private-range literal not in
  `allowed-hosts`; a host outside a non-empty `allowed-hosts` — all four `Optional.empty()` with
  no request reaching the stub. Then the two that must succeed: a well-formed `https` host inside
  `allowed-hosts`, and the loopback WireMock URL **with `127.0.0.1` in `allowed-hosts`**, which
  pins decision 8's one relaxation and is the configuration every other resolution test runs
  under.

**Acceptance criteria:** AC 8, AC 9, AC 11.

### Step 6: Role matrix, spec-consistency and the cross-cutting assertions

Covers AC 14 across the module and closes the matrix the earlier steps only touched per-feature.

- `api/EndpointRoleMatrixIT.java` — extend the existing matrix with all three new operations ×
  every role (`USER`, `PARTICIPANT`, `CATALOG`, `OUTSIDER`, unauthenticated), asserting the
  spec's roles exactly: both generation operations are `PARTICIPANT`-only, retrieval admits all
  three. Assert the status, not just the absence of a body.
- `security/KeycloakRoleMatrixIT.java` — the same three operations against real Keycloak
  tokens, so the roles are pinned against the issuer as well as against the test profile.
- `api/PrivacyNoticeSpecParityTest.java` — a plain surefire unit test, in the style of
  `ParticipantSchemaParityTest`: `GeneratePrivacyNotice` and `GenerateByContract` agree on
  `contractUri`'s constraints, `PrivacyNotice`'s fields are exactly the extractor's payload
  components plus the entity's own columns and the resolution block, no `PrivacyNotice` property
  is `nullable`, and the generated `data` and `recipients` accessors return their **element
  type** and not `Object` — the assertion Step 3's inline-items probe leaves behind, so a
  degraded `List<Object>` is a build failure rather than a silently untyped array. A spec edit
  that drifts from the payload then costs a build rather than a silent hole in a served notice.
- `api/PrivacyNoticeNoBase64Test.java` — AC 2 as a standing guard: grep the authored spec and
  the privacy-notice sources for `base64`, `Base64` and the pre-revision `SD64` parameter
  convention, and fail on a hit. The pre-revision API is what this ticket exists to replace, and
  the cheapest way to keep it from creeping back is to make it un-mergeable.
- Confirm `SpecSecurityConsistencyTest.SPECIFIED_OPERATION_COUNT` reads `19` and that the parsed
  list actually has 19 entries — the constant is only a tripwire while it tracks reality.

**Acceptance criteria:** AC 2 and AC 14 pinned by standing tests; the full role matrix green;
the JaCoCo gate still passes.

### Step 7: Final verification and the acceptance-criteria walk

- `./mvnw clean verify` from a clean `target/`; unit tests, integration tests, Spotless and the
  JaCoCo gate all green.
- Walk the 15 acceptance criteria and record, for each, the test that pins it. Any criterion
  that ended up unmet is named outright rather than quietly dropped.
- Confirm every new configuration key appears in **both** `application.yml` and `.env.sample`,
  with the env-var indirection and a comment in the existing style:
  `consent-manager.contract-service.{url,connect-timeout,read-timeout,retry-attempts,retry-delay,retry-multiplier,cache-ttl}`,
  `consent-manager.self-description.{allowed-hosts,allow-plaintext-http,cache-ttl}`,
  `micronaut.caches.{contracts,self-descriptions}`.
- Confirm no file named `api/paths/*.yaml` was created and `SpecReferenceResolutionTest` is
  green, i.e. every path item stayed inline.
- Confirm `CONTRACT_SERVICE_URL` still resolves after the Step 1 key move, by starting the app
  against `compose.yaml` with only that variable set.
- **`AGENTS.md` is not edited here.** A step agent may not edit it — the file is appended to the
  agent system prompt, so an edit invalidates the cached prefix for every later session. The
  refresh is Step 8, performed by a plan-mode agent.

**Acceptance criteria:** clean `./mvnw clean verify`; the acceptance-criteria walk recorded on
the ticket.

### Step 8: Refresh `AGENTS.md` (plan-mode agent)

Owned by a **plan-mode** agent, for the reason Step 7 gives. Documentation only: `AGENTS.md`
alone, no code, spec, test or configuration change. The list below is a floor, not a cap —
anything else found stale while editing is in scope.

1. **Project structure** — add `client/` (`ContractServiceClient`, `ContractGateway`,
   `UpstreamRetryPredicate`, `SelfDescriptionClient`, `dto/ContractResponse`), and under
   `service/` add `PrivacyNoticeService`, `PrivacyNoticeWriter`, `ContractContentExtractor`,
   `ContractPartyResolver`, `PrivacyNoticeResolver`; under `api/` add `PrivacyNoticeController`
   and `PrivacyNoticeMapper`.
2. **`api/components/schemas/`** — add `PrivacyNotice.yaml`, `PrivacyNoticeResolution.yaml`,
   `GeneratePrivacyNotice.yaml`, `GenerateByContract.yaml`.
3. **The upstream contract shapes** — record that the Contract Service serves two document
   shapes behind one `ContractResponse`, that ecosystem is tried before bilateral, that `uid`
   and `_id` are both accepted, and that **neither shape carries a `title`**, so the notice
   title is derived by a documented precedence. A future agent that assumes `title` exists will
   otherwise write a null-pointer.
4. **The retry and cache contract** — retry on 5xx and timeouts only, never on 4xx; a `404` is
   an empty `Optional` and never a retry; caching happens in `ContractGateway` and not on the
   `@Client` interface, and it uses `SyncCache` explicitly rather than `@Cacheable` because
   `@Cacheable` has no `unless` and would cache an empty result, freezing an unknown contract
   for the whole TTL. Only a hit is stored. `SelfDescriptionClient` caches the same way and for
   the same reason.
5. **Partial resolution** — `RESOLVED` / `UNAVAILABLE` / `NOT_REQUESTED`; resolution never fails
   a request and never writes back; the resolver catches the gateway's `UpstreamServiceException`
   so that generation stays the single place a Contract Service outage is a `502`. Record that
   `purposes` resolves from the contract, so a failed contract fetch degrades both sections.
6. **`SpecSecurityConsistencyTest.SPECIFIED_OPERATION_COUNT` is now `19`**, and the two new
   build-failing unit tests `PrivacyNoticeSpecParityTest` and `PrivacyNoticeNoBase64Test`.
7. **No Flyway migration was added** by this ticket; `V3` is still the latest. Record that
   `privacy_notices` predates its first writer and that `uq_privacy_notices_active_contract` is
   what makes concurrent generation safe, so an application-level check is not the guarantee.
8. **The renamed `PrivacyNoticePayload` vocabulary** — `categoriesOfData`, `controllerDetails`,
   `withdrawalOfConsent`, `data`, and `piiPrincipalRights` now a `String` — and that
   `ConsentSnapshot` still carries the *old* names and is TICKET-008's to align.
   - **The self-description guard** — https-only unless `allow-plaintext-http`, host allow-list,
     private/loopback refused **unless the host is in `allowed-hosts`**, which is both the
     operator escape hatch and what makes WireMock-stubbed resolution testable.
   - **The inline-`items` question, settled by Step 3's probe.** Replace the "unverified" wording
     this plan's refresh put in the `items: $ref` gotcha with whichever answer the probe
     produced, and name the parity-test assertion that now holds it.

**Already applied by this plan agent — verify, do not redo.** Ticket #69's Step 10 deferred its
`AGENTS.md` refresh to a plan-mode agent and no such agent ever ran, so the file every step
agent loads as system prompt was still stale on all of it. It is fixed in the same commit as
this plan, because a step agent starting from a context file that names a class which does not
exist will act on it. Points 9-18 are therefore a **checklist to confirm still true at the end
of the ticket**, not work to perform:

9. `service/` names `UserProvisioningService`, which does not exist — JIT provisioning lives in
   `UserService` and returns a `ProvisionedUser`. The line should name `UserService`,
   `ParticipantService`, `ErasureVerifier` and `CallerScope`, and the participant module's
   `ParticipantRegistration`, `ParticipantUpdate` and `DeregistrationResult`.
10. `api/` omits `ParticipantController`, `ParticipantMapper` and `ApiStatusController`.
11. The pagination convention is undocumented: `GET /participants` clamps an oversized `size`
    to `consent-manager.participants.page-max-size` and answers `200` rather than refusing; the
    spec carries no `maximum` because the ceiling is per deployment; the response reports the
    size it applied.
12. `GET /participants/{identifier}`'s two limitations. Use the merged tree's wording, not the
    stronger claim PR #5 removed: a path variable never spans a `/`, so a URI-shaped identifier
    reaches the route **only percent-encoded**, and whether an encoded slash survives is a
    property of the deployment rather than of this service — this stack happens to route `%2F`
    through because the variable is decoded after the route is matched. The `identifier` query
    filter on `GET /participants` is therefore the *dependable* way, not the only one. Second
    limitation, same sentence: the literal identifier `me` is unreachable on that route in every
    deployment, because `GET /participants/me` claims the path.
13. `ParticipantPrincipal` is a **three**-state contract. The row may be absent, which is what
    makes self-registration reachable and which only `POST /participants` accepts.
    `requireRegistered()` admits any registered row and is what a route reading or writing the
    caller's own record uses (`ParticipantController`, and `CallerScope.of()`, which is why
    every participant-scoped read fails closed for an unregistered token). `requireActive()` is
    stricter by one condition — it also refuses a row carrying `deregistered_at` — and is what
    every route acting in the dataspace in the participant's name uses
    (`ParticipantUserController`, and now privacy-notice generation).
14. `V3__participant_deregistration.sql` is missing from the migration list, as are the
    `deregistered_at` semantics: nullable, null for every active participant, set only on the
    retain branch, with a partial index keeping the directory listing to active rows.
15. `config/ConsentManagerConfiguration.java` reads "nested Erasure / Users classes"; there are
    now `Participants`, `ContractService` and `SelfDescription` too. The ADR range reads
    `0001-0007`; it should read `0001-0008`.
16. The `api/components/schemas/` listing omits the six schemas #69 added — `Participant.yaml`,
    `ParticipantEndpoints.yaml`, `ParticipantLegalPerson.yaml`, `ParticipantRegistration.yaml`,
    `ParticipantUpdate.yaml`, `DeregistrationSummary.yaml` — and `ParticipantSchemaParityTest`
    and `ParticipantCredentialAbsenceTest` are not named as build-failing spec tests.
17. `error/` omits `ApiExceptionHandler.java`, `ConstraintViolationProblemHandler.java` and
    `ProblemType.java`; `domain/` omits `PrivacyNoticePayload.java`; `src/main/resources/`
    omits `static/swagger-ui/index.html`, which is what makes the Swagger UI entry under
    "Important Files" work. `SpecReferenceResolutionTest` is a second build-failing spec test
    not called out.
18. **`./mvnw verify -DskipUTs` does not skip unit tests.** `skipUTs` is defined nowhere in
    `pom.xml`, `.mvn/`, or the inherited `micronaut-parent` / `micronaut-platform` 5.2.0 poms;
    Surefire and Failsafe are declared with no `<configuration>` at all, and Maven silently
    ignores an undefined `-D`, so the documented command runs the whole suite. #69's Step 9
    already corrected the `docs/security.md` occurrence; `AGENTS.md`'s Build & Test block still
    carries it. Correct it to what is true — `./mvnw verify` runs both suites, `./mvnw test`
    runs unit tests only — rather than substituting another flag: Surefire and Failsafe share
    the `skipTests` property, so an integration-only run needs a property wired in `pom.xml`
    that does not exist. Wiring one is a build change and belongs to a step of its own.

**Acceptance criteria:** `AGENTS.md` matches the merged state on every point above. No other
file changes.
