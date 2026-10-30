# Documentation

The guides for operating, integrating with and contributing to the Consent Manager.

Back to the [project README](../README.md).

## Operators

| Guide | Summary |
| --- | --- |
| [Deployment](deployment.md) | One container image, run as a stateless OAuth2 resource server against PostgreSQL 16 and trusted OIDC identity providers; where to start for each target. |
| [Security Architecture](security.md) | The resource-server model: no tokens issued, no credentials or sessions held, every request carrying a bearer JWT from a trust list fixed at startup. |
| [Helm chart](../charts/consent-manager/README.md) | Kubernetes install: required inputs, values, key rotation, probes and migrations. |
| [Local stack](../docker/README.md) | The Compose stack (service, PostgreSQL, Keycloak, WireMock), its host ports and the smoke test. |

## Integrators (API clients, participants, catalogs)

| Guide | Summary |
| --- | --- |
| [API compatibility](api-compatibility.md) | The published contract is the bundled `GET /openapi.yaml`; a breaking change must be a decision, not an accident. |
| [Participant notifications](participant-notifications.md) | The contract a participant's receiving endpoint implements to learn when a consent it is party to starts or stops permitting processing. |
| [Identifiers in the user directory](user-identifiers.md) | The one identifier the service matches a user on, versus the names a participant may attach. |
| [OpenAPI specification](../api/openapi.yaml) | The API contract every route is generated from. |
| [Token contract](../api/components/security.yaml) | The `bearerAuth` scheme: every claim, its optionality and the accepted signature algorithms. |

## Contributors

| Guide | Summary |
| --- | --- |
| [Testing](testing.md) | How the suite is organised, and which tests satisfy each acceptance criterion of tickets #83 and #94. |
| [Architecture Decision Records](adr/README.md) | Index of the recorded design decisions, grouped by theme, with their status. |
| [Contract Service stub](../docker/wiremock/README.md) | The WireMock stub the local stack uses in place of the Contract Service. |

## Reading order

1. The [project README](../README.md) for what the service does and how to run it.
2. [Security Architecture](security.md) for the trust model every route depends on.
3. [Deployment](deployment.md) for running it outside a laptop.
4. The [ADR index](adr/README.md) for why it is built the way it is.
