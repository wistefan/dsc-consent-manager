# Participant notifications — integrator guide

[README](../README.md) › [Documentation](README.md) › Participant notifications — integrator guide

The Consent Manager tells a participant when a consent it is a party to (as provider or consumer)
starts or stops permitting processing. This page is the contract a receiving endpoint implements.
The design and its trade-offs are recorded in
[ADR-0016](adr/0016-participant-notifications-use-a-transactional-outbox.md).

## Registering an endpoint

Set `endpoints.consentNotification` on your participant record (`POST /participants` or
`PUT /participants/me`). It must be an absolute `https` URL. Without it nothing is delivered, and
what was queued for you is abandoned with `no consentNotification endpoint` (you can still read it
from the [history route](#delivery-history)). The endpoint is read at each attempt, so fixing a
broken URL lets queued notifications reach it.

**Internal and non-public endpoints are refused.** Before connecting, the service resolves your
host and abandons the notification with `endpoint not permitted` if any of its addresses is
loopback, link-local, private (RFC 1918), CGNAT (`100.64.0.0/10`), unique-local (`fc00::/7`),
any-local or multicast. Redirects are never followed: a `3xx` answer abandons the notification.
An operator may restrict delivery to an allow-list of hosts, in which case only listed hosts are
reached. See [security.md](security.md#outbound-fetches).

## What is sent

| Event | Sent when the consent moves | Sent to |
|---|---|---|
| `CONSENT_GRANTED` | into `GRANTED` (a grant, or the resume of a pending request) | provider and consumer |
| `CONSENT_REVOKED` | from `GRANTED` to `REVOKED` (the subject revokes, grants the notice again, or is erased) | provider and consumer |
| `CONSENT_TERMINATED` | from `GRANTED` to `TERMINATED` | the party that did not terminate it; both if the service did |
| `CONSENT_EXPIRED` | from `GRANTED` to `EXPIRED` | provider and consumer |

Nothing is sent for a request created or refused, a re-confirmation, or a pending request that is
closed: none of them changes what you may do. A participant on both sides receives one
notification, not two.

Each notification is one `POST` with `Content-Type: application/json`. The body is the
[RFC 8785](https://www.rfc-editor.org/rfc/rfc8785) canonical JSON of:

```json
{"consentId":"0193a6f2-5c1e-7b3a-9d40-2f6c8e1a7b55","consented":false,"eventId":"0193a6f3-01d4-7e21-8a6b-3c9f0d2e4a17","eventType":"CONSENT_REVOKED","occurredAt":"2026-10-29T13:46:16.003512Z","privacyNoticeId":"0193a1c0-7f2a-7c55-b1e8-6d4a9e0c3f21","status":"REVOKED","userIdentifier":"did:example:alice"}
```

| Field | Meaning |
|---|---|
| `eventType` | One of the four events above. |
| `eventId` | The consent event reported. Shared by every recipient of the event and identical on every retry: **deduplicate on it.** |
| `occurredAt` | When the move was recorded. |
| `consentId` | The consent moved. A new grant is a new consent, so key your state on this. |
| `privacyNoticeId` | The notice the consent answers. |
| `userIdentifier` | The data subject, as you registered them. |
| `status`, `consented` | The consent's status after the move; `consented` is true only for `GRANTED`. |

No notice content, purpose or snapshot is included. Fetch the consent with
`GET /consents/{id}` if you need it.

## Verifying the signature

The `X-Consent-Signature` header carries a detached compact JWS
([RFC 7515 Appendix F](https://www.rfc-editor.org/rfc/rfc7515#appendix-F)): `<header>..<signature>`,
with the payload left out. The payload is exactly the request body. To verify:

1. Keep the **raw body bytes**. Never re-serialise parsed JSON before verifying.
2. Decode the protected header. **Require `typ` to be `consent-notification+jws`.** The same key
   signs consent receipts under `typ: JOSE`; refusing any other `typ` stops a receipt signature
   being passed off as a notification.
3. Accept only the algorithms you expect (the service signs with ES256, ES384, ES512, RS256 or
   PS256, whichever its key is), and never `none`.
4. Fetch `https://<consent-manager-host>/.well-known/jwks.json` (at the origin root, not under the
   API prefix) from the deployment you trust, and select the key whose `kid` the header names. The
   header carries no `jku`. Cache the set, and refetch once on an unknown `kid`, since keys rotate.
5. Verify the signature over `<header>.` + base64url(body).

With Nimbus JOSE+JWT, for example:

```java
JWSObject jws = JWSObject.parse(signatureHeader, new Payload(rawBody));
if (!new JOSEObjectType("consent-notification+jws").equals(jws.getHeader().getType())) {
    reject();
}
JWK key = jwks.getKeyByKeyId(jws.getHeader().getKeyID());
boolean genuine = key != null && jws.verify(new DefaultJWSVerifierFactory()
        .createJWSVerifier(jws.getHeader(), key.toECKey().toPublicKey())); // or toRSAKey()
```

**A genuine notification does not name its recipient.** One participant can forward a notification
it received to another participant's endpoint, and the signature still verifies. Ignore any event
whose `consentId` names a consent you are not provider or consumer of; `GET /consents/{id}` answers
`404` for those.

## Delivery semantics

**At least once.** A notification is written in the same transaction as the move, so a recorded
move is always notified and a rolled-back one never is. A crash, a lost lease or a timeout after
your endpoint accepted it can cause a second delivery of the same body. Deduplicate on `eventId`.

**No ordering.** Notifications are sent concurrently, retries interleave with new events, and
nothing orders them by `occurredAt`. Treat each one as a prompt to check, and read
`GET /consents/{id}` or `GET /consents/check` for the current state.

One case is certain to arrive out of order: when a subject grants a notice they have already
granted, the old consent is revoked and a new one granted in the same transaction. You receive
`CONSENT_REVOKED` for the old `consentId` and `CONSENT_GRANTED` for the new one, queued at the same
instant and delivered in either order. **Key your state on `consentId`**, or re-read
`GET /consents/check`. Never apply events per `(userIdentifier, privacyNoticeId)` in arrival order:
a revocation that lands second would wrongly switch off the consent that is in force.

## Responding and retries

Answer quickly with a `2xx` and process asynchronously. The service gives up on an attempt after
the deployment's connect plus read timeout (by default 2 s + 5 s), and the body is never read.

| Your answer | Result |
|---|---|
| `2xx` | Delivered. |
| `408`, `429`, `5xx`, timeout, refused or reset connection, failed name lookup | Retried. |
| `3xx` (redirects are not followed), any other `4xx` | Abandoned at once. |

Retries follow the deployment's schedule, by default 1 min, 5 min, 15 min, 1 h, 6 h and 24 h after
the previous failure, each ±10%. A notification that fails its seventh attempt (about 31 hours
after the move) is abandoned. Every notification is also abandoned if it is still undelivered after
the deployment's maximum age (default 36 hours). Each retry carries the same body, signature and
`eventId`.

When your participant record is deregistered, its endpoints are cleared and everything still queued
for it is abandoned with `participant deregistered`.

## Delivery history

`GET /v1/participants/me/notifications?page=0&size=20` (participant token) lists the notifications
queued for you, newest first, with `status` (`PENDING`, `FAILED` with a retry scheduled,
`DELIVERED`, `ABANDONED`), `attempts`, `nextAttemptAt`, `deliveredAt` and `lastError`. `lastError` is
a short classification such as `HTTP 503`, `read timeout`, `connection refused`,
`name resolution failed`, `endpoint not permitted`, `no consentNotification endpoint`,
`participant deregistered` or `max age exceeded`, never a response body. Entries do not carry the
signed body or the data subject. Final entries are kept for the deployment's retention period
(default 30 days). A deregistered participant can still read its history. The operation is
described in full in `api/openapi.yaml`.

## Related documents

- [ADR-0016](adr/0016-participant-notifications-use-a-transactional-outbox.md): why delivery uses a transactional outbox.
- [ADR-0011](adr/0011-a-lifecycle-move-is-recorded-here-and-notified-elsewhere.md): why a lifecycle move is recorded here and notified elsewhere.
- [Security Architecture — Outbound fetches](security.md#outbound-fetches): the egress rules a webhook URL is held to.
- [Deployment](deployment.md): the `PARTICIPANT_NOTIFICATIONS_*` settings an operator tunes.
