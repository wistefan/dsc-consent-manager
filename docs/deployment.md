# Deployment

[README](../README.md) › [Documentation](README.md) › Deployment

The Consent Manager ships as one container image (`docker/Dockerfile`) and runs as a stateless OAuth2
resource server against PostgreSQL 16 and one or more trusted OIDC identity providers.

| Target | Start here |
| --- | --- |
| Kubernetes | [`charts/consent-manager/README.md`](../charts/consent-manager/README.md): install, required inputs, values, key rotation, probes, migrations |
| Local stack (Compose) | [`docker/README.md`](../docker/README.md): PostgreSQL, Keycloak, WireMock and a smoke test |
| Every environment variable | [`.env.sample`](../.env.sample): meaning, default, and the key-generation commands |

Both targets run the image the same way: a numeric non-root user, a read-only root filesystem with a
writable `/tmp`, and health and metrics on the management port (`9090`) at the origin root, apart
from the API port. Credentials come from a Secret and receipt signing keys from mounted files, never
from a ConfigMap or the image.

Participant webhooks (ADR-0016) need no extra component: an outbox table drained by every replica,
configured by the `PARTICIPANT_NOTIFICATIONS_*` variables (chart: `config.participantNotifications`).
Every replica may deliver, since claims are leased and never overlap. Set `PARTICIPANT_NOTIFICATIONS_ENABLED=false`
to keep a replica from delivering; the sweep that drops undelivered payloads after
`PARTICIPANT_NOTIFICATIONS_MAX_AGE` runs regardless. Startup refuses timing values that cannot
hold together (a lease too short for a batch, a per-participant cap not below the concurrency, a
max age shorter than the retry schedule). Webhook URLs are participant-supplied, so egress must reach
the public internet; `PARTICIPANT_NOTIFICATIONS_ALLOWED_HOSTS` narrows it to listed hosts.
Integrators read [`participant-notifications.md`](participant-notifications.md).

## Related documents

- [`security.md`](security.md): the token, health and signing-key model.
- [ADR-0014](adr/0014-a-grant-receipt-is-signed-once-and-travels-beside-its-signature.md): why
  receipt-key rotation takes two deploys.
- [Participant notifications](participant-notifications.md): the webhook contract the outbox
  delivers to.
