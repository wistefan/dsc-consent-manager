# Implementation Plan: Consent Manager - Reimplementation - Participant Notification

## Overview
Push every permission-changing consent move to the participants it binds. A transactional outbox
(`notification_outbox`, written in the same transaction as the move) is drained by a scheduled,
lease-based, `SKIP LOCKED` dispatcher that POSTs a minimal, detached-JWS-signed payload to
`endpoints.consentNotification`, with backoff, abandonment, per-participant metrics, and a
`GET /participants/me/notifications` history route for integrators.

## Decisions and adaptations (read before any step)

Where the ticket text and the repo differ, the repo wins:

- **Package** is `com.seamware.consentmanager`. **Migration** is `V6__notification_outbox.sql`,
  because V2–V5 exist.
- **Config** lives in a new nested class `ConsentManagerConfiguration.ParticipantNotifications`
  bound under `consent-manager.participant-notifications.*`. `consent-manager.notifications` and
  `notification/NotificationConfiguration` (`smtp.*`) already belong to *subject* email and are
  left alone. Every new env var goes in `.env.sample` (`EnvSampleParityTest`), `application.yml`
  and the Helm chart's `values.yaml` + `templates/configmap.yaml`.
- **Which moves notify.** A move is notified only when it creates or withdraws permission, which
  means the held status or the target status is `GRANTED`:

  | Held → target | Event | Recipients |
  |---|---|---|
  | (new) / `PENDING` → `GRANTED`, incl. resume | `CONSENT_GRANTED` | provider and consumer |
  | `GRANTED` → `REVOKED` (subject revoke, a decision superseding a grant, erasure) | `CONSENT_REVOKED` | provider and consumer |
  | `GRANTED` → `TERMINATED` | `CONSENT_TERMINATED` | the party that did **not** initiate it; both if system-initiated |
  | `GRANTED` → `EXPIRED` | `CONSENT_EXPIRED` | provider and consumer |

  Not notified: creation in `PENDING`/`DRAFT`, `REFUSED`, re-confirmation (`affirm`, which is an
  event and not a move), and `PENDING`/`DRAFT` → `TERMINATED`. Cleanup, deregistration and
  participant termination of an unanswered request all change no permission. If provider and
  consumer are the same participant, it gets one row, not two.
- **Expiry has no trigger today.** Only the `GRANTED → EXPIRED` edge in `ConsentStateMachine`
  exists. This ticket adds no expiry mechanism. The hook sits on the shared write path, so any
  future expiry job notifies automatically. Tests drive `ConsentWriter.move(id, EXPIRED, …, SYSTEM)`.
- **Write paths to hook.** Moves are made in three places, all in a transaction:
  `ConsentWriter.saveNew`/`saveUpdated` (decide, request, move, resume and their collateral),
  `UserService.close` (erasure, `@Transactional erase`) and `ParticipantService.terminate`
  (deregistration, `@Transactional deregister`). All three must enqueue.
- **`eventId`** is the `consent_events.id` (UUIDv7) of the move. One event fans out to two rows
  with the same `eventId`, and the value never changes across retries. `occurredAt` is that
  event's `occurredAt`.
- **Payload is frozen at enqueue** as `jsonb`, so the signed bytes and `eventId` are the same on
  every retry. The *endpoint* is resolved at dispatch time, so a participant can fix a broken URL
  and have pending rows reach it.
- **Status semantics.** `PENDING` has not been attempted yet. `FAILED` means the last attempt
  failed and a retry is scheduled. `DELIVERED` and `ABANDONED` are terminal. Because `FAILED` rows
  are also due, the due index covers `status IN ('PENDING','FAILED')`, not only `PENDING` as the
  ticket's index does.
- **Claiming uses a lease, not a lock held across HTTP.** A short transaction selects due rows
  `FOR UPDATE SKIP LOCKED`, pushes their `next_attempt_at` forward by a lease, stamps a fresh
  `lease_token` (UUIDv7, one per claim), then commits. Lease arithmetic uses the database clock
  (`now()`), so clock skew between replicas cannot shorten a lease. Delivery runs outside any
  transaction, and the outcome is written in a second short transaction. If the process dies
  mid-send, the row becomes due again when the lease expires (at-least-once, AC3).
- **A lease covers the whole batch, and outcomes are fenced.** One request is bounded by
  connect + read timeout, but a batch runs in waves of `concurrency`, so it takes up to
  `ceil(batchSize / concurrency) × (connectTimeout + readTimeout)`. Startup validation requires
  the lease to exceed *that* bound plus a margin (defaults: `ceil(50/8) × 7s = 49s < 60s`). The
  dispatcher also never starts a send whose lease has less than `connectTimeout + readTimeout`
  left; such a row is dropped from the batch untouched and is re-claimed after expiry. Every
  outcome write is `UPDATE … WHERE id = :id AND lease_token = :token`; 0 rows updated means the
  lease was lost to another replica, and the late outcome is discarded (logged at DEBUG, no
  metric). A late writer can therefore never overwrite a row another replica already moved
  (AC13/AC17).
- **Isolation.** The claim query caps rows per participant per batch. Postgres refuses
  `FOR UPDATE` over a window function (`FOR UPDATE is not allowed with window functions`), so the
  ranking is a joined subquery and only the base table is locked (shape in Step 1). Deliveries run
  on a bounded pool with short timeouts, and startup validation requires
  `perParticipantBatchLimit < concurrency`, so a dead endpoint can hold at most its cap of workers
  and never the whole pool (AC12).
- **`last_error`** is a short classification (`HTTP 503`, `read timeout`, `connection refused`,
  `no consentNotification endpoint`, `participant deregistered`, `endpoint not permitted`). It
  never holds a response body. The history route returns it, and logs may print it.
- **Erasure, and the subject identifier in the outbox.** ADR-0007/ADR-0014 do not let a stored
  artefact keep naming an erased subject (`erase` deletes `consent_receipts` for that reason), so
  the outbox may hold the identifier only while it still has to be sent:
  - **Terminal rows carry no payload.** The outcome write that moves a row to `DELIVERED` or
    `ABANDONED` sets `payload = NULL`, and V6 enforces it with
    `CHECK (status IN ('PENDING','FAILED') OR payload IS NULL)`. The history route reads only
    columns (`event_id`, `event_type`, `consent_id`, status, timestamps, `last_error`), never the
    payload, so nothing it needs is lost. Rows that predate an erasure and are already terminal
    therefore hold no identifier, and nothing has to scrub them.
  - **Due rows keep it until they turn terminal.** A revocation caused by erasure is enqueued
    *before* the user is pseudonymised, in the same transaction, so its payload carries the
    identifier the recipients already hold, the only one they can act on. Earlier rows for that
    user that are still `PENDING`/`FAILED` keep theirs for the same reason. Each such row turns
    terminal (and is redacted) no later than the end of the backoff schedule plus one lease
    (≈ 31.4 h with the defaults, ≈ 34.5 h at maximum jitter).
  - That bound, not `retention`, is what the ADR states. `participant-notifications.retention`
    (default 30 days) only limits how long the identifier-free delivery history is kept.
- **SSRF fence on the participant-supplied URL.** `consentNotification` only requires https, so
  delivery gets the same fence `SelfDescriptionClient.permits` applies: the address heuristic
  (`isInternal`/`isExternal`: loopback, link-local, site-local, any-local, multicast, CGNAT
  `100.64.0.0/10`, ULA `fc00::/7`) is extracted into a shared helper and checked at dispatch time,
  before any connect. A refused or unresolvable host abandons the row with the fixed reason
  `endpoint not permitted`, so the history route never reveals whether an internal host is
  reachable. `participant-notifications.allowed-hosts` mirrors `self-description.allowed-hosts`
  (non-empty = only these hosts, which waive the address check), and is how the WireMock ITs
  reach loopback. The DNS-rebinding window between check and connect stays open exactly as for
  self-descriptions, and `allowed-hosts` closes it; `docs/security.md` says so.
- **Redirects are not followed** (the other half of the SSRF fence). A 3xx response is a permanent
  failure.
- **Notification signatures are typed.** `JwsConsentSigningService` signs every receipt with
  `typ: JOSE`. Notifications get their own `typ: consent-notification+jws`, so a signature over
  one kind of document never verifies as the other under the shared key. Receipts keep `JOSE`
  (existing receipts must still verify). The integrator guide tells recipients to require that
  `typ`.
- **Doc comments**: match the file. New records, enums and config stay at one to three lines. No
  `@param`/`@return` echoes. Named constants only. Parameterized tests wherever cases form a
  table.

## Steps

### Step 1: Outbox schema, domain types and repository
Add the storage layer with no callers yet.

- `src/main/resources/db/migration/V6__notification_outbox.sql`: the ticket's table, plus
  `event_id uuid NOT NULL` (the `consent_events` id), `consent_id uuid NOT NULL` if the ticket's
  table lacks it (the history route must not read the payload), `lease_token uuid NULL`,
  `UNIQUE (event_id, participant_id)` so the same event is never queued twice for one recipient,
  and a `CHECK` on `event_type` (the four values). `payload jsonb` is nullable, with
  `CHECK (status IN ('PENDING','FAILED') OR payload IS NULL)` (see *Erasure* above). Replace the
  ticket's index with `notification_outbox_due_idx ON (next_attempt_at)
  WHERE status IN ('PENDING','FAILED')`, and add `notification_outbox_participant_idx ON
  (participant_id, created_at DESC)` for the history route.
- `notification/` (the existing package; new classes are package-private wherever they can be):
  - `NotificationEventType` enum `CONSENT_GRANTED|CONSENT_REVOKED|CONSENT_TERMINATED|CONSENT_EXPIRED`.
  - `NotificationOutboxStatus` enum `PENDING|FAILED|DELIVERED|ABANDONED`.
  - `NotificationPayload` record (`@Serdeable`), with exactly the ticket's fields: `eventType,
    eventId, occurredAt, consentId, privacyNoticeId, userIdentifier, status, consented`.
  - `NotificationOutboxEntry` Micronaut Data `@MappedEntity("notification_outbox")`, with
    `payload` as `@TypeDef(type = DataType.JSON)`, mirroring how `Consent.snapshot` /
    `Participant.endpoints` map `jsonb`.
  - `NotificationOutboxRepository` (`@JdbcRepository(dialect = POSTGRES)`):
    `claimDue(int batchSize, int perParticipant)` as a native query. The window function lives in
    a joined subquery and only the base table is locked, because `FOR UPDATE` directly over a
    window function is rejected by Postgres 16:

    ```sql
    SELECT o.* FROM notification_outbox o
    JOIN (SELECT id, row_number() OVER (PARTITION BY participant_id ORDER BY next_attempt_at) rn
          FROM notification_outbox
          WHERE status IN ('PENDING','FAILED') AND next_attempt_at <= now()) r ON r.id = o.id
    WHERE r.rn <= :perParticipant
    ORDER BY o.next_attempt_at
    LIMIT :batchSize
    FOR UPDATE OF o SKIP LOCKED
    ```

    The ranking also counts rows another replica holds locked, so a concurrent claim may get
    fewer than `perParticipant` rows for one participant. That costs throughput, not correctness.
    `lease(List<UUID> ids, UUID token, Duration lease)` sets `lease_token` and
    `next_attempt_at = now() + lease`, returning the leased-until time.
    Fenced outcome writes `markDelivered` / `markFailed` / `markAbandoned`, each
    `… WHERE id = :id AND lease_token = :token` and returning the updated row count; the
    terminal two also set `payload = NULL`. `findByParticipantIdOrderByCreatedAtDesc(UUID,
    Pageable)`. `countDueByParticipant()` for the depth gauge. `deleteTerminalBefore(Instant)` for
    retention.
- Tests: `NotificationOutboxRepositoryIT` (extends `PostgresTestResource`). Cover round-trip of the
  jsonb payload; the claim returns only due `PENDING`/`FAILED` rows; the per-participant cap (as
  an upper bound, given the locked-row note above); two concurrent claims in separate transactions
  (two threads via `support/Race`) returning disjoint sets; a fenced write with a stale token
  updating 0 rows and leaving the row unchanged; and the payload `CHECK` refusing a terminal row
  with a payload. Reset between tests with `support/DatabaseReset` (add the new table if it
  enumerates tables).
- Acceptance: `./mvnw verify` green. Flyway applies V6 on the Testcontainers database.

### Step 2: Enqueue in the consent transaction
Make every permission-changing move durably queue its notifications atomically (AC1–AC6).

- `notification/NotificationRecipients` (pure, unit-testable): maps `(held status or empty,
  target status, provider id, consumer id, initiating participant id or null)` to an optional event
  type and the set of recipient participant ids, per the table in *Decisions*.
- `notification/ParticipantNotificationService` (`@Singleton`): `enqueue(RecordedConsent recorded,
  @Nullable ConsentStatus held, @Nullable UUID initiator)`. It builds the `NotificationPayload` from
  the consent, its event and the subject's identifier (`UserRepository.findById`), and saves one
  outbox row per recipient. Annotate it `@Transactional(MANDATORY)`, or equivalent, so it fails
  loudly when called outside the move's transaction. It never commits on its own.
- Hook it in:
  - `ConsentWriter.saveNew`/`saveUpdated` (thread `held` through. `create` has no held status).
    The enqueue goes **before** `issueIfGranted` in both methods: the receipt issuer runs inside
    them, so a receipt failure then rolls back a row that was really written.
  - `UserService.close` (erasure, before pseudonymisation).
  - `ParticipantService.terminate` (deregistration, which produces no rows under the rule, but
    keeps the hook total).
- Carry the terminating participant to the writer: add an initiator to the termination path
  (e.g. `ConsentWriter.move(..., @Nullable UUID initiator)` overload, used by
  `ConsentLifecycleService.terminate` with `caller.getId()`). Every other caller passes `null`.
- Tests:
  - `NotificationRecipientsTest`: parameterized over every (held, target) pair the state machine
    permits plus creations, asserting event type and recipients, including terminate-by-provider,
    terminate-by-consumer, system terminate, and provider == consumer.
  - `ParticipantNotificationEnqueueIT` (extends `PostgresTestResource`): a parameterized table
    driving each write path (grant, decide-refuse superseding a grant, revoke, terminate by each
    side, resume, request, refuse, re-confirm, cleanup terminate, erasure, deregistration, and
    `writer.move(…, EXPIRED, …)`), asserting the exact outbox rows: event type, recipients, and
    payload fields with no snapshot/content keys (AC4–AC6).
  - Rollback (AC2): a `@Replaces` receipt-issuer bean that, inside the transaction, first counts
    the outbox rows for the consent (asserting at least one, which proves the enqueue ran before
    it) and then throws. After the call, assert no outbox row and an unchanged consent. This fails
    if the enqueue is ever moved after `issueIfGranted`, where a `SigningFailureIT`-style test
    would pass vacuously.
  - Commit with no dispatcher running: rows exist in `PENDING` (AC1/AC3).
  - `UserErasureIT`: after erasure and a drain to terminal, no `notification_outbox.payload`
    contains the erased identifier, and every terminal row's payload is null.
- Acceptance: AC1–AC6 evidenced. Existing consent ITs stay green.

### Step 3: Signed webhook sender
One delivery attempt: sign, POST, classify (AC7, and the classification behind AC9–AC10).

- `ConsentManagerConfiguration.ParticipantNotifications` (new nested class, `@Getter @Setter`,
  under `participant-notifications`): for this step `connectTimeout` (default `2s`) and
  `readTimeout` (default `5s`). Steps 4–5 add the rest. Bind env vars
  `PARTICIPANT_NOTIFICATIONS_CONNECT_TIMEOUT` / `_READ_TIMEOUT` in `application.yml`, `.env.sample`
  and the chart.
- `notification/ParticipantWebhookClient`: a dedicated Micronaut `HttpClient`, built like
  `client/SelfDescriptionHttpClientFactory` from the configured timeouts, with
  `followRedirects=false`. It sends `POST` to the endpoint with body = canonical RFC 8785 JSON of
  the payload (`CanonicalJsonSerializer`), `Content-Type: application/json`, and header
  `X-Consent-Signature` = the detached compact JWS from `ConsentSigningService`. The body bytes
  must equal `SignedReceipt.canonicalJson()`, so a recipient verifies exactly what it received
  against `/.well-known/jwks.json`. No new key material.
- Typed signatures: extend `ConsentSigningService` with a small `SignatureType` enum
  (`RECEIPT` → `JOSE`, unchanged; `NOTIFICATION` → `consent-notification+jws`), a
  `sign(payload, type)` overload, and a `verifyCanonical(body, jws, type)` that also requires the
  header `typ`. Existing receipt callers keep the current methods, which mean `RECEIPT`.
- Address fence: extract `SelfDescriptionClient`'s `isExternal`/`isInternal` (and the two
  helpers below them) into a shared `client/NetworkAddressPolicy`, behaviour unchanged, with
  `SelfDescriptionClient` delegating to it. The webhook client refuses a non-https URL, a host
  outside a non-empty `allowed-hosts`, or a host that is not external, *before* connecting,
  returning `ABANDON` with `endpoint not permitted`. Add `allowedHosts` to
  `ParticipantNotifications` (env `PARTICIPANT_NOTIFICATIONS_ALLOWED_HOSTS`, empty default).
- `notification/DeliveryOutcome` (sealed or record + enum): `DELIVERED` for 2xx. `RETRY` with a
  reason for 408, 429, 5xx, connect/read timeout and I/O errors. `ABANDON` with a reason for any
  other 4xx and for 3xx. Keep the status ranges as named constants.
- Tests:
  - `ParticipantWebhookClientTest` uses in-process WireMock over HTTPS, with the test client
    trusting WireMock's certificate through test-only config (never trust-all in main config).
    Parameterized over status → outcome (200, 204, 301, 400, 404, 408, 409, 429, 500, 503), plus a
    fixed-delay stub beyond the read timeout → `RETRY` within roughly the timeout.
  - A signature test asserts the received body is canonical JSON, that
    `verifyCanonical(body, header, NOTIFICATION)` is true (AC7), and, parameterized, that a
    receipt signature fails `NOTIFICATION` verification and a notification signature fails
    `RECEIPT` verification.
  - `NetworkAddressPolicyTest`, parameterized over the address classes (loopback, `10/8`,
    `172.16/12`, `192.168/16`, `169.254/16`, `100.64/10`, `fc00::/7`, `::1`, a public address),
    plus a webhook-client case: an internal endpoint returns `ABANDON`/`endpoint not permitted`
    without a connect attempt, and an `allowed-hosts` entry admits loopback. Existing
    `SelfDescriptionClient` tests stay green unchanged.

### Step 4: Dispatcher with backoff, abandonment and retention
Drain the outbox safely across replicas (AC3, AC8–AC13).

- Extend `ParticipantNotifications`, each with a named default and an env var:
  `enabled` (true), `pollInterval` (`5s`), `batchSize` (50), `perParticipantBatchLimit` (5),
  `concurrency` (8), `lease` (`60s`), `backoff` (list, default `1m,5m,15m,1h,6h,24h`),
  `jitter` (fraction, default `0.1`), `retention` (`30d`). Add a startup validator (pattern:
  `SmtpConfigurationValidator`/`IdentityProviderRegistryValidator`) that requires
  `lease > ceil(batchSize / concurrency) × (connectTimeout + readTimeout) + margin` (named
  constant), `perParticipantBatchLimit < concurrency`, and a non-empty, positive backoff. Each
  refusal names the offending settings. `NotificationConfigurationValidatorTest`, parameterized
  over the defaults (admitted) and each violated rule (refused).
- `notification/NotificationBackoff` (pure): `nextAttemptAt(attemptsSoFar, now, random)` returns
  the scheduled delay ± jitter, or empty once the schedule is exhausted (→ `ABANDONED`). With the
  first attempt plus six retries, the seventh failure abandons.
- `notification/NotificationDispatcher`: `@Scheduled(fixedDelay = "${…poll-interval}")` calling
  `drainOnce()` (public, so tests drive it with `enabled=false`). Each tick:
  1. Claim and lease in one short transaction; keep the token and leased-until time.
  2. For each row, on a bounded pool (reuse the `NotificationThreadPool` pattern, sized by
     `concurrency`): if the lease has less than `connectTimeout + readTimeout` left, drop the row
     untouched (it is re-claimed after expiry). Otherwise resolve the recipient's current
     `endpoints.consentNotification`.
  3. Abandon immediately with a reason if the participant is gone or deregistered, or has no
     endpoint (AC11). Otherwise send through `ParticipantWebhookClient`, which applies the address
     fence first.
  4. Write the outcome in a short, fenced transaction (`lease_token` must still match):
     `DELIVERED` + `delivered_at`, payload nulled. `FAILED` + `attempts+1` + `last_error` + backoff
     `next_attempt_at`. Or `ABANDONED`, payload nulled (AC9, AC10). A 0-row update means the lease
     was lost; discard the outcome and record no metric.
  5. Wait for the batch, bounded by the lease; on expiry cancel the tasks not yet started.
  6. Purge terminal rows older than `retention`.

  Log every abandonment at WARN with participant id, consent id and reason, and never the user
  identifier, which `LogLeakIT` must keep passing.
- Tests:
  - `NotificationBackoffTest`, parameterized: attempt → delay window, then exhaustion.
  - `NotificationDispatcherIT`, against WireMock and Postgres, driving `drainOnce()` with a
    controllable clock or by rewriting `next_attempt_at`. Cover 5xx → `FAILED` with the next
    attempt inside the jitter window, the same `eventId` and signature-verifiable body on the
    retry (AC8), and walking the schedule to `ABANDONED` (AC9). Cover 400 → `ABANDONED` after one
    attempt and 429 → `FAILED` (AC10), and the no-endpoint case → `ABANDONED` with no HTTP call
    (AC11). Cover an expired lease being re-claimed, with the row delivered (AC3), and the stale
    holder's late outcome write updating nothing. Cover a row whose lease is nearly spent never
    being sent. Cover an endpoint resolving to loopback without an `allowed-hosts` entry →
    `ABANDONED` with `endpoint not permitted` and no request reaching WireMock.
  - Isolation: participant A's endpoint stalls past the read timeout with many rows, participant B
    has one row, and B is `DELIVERED` within the first `drainOnce()` (AC12).

### Step 5: Per-participant delivery metrics
Make delivery observable (AC14).

- `observability/NotificationMetrics` (beside `ConsentMetrics`, same terse style), with public
  metric-name constants:
  - `consentmanager.participant.notifications.attempts`: counter, tags `participant` and
    `outcome` (delivered/retry/abandoned).
  - `….abandoned`: counter, tags `participant` and `reason` (a bounded enum, not free text).
  - `….latency`: timer from `created_at` to `delivered_at`, tag `participant`.
  - `….queue.depth`: `MultiGauge` per participant, refreshed each tick from
    `countDueByParticipant()`.

  Tag the participant by its stable `participants.id` UUID. Cardinality is bounded by the
  registered participants.
- The dispatcher calls it after each outcome commits.
- Tests: `NotificationMetricsIT` (or extend `NotificationDispatcherIT`) asserts that the counters
  and timer move per participant on `/prometheus` or the `MeterRegistry`. Check that
  `ManagementEndpointSecurityIT` still passes.

### Step 6: `GET /participants/me/notifications`
Let an integrator see its own delivery history (AC15).

- `api/openapi.yaml`, inline path `/participants/me/notifications`, declared beside the other
  `/participants/me/*` paths and before `/participants/{identifier}`. Set `tags` to a new tag such
  as `ParticipantNotification` (→ `AbstractParticipantNotificationController`), `security:
  [{bearerAuth: [PARTICIPANT]}]`, `x-roles: [PARTICIPANT]`, `x-principal: ParticipantPrincipal`,
  and the shared 401/403 refs. Add `page`/`size` query params mirroring `GET /participants`,
  clamped by a configured max with no spec `maximum`.
- Schema `api/components/schemas/ParticipantNotification.yaml`: `id, eventId, eventType, consentId,
  status, attempts, createdAt, nextAttemptAt, deliveredAt, lastError`, with no nullable
  properties (absent means none). Add an inline wrapper `ParticipantNotificationPage` in
  `openapi.yaml` whose `items` is an internal `#/components/schemas/...` ref, as `ParticipantPage`
  does. Assert the generated field is not `List<Object>` (extend `GeneratedPropertyTypeTest`).
- `api/ParticipantNotificationController` (`@Controller`, extends the generated abstract class)
  and `api/ParticipantNotificationMapper`. Scope through `CallerScope.of()` /
  `requireRegistered()`, so a deregistered participant can still read its own history and an
  unregistered one is refused. Query only by the caller's `participants.id`.
- Update `ParticipantEndpoints.yaml` `consentNotification` description: it is now used, is
  signed, and is delivered at-least-once.
- Guards: `SpecSecurityConsistencyTest.SPECIFIED_OPERATION_COUNT` 34 → 35, a new
  `EndpointRoleMatrixIT.Endpoint` row (it is PARTICIPANT-only, so `participantScopedEndpoints` sweeps
  it), and `SpecCompatibilityIT` (additive). Follow `docs/api-compatibility.md` on whether to
  refresh `api/baseline/openapi.yaml`.
- Tests: `ParticipantNotificationHistoryIT` (`KeycloakAndPostgresTestResource`) seeds rows for the
  realm participant and another participant, and asserts only the caller's rows come back, newest
  first, with paging and clamping. Add the case to `OwnershipMatrixIT` if it fits that matrix.

### Step 7: End-to-end delivery, concurrency and replica ITs
Prove the whole path against a WireMock recipient (AC12, AC13, AC16, AC17).

- `ParticipantNotificationDeliveryIT` (`KeycloakAndPostgresTestResource`, plus HTTPS WireMock
  registered as the participant's `consentNotification` via `PUT /participants/me`). It is
  parameterized over recipient behaviour: 200 → `DELIVERED`, 404 → `ABANDONED` once, 503 →
  `FAILED` then `DELIVERED` on the retry, delay > read timeout → `FAILED`, no endpoint →
  `ABANDONED`. The trigger is a real grant then revoke through the HTTP API. Assert the WireMock
  request carries a body and `X-Consent-Signature` that verify against keys fetched from
  `GET /.well-known/jwks.json` (independent verifier: `support/ReceiptSignatures`).
- `NotificationReplicaConcurrencyIT`: start two independent `ApplicationContext`s (two
  "replicas", scheduler disabled) on the same Postgres, enqueue N notifications for several
  participants, and call `drainOnce()` on both concurrently in loops until the outbox is terminal.
  Assert that WireMock received each `(eventId, participant)` exactly once (AC13, AC17).
- Both ITs set `participant-notifications.allowed-hosts` to the WireMock host (loopback), the
  only way past the address fence; main config never carries it.
- Make sure these ITs disable the scheduler (`participant-notifications.enabled=false` in
  `application-test.yml`, overridden per test only where wanted) so existing ITs are unaffected.

### Step 8: Documentation, ADR and chart
Record the contract for integrators and operators.

- `docs/adr/0016-participant-notifications-use-a-transactional-outbox.md`: outbox versus inline
  send, lease claim with whole-batch lease and fenced outcomes, the which-moves-notify table,
  frozen payload/`eventId`, the erasure bound (identifier only on due rows, nulled on turning
  terminal, at most the backoff schedule plus one lease after enqueue; retention covers only
  identifier-free history), the SSRF fence and redirects off, the typed notification signature,
  and no expiry trigger. Set ADR-0011's status line to "partly superseded by ADR-0016
  (participant delivery)" and leave its body unchanged.
- `docs/security.md`: the webhook's address fence beside the self-description one, the same
  DNS-rebinding residual and how `allowed-hosts` closes it, and the `typ` separation between
  receipt and notification signatures.
- `docs/participant-notifications.md` (integrator guide): payload, header, how to verify the
  detached JWS against `/.well-known/jwks.json` **and require `typ: consent-notification+jws`**,
  that internal or non-public endpoints are refused, **at-least-once delivery, deduplicate on
  `eventId`, no ordering guarantee — read `GET /consents/{id}` or `GET /consents/check` for
  current state**, the retry schedule, which responses abandon, and the history route. Link it from
  `docs/security.md` (authenticity, SSRF note) and the `consentNotification` description.
- `docs/testing.md`: one row per AC (1–17), citing test class or method names.
  `docs/deployment.md` and `charts/consent-manager/README.md`: the new env vars. Run
  `helm lint --strict charts/consent-manager` and `helm template`.
- `AGENTS.md`: replace the "being added" notes with the final state (operation count 35, the
  outbox convention).
- Acceptance: `./mvnw clean verify` green, including the JaCoCo gate, Spotless and every
  build-failing spec guard.
