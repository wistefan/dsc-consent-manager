# Deployment

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

Related: [`security.md`](security.md) for the token, health and signing-key model, and
[ADR-0014](adr/0014-a-grant-receipt-is-signed-once-and-travels-beside-its-signature.md) for why
receipt-key rotation takes two deploys.
