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
  `FOR UPDATE SKIP LOCKED`, pushes their `next_attempt_at` forward by a lease, then commits.
  Delivery runs outside any transaction, and the outcome is written in a second short transaction.
  If the process dies mid-send, the row becomes due again when the lease expires (at-least-once,
  AC3). Startup validation requires the lease to exceed connect timeout + read timeout by a margin,
  so a live delivery is never re-claimed by another replica (AC13/AC17).
- **Isolation.** The claim query caps rows per participant per batch, using a `row_number() OVER
  (PARTITION BY participant_id)` subquery with the outer `SELECT … FOR UPDATE SKIP LOCKED`.
  Deliveries run on a bounded pool with short timeouts, so a dead endpoint can hold at most its
  cap of workers.
- **`last_error`** is a short classification (`HTTP 503`, `read timeout`, `connection refused`,
  `no consentNotification endpoint`, `participant deregistered`). It never holds a response body.
  The history route returns it, and logs may print it.
- **Erasure.** A revocation caused by erasure is enqueued *before* the user is pseudonymised, in
  the same transaction, so its payload carries the identifier the recipients already hold. That is
  the only identifier they can act on. Terminal rows are purged after
  `participant-notifications.retention` (default 30 days), which bounds how long that identifier
  outlives the erasure. Record this in the new ADR.
- **Redirects are not followed** (SSRF hygiene for a participant-supplied URL). A 3xx response is
  a permanent failure.
- **Doc comments**: match the file. New records, enums and config stay at one to three lines. No
  `@param`/`@return` echoes. Named constants only. Parameterized tests wherever cases form a
  table.

## Steps

### Step 1: Outbox schema, domain types and repository
Add the storage layer with no callers yet.

- `src/main/resources/db/migration/V6__notification_outbox.sql`: the ticket's table, plus
  `event_id uuid NOT NULL` (the `consent_events` id), `UNIQUE (event_id, participant_id)` so the
  same event is never queued twice for one recipient, and a `CHECK` on `event_type` (the four
  values). Replace the ticket's index with `notification_outbox_due_idx ON (next_attempt_at)
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
    `claimDue(Instant now, int batchSize, int perParticipant)` as a native query using the
    per-participant `row_number()` subquery and `FOR UPDATE SKIP LOCKED`.
    `extendLease(List<UUID>, Instant until)`. `findByParticipantIdOrderByCreatedAtDesc(UUID,
    Pageable)`. `countDueByParticipant()` for the depth gauge. `deleteTerminalBefore(Instant)` for
    retention.
- Tests: `NotificationOutboxRepositoryIT` (extends `PostgresTestResource`). Cover round-trip of the
  jsonb payload; the claim returns only due `PENDING`/`FAILED` rows; the per-participant cap; and
  two concurrent claims in separate transactions (two threads via `support/Race`) returning disjoint sets. Reset between tests with `support/DatabaseReset` (add the new table if it enumerates tables).
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
  - Rollback: force a failure after enqueue inside the same transaction, and assert no outbox row
    and an unchanged consent (AC2). Use the existing `SigningFailureIT` approach of failing the
    receipt issuer, which runs after `saveNew` in the same transaction, or a `@Replaces` bean that
    throws.
  - Commit with no dispatcher running: rows exist in `PENDING` (AC1/AC3).
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
  `X-Consent-Signature` = the detached compact JWS from `ConsentSigningService.sign(payload)`. The
  body bytes must equal `SignedReceipt.canonicalJson()`, so a recipient verifies exactly what it
  received against `/.well-known/jwks.json`. No new key material.
- `notification/DeliveryOutcome` (sealed or record + enum): `DELIVERED` for 2xx. `RETRY` with a
  reason for 408, 429, 5xx, connect/read timeout and I/O errors. `ABANDON` with a reason for any
  other 4xx and for 3xx. Keep the status ranges as named constants.
- Tests:
  - `ParticipantWebhookClientTest` uses in-process WireMock over HTTPS, with the test client
    trusting WireMock's certificate through test-only config (never trust-all in main config).
    Parameterized over status → outcome (200, 204, 301, 400, 404, 408, 409, 429, 500, 503), plus a
    fixed-delay stub beyond the read timeout → `RETRY` within roughly the timeout.
  - A signature test asserts the received body is canonical JSON and that
    `ConsentSigningService.verifyCanonical(body, header)` is true (AC7).

### Step 4: Dispatcher with backoff, abandonment and retention
Drain the outbox safely across replicas (AC3, AC8–AC13).

- Extend `ParticipantNotifications`, each with a named default and an env var:
  `enabled` (true), `pollInterval` (`5s`), `batchSize` (50), `perParticipantBatchLimit` (5),
  `concurrency` (8), `lease` (`60s`), `backoff` (list, default `1m,5m,15m,1h,6h,24h`),
  `jitter` (fraction, default `0.1`), `retention` (`30d`). Add a startup validator (pattern:
  `SmtpConfigurationValidator`/`IdentityProviderRegistryValidator`) that requires `lease >
  connectTimeout + readTimeout` and a non-empty, positive backoff.
- `notification/NotificationBackoff` (pure): `nextAttemptAt(attemptsSoFar, now, random)` returns
  the scheduled delay ± jitter, or empty once the schedule is exhausted (→ `ABANDONED`). With the
  first attempt plus six retries, the seventh failure abandons.
- `notification/NotificationDispatcher`: `@Scheduled(fixedDelay = "${…poll-interval}")` calling
  `drainOnce()` (public, so tests drive it with `enabled=false`). Each tick:
  1. Claim and lease in one short transaction.
  2. For each row, on a bounded pool (reuse the `NotificationThreadPool` pattern, sized by
     `concurrency`), resolve the recipient's current `endpoints.consentNotification`.
  3. Abandon immediately with a reason if the participant is gone or deregistered, or has no
     endpoint (AC11). Otherwise send through `ParticipantWebhookClient`.
  4. Write the outcome in a short transaction: `DELIVERED` + `delivered_at`. `FAILED` +
     `attempts+1` + `last_error` + backoff `next_attempt_at`. Or `ABANDONED` (AC9, AC10).
  5. Wait for the batch, bounded by the lease.
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
    (AC11). Cover an expired lease being re-claimed, with the row delivered (AC3).
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
- Make sure these ITs disable the scheduler (`participant-notifications.enabled=false` in
  `application-test.yml`, overridden per test only where wanted) so existing ITs are unaffected.

### Step 8: Documentation, ADR and chart
Record the contract for integrators and operators.

- `docs/adr/0016-participant-notifications-use-a-transactional-outbox.md`: outbox versus inline
  send, lease claim, the which-moves-notify table, frozen payload/`eventId`, erasure identifier and
  retention, redirects off, and no expiry trigger. Set ADR-0011's status line to "partly
  superseded by ADR-0016 (participant delivery)" and leave its body unchanged.
- `docs/participant-notifications.md` (integrator guide): payload, header, how to verify the
  detached JWS against `/.well-known/jwks.json`, **at-least-once delivery, deduplicate on
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
