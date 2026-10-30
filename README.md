<a id="readme-top"></a>

<div align="center">

<h3 align="center">Consent Manager</h3>

  <p align="center">
    <a href="https://github.com/wistefan/dsc-consent-manager/actions/workflows/test.yml"><img src="https://github.com/wistefan/dsc-consent-manager/actions/workflows/test.yml/badge.svg" alt="Test"></a>
    <a href="https://github.com/wistefan/dsc-consent-manager/actions/workflows/release.yml"><img src="https://github.com/wistefan/dsc-consent-manager/actions/workflows/release.yml/badge.svg" alt="Release"></a>
  </p>

  <p align="center">
    Manages data subjects' consents in a data space: participants request consent, users grant or
    revoke it, and every decision is recorded, queryable and provable with a signed receipt.
    <br />
    <a href="docs/README.md"><strong>Explore the docs »</strong></a>
    <br />
    <br />
    <a href="api/openapi.yaml">API specification</a>
    &middot;
    <a href="https://github.com/wistefan/dsc-consent-manager/issues/new?labels=bug">Report Bug</a>
    &middot;
    <a href="https://github.com/wistefan/dsc-consent-manager/issues/new?labels=enhancement">Request Feature</a>
  </p>
</div>

<details>
  <summary>Table of Contents</summary>
  <ol>
    <li>
      <a href="#about-the-project">About The Project</a>
      <ul>
        <li><a href="#prometheus-x">Prometheus-X</a></li>
        <li><a href="#built-with">Built With</a></li>
      </ul>
    </li>
    <li>
      <a href="#getting-started">Getting Started</a>
      <ul>
        <li><a href="#prerequisites">Prerequisites</a></li>
        <li><a href="#installation">Installation</a></li>
      </ul>
    </li>
    <li><a href="#usage">Usage</a></li>
    <li><a href="#documentation">Documentation</a></li>
    <li><a href="#roadmap">Roadmap</a></li>
    <li><a href="#contributing">Contributing</a></li>
    <li><a href="#license">License</a></li>
    <li><a href="#contact">Contact</a></li>
    <li><a href="#acknowledgments">Acknowledgments</a></li>
  </ol>
</details>

## About The Project

Participants in a data space exchange data about people under contracts, and processing that data
needs the data subject's consent. The Consent Manager is where that consent lives. A participant
turns a contract into a privacy notice and asks a user to decide on it. The user grants, refuses,
re-confirms or revokes, and each move is recorded with its history. Both sides can then ask
whether consent is in force and prove what was agreed with a signed receipt.

Three caller roles. Each is mapped from a role claim in the caller's bearer token: by default the
values `consent-user`, `consent-participant` and `consent-catalog` under `realm_access.roles`,
configurable through `IDP_CLAIM_ROLES` and `IDP_ROLE_*` (see [`.env.sample`](.env.sample)):

| Role | Who | What it does |
| --- | --- | --- |
| `USER` | a data subject | registers, decides on privacy notices (grant, refuse, revoke, re-confirm, resume), reads its own consents and receipts, lists the contracts its participants take part in, erases its own record |
| `PARTICIPANT` | an organisation in the data space | registers itself, links its users, generates privacy notices from its contracts, requests consent, checks whether consent is in force, terminates consents, receives change notifications |
| `CATALOG` | a data space catalog | searches users, reads the participant directory and privacy notices, relays a user's resume of an open request |

Key capabilities:

- **Users and participants directory.** Registration, participant-to-user links (one at a time or
  in bulk), user search and a paged participant directory.
- **Privacy notices generated from contracts.** A participant names a contract held by the
  Contract Service, and the notice the user decides on is generated from it.
- **Consent lifecycle.** One state machine holds every permitted move; a move it does not list is
  refused, and every move is recorded as an event.
- **Signed receipts.** A consent renders as an ISO/IEC 29184 record or a Kantara Consent Receipt,
  signed as a JWS. The verification keys are published at `/.well-known/jwks.json`.
- **Notifications.** Best-effort e-mail to the data subject when a participant acts on their
  consent, and webhooks to the participants party to a consent, delivered through a
  transactional outbox.
- **Erasure and deregistration.** A user's erasure pseudonymises the record and keeps the audit
  trail ([ADR-0007](docs/adr/0007-erasure-pseudonymises-the-user-and-retains-the-audit-trail.md)).
  A participant's deregistration is refused while a consent it is party to is granted; otherwise it
  closes its open business and keeps its record wherever consents or notices still refer to it
  ([ADR-0008](docs/adr/0008-deregistration-retains-the-participant-and-closes-its-open-business.md)).

**API-first.** The hand-written [`api/openapi.yaml`](api/openapi.yaml) is the contract. The server
interfaces and models are generated from it at build time, and the build fails when the spec and
the implementation disagree on routes or security.

Architecturally, the service is a stateless OAuth2 resource server. It validates bearer JWTs
against identity providers on a trust list fixed at startup, and it issues no tokens and holds no
sessions or credentials (see [Security Architecture](docs/security.md)).

### Prometheus-X

The API follows the one defined by the [Prometheus-X](https://github.com/Prometheus-X-association/docs)
data space association, so this service can take the
[Consent Manager](https://prometheus-x-association.github.io/docs/consent-manager/) role in a
Prometheus-X (PTX) data space architecture. There, the Consent Manager is the personal data
intermediary through which individuals give, review and revoke consent for sharing their personal
data, next to the Catalog, the Contract Manager (called the Contract Service in this repository's
configuration, e.g. `CONTRACT_SERVICE_URL`) and the Dataspace Connector. The consent routes keep
the Prometheus-X shape: `POST /users/register`, `GET /consents/me` and `/consents/me/{id}`,
`POST /consents/{id}/refuse`, `/re-confirm` and `/terminate`, and `POST /participants`. Privacy
notices are generated from the contracts held by the Contract Service.

The main deliberate difference is authentication: callers present OAuth2 bearer tokens from a
trusted identity provider, and the service itself has no login or token endpoints. The two APIs
are not identical otherwise either. This service adds routes Prometheus-X does not describe, among
them `POST /consents/requests`, `GET /consents/check`, `POST /consents/{id}/resume`, the receipt
formats of `GET /consents/{id}` and `GET /contracts/available`. The reference implementation, in
turn, has routes this service lacks, such as `/users/login` and `/consents/{id}/token`. The
reference implementation is
[Prometheus-X-association/consent-manager](https://github.com/Prometheus-X-association/consent-manager).

### Built With

- Java 25
- Micronaut 5 (Netty, Data JDBC, Security JWT)
- PostgreSQL 16, with Flyway migrations
- OpenAPI Generator
- Keycloak (identity provider for local development and tests)
- Testcontainers and WireMock
- Helm

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Getting Started

### Prerequisites

- JDK 25 (for example [Eclipse Temurin](https://adoptium.net/)), to build and test outside Docker
- Docker with Docker Compose
- No Maven install: the repository ships the wrapper `./mvnw`
- `curl` and `jq`, for the smoke test

### Installation

1. Clone the repository.

   ```bash
   git clone https://github.com/wistefan/dsc-consent-manager.git
   cd dsc-consent-manager
   ```

2. Optionally copy the environment sample. `compose.yaml` reads `.env` when it exists and runs
   without it.

   ```bash
   cp .env.sample .env
   ```

3. Start the local stack and run the end-to-end smoke test.

   ```bash
   docker compose up -d --build && docker/smoke-test.sh
   ```

   | Service | Host port | Purpose |
   | --- | --- | --- |
   | `consent-manager` | `8080` (API), `9090` (management) | the service, `dev` profile |
   | `postgres` | `5432` | database |
   | `keycloak` | `8180` | identity provider, realm `consent-manager` |
   | `wiremock` | `8081` | Contract Service stub |

   The smoke test registers two participants, generates a privacy notice from the stubbed
   contract and grants consent as `test-user`. [`docker/README.md`](docker/README.md) has the
   detail, including the database outage check.

To run the service from source instead, start only its dependencies and launch it in the `dev`
environment. Keycloak stamps `http://keycloak:8180/...` into every token's `iss`, so the host
must resolve `keycloak` (add `127.0.0.1 keycloak` to `/etc/hosts`):

```bash
docker compose up -d postgres keycloak wiremock
MICRONAUT_ENVIRONMENTS=dev \
IDP_ISSUER=http://keycloak:8180/realms/consent-manager \
IDP_DISCOVERY_URL=http://keycloak:8180/realms/consent-manager/.well-known/openid-configuration \
./mvnw mn:run
```

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Usage

With the local stack running:

| What | Where |
| --- | --- |
| API base | `http://localhost:8080/v1` |
| Swagger UI | `http://localhost:8080/swagger-ui/index.html` |
| Bundled specification | `http://localhost:8080/openapi.yaml` |
| Receipt verification keys (JWKS) | `http://localhost:8080/.well-known/jwks.json` |
| Liveness / readiness | `http://localhost:9090/health/liveness`, `http://localhost:9090/health/readiness` |
| Prometheus metrics | `http://localhost:9090/prometheus` (bearer token required unless `METRICS_AUTHENTICATION_ENABLED=false`) |

Only the API controllers sit under `/v1`; Swagger UI, the spec and the JWKS are at the origin
root, and the management endpoints are on their own port.

Every API call needs a bearer token. The local Keycloak realm
([`docker/keycloak/consent-manager-realm.json`](docker/keycloak/consent-manager-realm.json))
has one client per role and test users sharing the password `test-password`:

| Role | Client (secret) | Users |
| --- | --- | --- |
| `USER` | `consent-manager-user-client` (`user-secret`) | `test-user` |
| `PARTICIPANT` | `consent-manager-participant-client` (`participant-secret`) | `test-participant`, `test-participant-2` |
| `CATALOG` | `consent-manager-catalog-client` (`catalog-secret`) | `test-catalog` |

Take a token as `test-user` and read the participant directory:

```bash
TOKEN=$(curl -s http://localhost:8180/realms/consent-manager/protocol/openid-connect/token \
  -d grant_type=password \
  -d client_id=consent-manager-user-client -d client_secret=user-secret \
  -d username=test-user -d password=test-password | jq -r .access_token)

curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/v1/participants | jq
```

After the smoke test, the page lists the two participants it registered.

- **Identifiers.** Users and participants are addressed by a global identifier, often URI-shaped.
  Read [Identifiers in the user directory](docs/user-identifiers.md) before putting one in a path.
- **Configuration.** Every setting is an environment variable, documented in
  [`.env.sample`](.env.sample).
- **Deployment.** The Helm chart in [`charts/consent-manager`](charts/consent-manager/README.md)
  installs the service on Kubernetes; [Deployment](docs/deployment.md) covers the other targets.
- **Releases / container image.** Every merge to `main` publishes the image
  `quay.io/fiware/consent-manager:<version>` and a GitHub release; see
  [CI and releases](docs/ci.md).

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Documentation

| Document | Audience | Purpose |
| --- | --- | --- |
| [Deployment](docs/deployment.md) | operators | Running the image against PostgreSQL and trusted identity providers. |
| [Security Architecture](docs/security.md) | operators, integrators | How callers are authenticated and authorised. |
| [API compatibility](docs/api-compatibility.md) | integrators | What the published contract promises, and what counts as a breaking change. |
| [Participant notifications](docs/participant-notifications.md) | integrators | The contract a participant's webhook receiver implements. |
| [Identifiers in the user directory](docs/user-identifiers.md) | integrators | The identifier the service matches a user on, and how to address it. |
| [CI and releases](docs/ci.md) | contributors | The workflows, the semver label a PR needs, and how releases are versioned and published. |
| [Testing](docs/testing.md) | contributors | How the suite is organised and which tests meet each acceptance criterion. |
| [Architecture Decision Records](docs/adr/README.md) | contributors | Why the service is built the way it is, grouped by theme. |

The [documentation index](docs/README.md) is the full list, including the Helm chart, local stack
and Contract Service stub guides.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Roadmap

- [x] User and participant registration, participant-to-user links and bulk registration
- [x] Participant directory and user search
- [x] Privacy notices generated from Contract Service contracts
- [x] Consent requests, decisions and the full lifecycle (grant, refuse, revoke, re-confirm,
      resume, terminate)
- [x] Consent queries for subjects and participants, including "is consent in force now"
- [x] ISO/IEC 29184 and Kantara receipts signed as JWS, with a public JWKS
- [x] Subject e-mail notifications and participant webhook notifications
- [x] User erasure and participant deregistration
- [x] Container image, Compose stack and Helm chart

Open work is tracked in the [issues](https://github.com/wistefan/dsc-consent-manager/issues).

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Contributing

Contributions are welcome. For anything beyond a small fix, open an issue first so the approach
can be agreed.

1. Fork the project.
2. Create a branch (`git checkout -b feature/my-change`).
3. Make the change, keeping to the rules below.
4. Run `./mvnw spotless:apply` and `./mvnw verify`.
5. Commit and push the branch, then open a pull request.

- **Build and test.** `./mvnw verify` generates the API sources, compiles and runs unit and
  integration tests. The integration tests start PostgreSQL and Keycloak with Testcontainers, so
  Docker must be running. `./mvnw test` runs the unit tests only.
- **API-first.** Change the API in [`api/openapi.yaml`](api/openapi.yaml) and implement the
  generated controller; never hand-write API interfaces or models. Spec consistency tests fail the
  build when the spec and the implementation disagree.
- **Decisions.** Record a significant design decision as an ADR (see
  [Adding an ADR](docs/adr/README.md#adding-an-adr)).
- **Tests.** When a test covers an acceptance criterion, update its row in
  [`docs/testing.md`](docs/testing.md).
- **Labels.** A pull request into `main` needs exactly one of the labels `major`, `minor` or
  `patch`, which decides the version its merge releases (see [CI and releases](docs/ci.md)).

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## License

Distributed under the Apache License 2.0. See [`LICENSE`](LICENSE).

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Contact

Project: [github.com/wistefan/dsc-consent-manager](https://github.com/wistefan/dsc-consent-manager)

Questions, bugs and feature requests: [issues](https://github.com/wistefan/dsc-consent-manager/issues)

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Acknowledgments

- [Best-README-Template](https://github.com/othneildrew/Best-README-Template)
- [Prometheus-X](https://github.com/Prometheus-X-association/docs), whose Consent Manager API this
  service follows
- [Micronaut](https://micronaut.io/)
- [OpenAPI Generator](https://openapi-generator.tech/)
- [Keycloak](https://www.keycloak.org/)
- [Testcontainers](https://testcontainers.com/)
- [WireMock](https://wiremock.org/)
- Standards: ISO/IEC 29184 (online privacy notices and consent),
  [Kantara Consent Receipt](https://kantarainitiative.org/),
  [RFC 7807](https://www.rfc-editor.org/rfc/rfc7807) (problem details),
  [RFC 7515](https://www.rfc-editor.org/rfc/rfc7515) (JWS),
  [RFC 7517](https://www.rfc-editor.org/rfc/rfc7517) (JWK)

<p align="right">(<a href="#readme-top">back to top</a>)</p>
