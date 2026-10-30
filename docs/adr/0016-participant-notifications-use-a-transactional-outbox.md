# ADR 0016 — Participant notifications use a transactional outbox

[Documentation](../README.md) › [ADR index](README.md) › ADR 0016

- **Status:** Accepted.
- **Date:** 2026-10-29
- **Ticket:** Taiga #94 (participant notification)
- **Relates to:** `notification_outbox` (`V6__notification_outbox.sql`); the participant half of
  the `…consentmanager.notification` package (`ParticipantNotificationService`,
  `NotificationRecipients`, `NotificationOutboxRepository`, `NotificationDispatcher`,
  `NotificationOutboxSweeper`, `NotificationBackoff`, `ParticipantWebhookClient`,
  `NotificationConfigurationValidator`); `client/NetworkAddressPolicy`; `signing/SignatureType`;
  `GET /participants/me/notifications`;
  [ADR-0011](0011-a-lifecycle-move-is-recorded-here-and-notified-elsewhere.md), whose deferred
  participant delivery this ADR supplies; [ADR-0007](0007-erasure-pseudonymises-the-user-and-retains-the-audit-trail.md)
  and [ADR-0008](0008-deregistration-retains-the-participant-and-closes-its-open-business.md),
  whose sweeps it joins; [ADR-0014](0014-a-grant-receipt-is-signed-once-and-travels-beside-its-signature.md),
  whose key and canonical form it reuses. Integrator contract: `docs/participant-notifications.md`.

## Context

ADR-0011 made a consent move effective in this service's records when its transaction commits,
and left telling the parties to a later ticket. A provider or consumer that keeps processing after
a revocation is the failure that matters, so a participant must learn of every move that changes
what it may do, even across crashes, restarts, a dead endpoint and several replicas, and it must be
able to prove the message came from here.

Sending inline from the request cannot give that. A send before commit announces a move that may
still roll back. A send after commit is lost when the process dies between the two, and holds the
caller's request on someone else's endpoint. Subject email (ADR-0015) accepts that loss because it
is informational; a revocation to a processor is not.

## Decision

**The move and its notifications commit together.** Every consent write path
(`ConsentWriter.saveNew`/`saveUpdated`, `UserService.close` for erasure,
`ParticipantService.terminate` for deregistration) calls
`ParticipantNotificationService.enqueue` inside its own transaction, which is
`@Transactional(MANDATORY)` so a caller outside one fails loudly. The enqueue runs before the
grant receipt is issued, so a receipt failure rolls the rows back with the move. A write path added
later must enqueue too. One `notification_outbox` row is written per recipient; delivery happens
later, from the table.

**Only a move that creates or withdraws permission notifies**, which means the held or the target
status is `GRANTED`. `NotificationRecipients` is the one table:

| Held → target | Event | Recipients |
|---|---|---|
| (new) / `PENDING` / `DRAFT` → `GRANTED`, incl. resume | `CONSENT_GRANTED` | provider and consumer |
| `GRANTED` → `REVOKED` (subject revoke, a decision superseding a grant, erasure) | `CONSENT_REVOKED` | provider and consumer |
| `GRANTED` → `TERMINATED` | `CONSENT_TERMINATED` | the party that did not initiate it; both if the system did |
| `GRANTED` → `EXPIRED` | `CONSENT_EXPIRED` | provider and consumer |

Creation in `PENDING`/`DRAFT`, `REFUSED`, re-confirmation (an event, not a move) and
`PENDING`/`DRAFT` → `TERMINATED` notify nobody, so the cleanup, deregistration and termination of
unanswered requests send nothing. A participant on both sides gets one row.

**The payload is frozen and minimal.** It is stored as `jsonb` at enqueue: `eventType`, `eventId`,
`occurredAt`, `consentId`, `privacyNoticeId`, `userIdentifier`, `status`, `consented`, and no
snapshot or notice content. `eventId` is the move's `consent_events.id`; both recipients share it,
and it never changes across retries, so it is the recipient's deduplication key. The signed bytes
are the same on every attempt. The *endpoint* is read at dispatch time, so a participant that fixes
its URL receives what is still queued.

**Claims are leased, not locked across HTTP.** A short transaction selects due rows
`FOR UPDATE OF o SKIP LOCKED`, sets `next_attempt_at = now() + lease` and a fresh `lease_token`, and
commits. Delivery runs outside any transaction. Each outcome is written in a second short
transaction as `UPDATE … WHERE id = :id AND lease_token = :token`. Zero rows updated means the
lease was lost to another replica, and the late outcome is discarded without a metric, so a slow
replica can never overwrite a row another one already moved. A process that dies mid-send leaves
the row due again once the lease expires: delivery is at least once.

**The claim query states the due predicates twice, on purpose.** Postgres refuses `FOR UPDATE`
over a window function, so the per-participant ranking is a joined subquery `r` and only the base
table `o` is locked. `r` is evaluated once, against the statement snapshot. If another replica
leases a row and commits after that snapshot, READ COMMITTED's EvalPlanQual re-check evaluates
only the quals on the locked relation. Without the outer copy on `o` the re-check passes and the
row is sent twice. `NotificationOutboxRepositoryIT.recheckDropsARowLeasedAfterTheSnapshot` stages
the race with a waiting lock and fails if the outer predicates go.

**The lease covers the whole batch, and two clocks are each used where they are safe.** One send
is bounded by `sendBound = resolveTimeout + connectTimeout + readTimeout`; the client also caps the
whole exchange at `connectTimeout + readTimeout`, because its read timeout is only an idle limit. A
batch runs in waves of `concurrency`, and startup refuses a lease that does not exceed
`ceil(batchSize / concurrency) × sendBound + 10s` (defaults: `4 × 9s + 10s = 46s < 60s`). Every
stored time (lease expiry, retry time, `delivered_at`, the age and retention cut-offs) is computed
by the database from `now()`, the clock every replica's claim compares against, so replica clock
skew cannot move it. The decision whether to *start* a send never reads a wall clock: the
dispatcher reads `System.nanoTime()` before opening the claim transaction, and its deadline is that
reading plus the lease, which precedes the database's expiry whatever the skew. A row with less
than `sendBound + 10s` left is dropped from the batch untouched and re-claimed after expiry. A stall
longer than the margin between that check and the connect (a long GC pause) can still produce a
duplicate POST; that residue is what at-least-once and `eventId` deduplication exist for.

**Retries are durable.** The attempt count and next attempt time live on the row; Micronaut
`@Retryable` would hold a worker and the lease for a ~31 h schedule and lose the retry on restart.
`NotificationBackoff` computes the next delay from `participant-notifications.backoff` (default
`1m,5m,15m,1h,6h,24h`, ±`jitter` 10%), and `markFailed` adds it to the database's `now()`. 2xx
delivers. 408, 429, 5xx, timeouts, refused connections, I/O errors and failed name lookups retry.
Every other 4xx and every 3xx abandon at once, and the seventh failure abandons. `attempts` counts
dispatch outcomes: each fenced write adds one, so a first-time delivery and an immediate `400`
both show `1`. The sweeper's and deregistration's abandonments, and a row dropped for lack of
lease, leave it unchanged.

**A dead endpoint cannot take the pool.** The claim caps rows per participant per batch
(`perParticipantBatchLimit`, default 5), deliveries run on a pool of `concurrency` (default 16),
and startup requires the cap to stay below the pool. Up to
`floor((concurrency − 1) / perParticipantBatchLimit)` simultaneously dead endpoints (3 with the
defaults) therefore leave a worker free for everyone else. Beyond that, healthy rows wait at most
one `sendBound` wave and are never starved. Because a tick waits for its batch and the poll is
`fixedDelay`, a dead endpoint delays the next tick by at most one `sendBound`. Name resolution runs
on its own bounded executor, and concurrent lookups of one host share a thread, so a host whose DNS
stalls holds one resolver thread however many rows it has.

**There is no ordering guarantee.** Rows are dispatched concurrently. The case certain to arrive
out of order is a re-grant of a notice the subject has already granted: `ConsentWriter.decide`
revokes the old consent and grants a new one in one transaction, so `CONSENT_REVOKED` (old
`consentId`) and `CONSENT_GRANTED` (new `consentId`) share `created_at` and may arrive either way
round. Recipients key state on `consentId` or re-read `GET /consents/check`. Ordering per
participant was rejected: it would let one stuck row hold back every later one, which is the
isolation this design exists to keep.

**The endpoint is fenced like a self-description URL.** `consentNotification` is supplied by the
participant, so it is a server-side request forgery primitive. `NetworkAddressPolicy`, extracted
unchanged from `SelfDescriptionClient`, refuses a host with any loopback, link-local, site-local,
any-local, multicast, CGNAT (`100.64.0.0/10`) or ULA (`fc00::/7`) address, and a non-https URL,
before any connect; the row is abandoned with the fixed reason `endpoint not permitted`, so the
history route never says whether an internal host answers. Redirects are not followed and a 3xx
abandons. The JDK resolver has no timeout, so the lookup runs under `resolveTimeout` (default `2s`)
on the resolver executor. A lookup that fails or times out is retried with `name resolution
failed`, not abandoned, because `UnknownHostException` also covers SERVFAIL and a brief DNS outage
must not drop a revocation; a really dead name is abandoned when the schedule runs out. Two
residuals are accepted. Retry versus abandon shows whether a name *resolves* in this service's DNS
view, never whether anything answers on it. And the check and the connect resolve separately, so
DNS rebinding between them stays open exactly as for self-descriptions. A non-empty
`participant-notifications.allowed-hosts` closes both: only listed hosts are delivered to, and they
waive the address check.

**Notifications are signed under their own `typ`.** The body is the RFC 8785 canonical JSON of the
payload; `X-Consent-Signature` carries a detached compact JWS over it, signed with the receipt key
and published at `/.well-known/jwks.json`. Receipts keep `typ: JOSE`, so existing receipts still
verify; notifications carry `typ: consent-notification+jws`, and `verifyCanonical(…, type)`
requires the header to match, so a signature over one kind of document never verifies as the
other. No new key material is introduced. The signed payload does not name its recipient, so a
genuine notification can be replayed to another participant's endpoint; a recipient ignores
consents it is not a party to.

**The subject identifier stays only while it must be sent.** ADR-0007 and ADR-0014 do not let a
stored artefact keep naming an erased subject. Terminal rows carry no payload: the write that makes
a row `DELIVERED` or `ABANDONED` nulls it, and V6 enforces
`CHECK (status IN ('PENDING','FAILED') OR payload IS NULL)`. The history route reads columns only.
A revocation caused by erasure is enqueued before the user is pseudonymised, so it carries the
identifier the recipients hold, and due rows keep theirs until they turn terminal. On schedule
that happens within `sum(backoff) × (1 + jitter) + (backoff.size() + 1) × (lease + pollInterval)`,
about 34.6 h with the defaults. `NotificationOutboxSweeper` enforces the bound when delivery does
not: on startup and every `sweep-interval`, **whatever `enabled` says**, it abandons each due row
older than `max-age` (default `36h`) with `max age exceeded`, nulls its payload and clears its
lease token so an in-flight outcome is discarded, and the claim never selects such a row. Startup
refuses a `max-age` shorter than the schedule above, so the sweeper cannot cut the last retry of a
revocation. An identifier therefore leaves the outbox at most `max-age + sweepInterval` after
enqueue while any replica runs; with none running nothing reads it, and the first sweep on startup
redacts. `retention` (default 30 days) only bounds the identifier-free history.

**Deregistration abandons what is still queued for the departing participant.** The retain branch
of `ParticipantService.deregister` clears `endpoints` (ADR-0008), so a due row has nowhere left to
go. `deregister` calls `abandonForParticipant` in its transaction: each due row becomes `ABANDONED`
with `participant deregistered`, no payload and no lease token. The revocation is not lost to the
participant: a deregistered participant still passes `requireRegistered()` and can read it from
`GET /participants/me/notifications` and `GET /consents/check`. Refusing deregistration while a row
is owed was rejected, because it would tie the participant's exit to its own endpoint's health for
up to `max-age`. The dispatcher abandons a row for a missing or deregistered participant with the
same reason, which covers a row enqueued in a race with deregistration. The delete branch never
meets an outbox row, because every row names a consent the participant is party to, and such a
consent forces the retain branch.

**Expiry has no trigger.** Only the `GRANTED → EXPIRED` edge exists in `ConsentStateMachine`, and
this ADR adds no job. The hook sits on the shared write path, so a future expiry mechanism notifies
without further change.

## Consequences

- A committed move is always followed by an attempt, and a rolled-back one never is. Delivery is at
  least once, unordered, and deduplicated by the recipient on `eventId`.
- Each replica runs a dispatcher (`enabled`, default on) and a sweeper (always). Replicas coordinate
  only through `SKIP LOCKED`, leases and fenced writes; there is no leader.
- `NotificationConfigurationValidator` refuses at startup a lease too short for a batch, a
  per-participant cap not below `concurrency`, too few resolver threads for the isolation bound, an
  empty or non-positive backoff, and a `max-age` shorter than the schedule. Each refusal names the
  settings.
- `consentmanager.participant.notifications.{attempts,abandoned,latency,queue.depth}` are tagged by
  `participants.id`, so their cardinality is bounded by registered participants.
- `last_error` is a short classification and never a response body; it is served by the history
  route and may be logged. Abandonments are logged at WARN with participant id, consent id and
  reason, never the user identifier.
- Subject email (ADR-0015) is unchanged and remains best-effort. ADR-0011 is partly superseded:
  `ConsentStateChangeLog` still records every move, and participant delivery is now this outbox.
