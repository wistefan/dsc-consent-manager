# Implementation Plan: Consent Manager - Reimplementation - Top Level Readme

## Overview
The repository has no top-level README, and its six guides in `docs/` and sixteen ADRs in
`docs/adr/` have no index tying them together. This plan adds a `README.md` following the
[Best-README-Template](https://github.com/othneildrew/Best-README-Template), a documentation index
(`docs/README.md`) and an ADR index (`docs/adr/README.md`), then adds navigation links so a reader
can move both ways between the README, the indices and every document. Documentation only: no
code, build, spec or test changes.

## Decisions (apply to every step)
- **Structure.** Three levels: `README.md` → `docs/README.md` (guides, grouped by audience) →
  `docs/adr/README.md` (ADRs, grouped by theme). The README also links the key guides directly so
  the most common entry points are one click away.
- **Existing content is not rewritten.** Guides and ADRs only gain navigation lines. An ADR's
  decision text, status and dates are never changed.
- **Template adaptations.** Keep the template's sections, order, `<a id="readme-top"></a>` anchor,
  `<details>` table of contents and "back to top" links. Leave out the logo, screenshots and
  shields.io badges (no external badge services); "Built With" is a plain list. Contact points to
  the GitHub repository and its issues (`https://github.com/wistefan/dsc-consent-manager`), never a
  personal e-mail address.
- **Facts come from the repo, not from memory.** Every command, port, path and env var cited must
  be checked against the file it comes from: `compose.yaml`, `docker/README.md`, `.env.sample`,
  `src/main/resources/application.yml` (`PORT` 8080, `MANAGEMENT_PORT` 9090, `API_PREFIX` `/v1`,
  Swagger UI at `/swagger-ui/`, `/openapi.yaml`, `/.well-known/jwks.json` at the API port's origin
  root), `pom.xml`, `charts/consent-manager/README.md`, `LICENSE` (Apache 2.0).
- **Link check.** Each step verifies, before committing, that every relative link in the files it
  touched resolves (file exists; `#anchor` matches a heading in the target, GitHub slug rules).
  Use a throwaway script outside the repo (e.g. a short Python script run with `python3 -I`), or
  `docker run --rm -v "$PWD":/w -w /w lycheeverse/lychee --offline --include-fragments '**/*.md'`.
  Do not commit a checker or wire one into the build.

## Steps

### Step 1: Documentation and ADR indices
Create the two index pages the README will point at, plus a stub root `README.md` so that their
upward links resolve and Step 1 passes its own link check.

- `README.md` (stub, replaced wholesale in Step 2) — H1 "Consent Manager", the one-line
  description from Step 2 item 1, and a single link to [`docs/README.md`](docs/README.md). Nothing
  else; Step 2 owns its content.
- `docs/README.md` — title "Documentation", one sentence on what lives here, a "Back to the
  [project README](../README.md)" line, then tables grouped by audience, one row per guide with a
  one-line summary taken from the guide's own opening paragraph:
  - **Operators:** `deployment.md`, `security.md`, plus `../charts/consent-manager/README.md`
    (Kubernetes) and `../docker/README.md` (local Compose stack).
  - **Integrators (API clients, participants, catalogs):** `api-compatibility.md`,
    `participant-notifications.md`, `user-identifiers.md`, and the published contract
    `../api/openapi.yaml` / `../api/components/security.yaml`.
  - **Contributors:** `testing.md`, `adr/README.md`, `../docker/wiremock/README.md`.
  Close with a short "Reading order" list for a newcomer (README → security → deployment →
  ADR index).
- `docs/adr/README.md` — title "Architecture Decision Records", one paragraph on what an ADR is
  here and the file naming (`NNNN-kebab-title.md`), links up to `../README.md` (Documentation) and
  `../../README.md`. Then one table per theme with columns *ADR · Title · Status · Summary*:
  - **Identity and token validation:** 0001–0006.
  - **Users, participants and the data subject:** 0007–0010.
  - **Consent records, receipts and notifications:** 0011–0016.
  Status is copied from each ADR's `**Status:**` line in short form (Accepted / Rejected /
  Partly superseded) and names every ADR that line names as superseding it, as a link. The
  complete mapping (every other ADR is plain "Accepted"):
  - 0001 → "Rejected; superseded by 0002". Its own status line names only 0002. ADR 0003's
    `Supersedes` field revisits 0001's discovery half, but that is 0003's claim, not 0001's
    status, so it goes in 0003's row (below), not in 0001's.
  - 0002 → "Partly superseded by 0003 (discovery), 0004 (JWKS retrieval, caching, JWS
    verification)".
  - 0003 → "Accepted; supersedes part of 0002 and revisits 0001's discovery half".
  - 0004 → "Accepted; supersedes part of 0002".
  - 0011 → "Accepted; partly superseded by 0016 (participant delivery)".
  - 0013 → "Accepted; partly superseded by 0014 (receipt signing)".
  - 0014 → "Accepted; supersedes part of 0013".
  Before writing the table, re-read each ADR's `Status` and `Supersedes` fields and stop to report a
  mismatch with this list rather than adjusting it silently. Summary is one line read from the
  ADR's decision section, not invented. Add a short "Adding an ADR" paragraph: next free number, same
  header fields (`Status`, `Date`, `Ticket`), add a row here in the same commit.

Acceptance: all 6 guides and all 16 ADRs appear exactly once in the respective index; every
relative link in the three new files (including the `../README.md` and `../../README.md` upward
links, which reach the stub) resolves; no existing file is modified. Once this step merges, every
file `AGENTS.md` lists for ticket #108 (`README.md`, `docs/README.md`, `docs/adr/README.md`)
exists; only the root README's content is still a stub until Step 2.

### Step 2: Top-level README.md
Replace the Step 1 stub `README.md` at the repo root with the full README, following the
Best-README-Template section order:

1. `<a id="readme-top"></a>`, centred header: "Consent Manager", one-line description (consent
   lifecycle — request, grant, revoke, query, signed receipts — as a stateless OAuth2 resource
   server for a data space), links "Explore the docs »" (`docs/README.md`), "API specification"
   (`api/openapi.yaml`), "Report Bug" / "Request Feature" (GitHub issues).
2. Table of contents in `<details>`.
3. **About The Project** — what problem it solves; the three caller roles (USER, PARTICIPANT,
   CATALOG) and what each does; key capabilities (users and participants directory, privacy
   notices generated from contracts, consent lifecycle state machine, ISO/IEC 29184 and Kantara
   receipts signed as JWS with a public JWKS, subject e-mail and participant webhook
   notifications, ADR-0007 erasure, ADR-0008 deregistration); API-first design (code generated
   from `api/openapi.yaml`). Subsection **Built With**: Java 25, Micronaut 5, PostgreSQL 16 +
   Flyway, OpenAPI Generator, Keycloak (dev/test IdP), Testcontainers, Helm.
4. **Getting Started** — *Prerequisites*: JDK 25 (Temurin), Docker with Compose, no Maven install
   needed (`./mvnw`). *Installation / running locally*: clone; `cp .env.sample .env` if the
   Compose file reads it (verify); `docker compose up -d --build && docker/smoke-test.sh`; table of
   the Compose services and host ports from `docker/README.md`; alternatively `./mvnw mn:run` (dev
   profile) against the Compose PostgreSQL/Keycloak. Link `docker/README.md` for detail.
5. **Usage** — API base `http://localhost:8080/v1`; Swagger UI, `/openapi.yaml`,
   `/.well-known/jwks.json` at the origin root; management on `:9090` (`/health/liveness`,
   `/health/readiness`, `/prometheus`). One worked example: obtain a token from the local Keycloak
   realm (client and user taken from `docker/keycloak/consent-manager-realm.json`, verify they
   exist) and call one read endpoint with `curl`. Pointer to `docs/user-identifiers.md` for
   identifier rules. Configuration: env vars documented in `.env.sample`. Deployment: Helm chart
   in `charts/consent-manager` → its README and `docs/deployment.md`.
6. **Documentation** — table of every guide with audience and one-line purpose, plus the ADR
   index, all linking into `docs/`; link `docs/README.md` as the full index.
7. **Roadmap** — checklist of delivered capabilities (`- [x]`) and a link to GitHub issues for
   open work; no invented future items.
8. **Contributing** — fork/branch/PR flow from the template, adapted: `./mvnw verify` (unit +
   integration, needs Docker), `./mvnw spotless:apply` before committing, API-first rule (change
   `api/openapi.yaml`, never hand-written interfaces; spec-consistency tests fail the build), add
   an ADR for a significant decision (`docs/adr/README.md`), update `docs/testing.md` when adding
   acceptance-criterion tests.
9. **License** — Apache License 2.0, link `LICENSE`.
10. **Contact** — repository URL and issues.
11. **Acknowledgments** — Best-README-Template, Micronaut, OpenAPI Generator, Keycloak,
    Testcontainers, WireMock; standards: ISO/IEC 29184, Kantara Consent Receipt, RFC 7807, RFC 7515
    / 7517 (JWS/JWK).

Each top-level section ends with `<p align="right">(<a href="#readme-top">back to top</a>)</p>`.

Acceptance: every section above is present in that order; every file in `docs/` is linked
directly and every ADR is reachable via `docs/adr/README.md`; all relative links and in-page
anchors resolve; every command, port and path is verified against the repo (list them in the PR
description with the file each was checked against). Update `docs/README.md`'s "Back to" line if
needed.

### Step 3: Cross-link the guides and ADRs
Make navigation bidirectional without rewriting content.

- Each guide in `docs/*.md` (6 files): directly under the H1 add one navigation line
  `[README](../README.md) › [Documentation](README.md) › <Guide title>`; at the end add a
  `## Related documents` section (or extend an existing "see also" if one exists) linking the
  2–4 most relevant guides/ADRs, chosen from links the guide already makes and the audience
  groups in `docs/README.md`.
- Each ADR in `docs/adr/*.md` (16 files): under the H1 add one navigation line
  `[Documentation](../README.md) › [ADR index](README.md) › ADR NNNN`. Nothing else in an ADR
  changes.
- `charts/consent-manager/README.md` and `docker/README.md`: add one line linking back to
  `docs/deployment.md` and the project README.
- Ensure `docs/testing.md`'s and `docs/security.md`'s existing in-page anchors still resolve after
  the insertion (they should — only a line is added under the H1).

Acceptance: every document listed in the indices links back to its index; the link check passes
over `README.md`, `docs/**/*.md`, `docker/README.md`, `charts/consent-manager/README.md`;
`git diff` on the ADRs shows only the added navigation line per file.
