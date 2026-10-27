# Implementation Plan: Consent Manager - Reimplementation - Deployment

## Overview
Package and deploy the service: harden the existing container image and Compose stack, let the
application load its receipt signing keys from mounted files, add a Helm chart under
`charts/consent-manager/`, and document key rotation and migration strategy. Most local pieces
already exist (`docker/Dockerfile`, `compose.yaml`, `docker/keycloak/consent-manager-realm.json`,
`docker/wiremock/`, `.env.sample`, `.dockerignore`). The work hardens them and adds the chart.

Where the ticket text conflicts with the codebase, these decisions apply (from the analysis):
- **Java 25, not 21.** The project targets Java 25, so the images are `eclipse-temurin:25-*`.
- **Probes go to the management port.** Health is served on `MANAGEMENT_PORT` (9090) at the
  origin root (`/health/liveness`, `/health/readiness`), not on `http`. The chart names that
  port `management` and points every probe at it.
- **Keep the existing realm file.** The realm stays at `docker/keycloak/consent-manager-realm.json`
  instead of moving to `keycloak/realm-export.json`. It already defines `consent-user`,
  `consent-participant` and `consent-catalog`, test users, and three confidential clients with
  direct-access grants.
- **Rotation follows the existing key model** (ADR-0013/0014): one active private signing key
  plus a public JWKS of retired and upcoming keys. The ticket's "key list" and "active-key-id"
  wording maps onto that model in the docs.
- **More secrets than the ticket lists.** `LOG_PSEUDONYM_KEY` and `ERASURE_VERIFICATION_SECRET`
  are secrets too, alongside the DB and SMTP credentials.
- **No GraalVM native-image profile.** The ticket marks it optional and outside MVP, so this
  plan does not add it.
- **AC 6 ("`helm install` succeeds with default values") means: renders and installs once the
  documented required inputs are supplied.** The defaults cannot produce Ready pods. The
  service refuses to start without a non-blank IdP, and it needs a database, a signing-key
  Secret and two stable secrets that a chart must not invent (Step 4). With an input missing,
  the chart fails at render time through `required` and names that input. The required set is
  checked in as `charts/consent-manager/ci/minimal-values.yaml` (`identityProviders.primary`,
  `config.database.url`, `secrets.*` or `secrets.existingSecret`), and lint and template run
  against it. "Pods Ready" additionally needs a reachable database and IdP and the out-of-band
  signing-key Secret. Step 7 shows that.

## Steps

### Step 1: Load receipt signing keys from mounted files
The ticket forbids passing keys as env vars. Today `SigningKeyStore` reads the private JWK inline
from `RECEIPTS_SIGNING_KEY` and the retired JWKS from `RECEIPTS_RETIRED_VERIFICATION_KEYS`.

- `config/ConsentManagerConfiguration.java` (`Receipts`): add `signingKeyFile` and
  `retiredVerificationKeysFile` (nullable path strings). Bind them in `application.yml` as
  `signing-key-file: ${RECEIPTS_SIGNING_KEY_FILE:}` and
  `retired-verification-keys-file: ${RECEIPTS_RETIRED_VERIFICATION_KEYS_FILE:}`.
- `signing/SigningKeyStore.java`: resolve each key source from either the inline value or the
  file, never both.
  - **Both set:** startup fails with a message naming the two settings.
  - **Path set but missing or unreadable:** startup fails naming the path. The message must not
    quote any content, and the cause must be dropped, as `load` already does on a parse error.
  - **File read:** UTF-8, trimmed, then passed through the existing parse and validation path
    (private-only check, duplicate-`kid` check, ephemeral fallback when nothing is configured).
  - Keep the read in a small private helper. Define named constants for the new messages.
- `signing/SigningKeyStoreTest.java`: add a parameterized test over (inline, file, both, missing
  file, file holding a public-only key, file holding malformed JSON). Write the files with JUnit
  `@TempDir`. Assert that no failure message contains key material.
- Docs: in `.env.sample` and `application.yml`, document both `*_FILE` variables as the
  production form and the inline variables as the local or test form. Mention the
  mutual-exclusion rule.
- Acceptance: `./mvnw verify` is green and `./mvnw spotless:apply` has been run. Existing inline
  behaviour is unchanged.

### Step 2: Harden the container image and `.dockerignore`
Files: `docker/Dockerfile`, `.dockerignore`.

**Build stage:** keep `eclipse-temurin:25-jdk` and the existing cache split: `.mvn/`, `mvnw` and
`pom.xml`, then `dependency:go-offline`, then `api/` and `src/`, then `package -DskipTests`.

**Runtime stage:**
- Switch to `eclipse-temurin:25-jre-alpine`. Busybox `wget` keeps the `HEALTHCHECK` working
  without an `apt-get install` layer.
- Verify the tag exists with `docker pull`. If it does not, use `eclipse-temurin:25-jre` and
  drop the apt `wget` install in favour of a `HEALTHCHECK` that needs no extra package. Record
  the choice in the commit message.
- Create the user and group with a fixed **numeric** UID/GID (`APP_UID=10001`, declared as an
  `ARG` rather than a magic literal) and write `USER 10001:10001`. Kubernetes `runAsNonRoot` can
  only verify a numeric user.
- The JAR is owned by root and is read-only for the app user. Nothing under `/app` is writable.

**JVM flags:** set `-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError
-Djava.io.tmpdir=/tmp` in the ENTRYPOINT. There is no `-Xmx`. Keep a `JAVA_OPTS`-style hook only
if it needs no shell (exec-form ENTRYPOINT, so `JAVA_TOOL_OPTIONS` is the override path).
Document it in the Dockerfile header.

**`.dockerignore`:**
- It must exclude build output (`target/`), keys (`*.pem`, `*.key`, `*.jwk`, `*.jwks`, `keys/`),
  `.env` and `.env.*` (but keep `!.env.sample` as today), test sources and resources
  (`src/test/`), `charts/`, and `docker/`, `compose.yaml` and the docs it already excludes.
- Confirm that `./mvnw package -DskipTests` still succeeds without `src/test`.

Verification (record the output in the PR):
- `docker build -f docker/Dockerfile -t consent-manager:local .`
- `docker run --rm --read-only --tmpfs /tmp -e MICRONAUT_ENVIRONMENTS=dev ... consent-manager:local`
  reaches the point of DB connection, and nothing fails on a read-only path.
- `docker run --rm --entrypoint id consent-manager:local` shows uid 10001.
- `docker history --no-trunc` shows no key material.

Covers AC 1, 2 and 15.

### Step 3: Harden the Compose environment and add an end-to-end smoke script
Files: `compose.yaml`, a new `docker/smoke-test.sh`, `docker/README.md` (new, short), and
`.env.sample` where variable names change.

**Rename services** to the ticket's names: `app` becomes `consent-manager` and `db` becomes
`postgres`. Update `DB_URL`, `depends_on`, comments, and every reference in `docs/` and
`docker/wiremock/README.md` (`grep -rn "\bapp\b\|\bdb\b"` across those files).

**`postgres`:** use `image: postgres:16-alpine`. It needs no init script.

**`consent-manager`:**
- Add `read_only: true`, `tmpfs: [/tmp]`, `cap_drop: [ALL]` and
  `security_opt: [no-new-privileges:true]`.
- Keep the readiness healthcheck and `restart: unless-stopped`, which never restarts on
  unhealthy.
- Signing keys stay ephemeral (from the dev profile). Add an optional, commented-out bind mount
  of `./docker/keys` to `/etc/consent-manager/keys:ro` with the `RECEIPTS_SIGNING_KEY_FILE` env
  line. That shows the file form without committing a key. Add `docker/keys/` to `.gitignore`.

**`docker/smoke-test.sh`** (bash, `set -euo pipefail`, curl + jq, named variables for every URL
and client):
1. Wait for `/health/readiness` to be `UP`.
2. Obtain real tokens from Keycloak with the password grant: `test-participant` and
   `test-participant-2` through `consent-manager-participant-client`, and `test-user` through
   `consent-manager-user-client`. Client secrets come from the realm file.
3. Register both participants (`POST /v1/participants`).
4. Generate a privacy notice from a WireMock contract (`POST /v1/privacy-notices/by-contract`).
5. Grant consent as `test-user` (`POST /v1/consents`).
6. Read it back (`GET /v1/consents/me`) and assert `status == GRANTED`.

Derive the request bodies from `api/openapi.yaml` and the contracts in
`docker/wiremock/__files`. The script exits non-zero on any failure.

**Outage check:** with the stack up, `docker compose stop postgres` must flip `/health/readiness`
to `DOWN` (HTTP 503) while `/health/liveness` stays `UP` and the container is not restarted.
`docker compose start postgres` recovers it. First check whether Micronaut's JDBC indicator
already contributes to readiness. `ManagementEndpointSecurityIT` and `ApplicationSmokeIT` are
the places to look. If it does not, fix it, for example with a `@Readiness` datasource indicator.
In either case, add or extend an IT asserting readiness is `DOWN` and liveness `UP` when the
datasource is unreachable. Document the manual compose check in `docker/README.md`.

**Realm:** confirm the realm still provides the three roles, test users and clients. Change it
only if the smoke script proves something is missing.

Run `docker compose up -d --build && docker/smoke-test.sh` and paste the output into the PR.

Covers AC 3, 4, 5 and 9.

### Step 4: Helm chart core (deployment, service, configmap, secret)
Create `charts/consent-manager/` with `Chart.yaml` (`apiVersion: v2`, `appVersion` from
`pom.xml`), `values.yaml`, `.helmignore`, and these templates: `_helpers.tpl` (name, fullname,
labels, selectorLabels, secret names), `deployment.yaml`, `service.yaml`, `configmap.yaml`,
`secret.yaml`.

**`values.yaml`:**
- `replicaCount: 2`, `image.{repository,tag,pullPolicy}`, `resources` (requests and limits;
  the heap follows the limit).
- `service.{type: ClusterIP, port: 8080, managementPort: 9090}`.
- `config` for non-sensitive env: `consentManagerUrl`, `contractServiceUrl`, `apiPrefix`,
  `database.url`, smtp host/port/from, cache and page settings, `metricsAuthenticationEnabled`.
- `identityProviders.primary.{issuer,discoveryUrl,audience,allowInsecureTransport,clockSkew,
  claims}`, which is required, and `identityProviders.additional` (a map keyed by provider
  name, empty by default). See the ConfigMap section for why `primary` is special.
- `secrets.{dbUser,dbPassword,smtpUser,smtpPassword,logPseudonymKey,erasureVerificationSecret}`
  and `secrets.existingSecret`.
- `signingKeys.{secretName (default "<release>-signing-keys"), signingKeyFile: signing-key.jwk,
  retiredVerificationKeys.enabled: false, retiredVerificationKeysFile:
  retired-verification-keys.jwks}`.
- `podSecurityContext` and `securityContext`, `tmp.sizeLimit`, `nodeSelector`, `tolerations`,
  `affinity`.

**`deployment.yaml`:**
- Two named container ports, `http` (8080) and `management` (9090).
- Probes exactly as the ticket specifies, but on `port: management`:
  - liveness `/health/liveness`: 30/30/3
  - readiness `/health/readiness`: 10/10/3
  - startup `/health/liveness`: period 5, failureThreshold 30
- RollingUpdate with `maxUnavailable: 0`, `maxSurge: 1`.
- Pod security context: `runAsNonRoot: true`, `runAsUser`/`runAsGroup`/`fsGroup: 10001`,
  `seccompProfile: RuntimeDefault`.
- Container security context: `readOnlyRootFilesystem: true`,
  `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`.
- An `emptyDir` mounted at `/tmp`.
- The `signing-keys` secret volume with `defaultMode: 0400`, mounted read-only at
  `/etc/consent-manager/keys`.
  - **Decision on AC 10:** the template states `0400` literally. Because `fsGroup: 10001` is
    set, the kubelet chowns the projected files to `root:10001` and ORs in group read, so the
    pod sees **0440 `root:10001`**. That is intended: the files are readable only by the app's
    group, and other users get no access. Without `fsGroup`, UID 10001 could not read a
    root-owned 0400 file at all. Note this in a comment beside `defaultMode` and in the chart
    README. Step 7 asserts the effective mode.
- Env:
  - `RECEIPTS_SIGNING_KEY_FILE` always.
  - `RECEIPTS_RETIRED_VERIFICATION_KEYS_FILE` only when enabled.
  - `envFrom` the ConfigMap.
  - The secret values via `secretKeyRef`.
- `checksum/config` and `checksum/secret` pod annotations, so a config or IDP-registry change
  rolls the pods. Changing the trust list is a deliberate redeploy.

**`configmap.yaml`:** plain env keys (`PORT`, `MANAGEMENT_PORT`, `API_PREFIX`,
`CONSENT_MANAGER_URL`, `CONTRACT_SERVICE_URL`, `DB_URL`, `SMTP_HOST`, ...).

**Identity providers, decided:** Micronaut merges maps across property sources, so no layered
file can remove the `consent-manager.identity-providers.primary` entry from `application.yml`.
Its issuer, discovery-url and audience default to empty and carry `@NotBlank`. So:
- `primary` is always configured through the existing `IDP_*` env vars, rendered into the
  ConfigMap from `identityProviders.primary`. Wrap `issuer`, `discoveryUrl` and `audience` in
  `required`, so a missing value fails rendering instead of failing startup. This is the same
  path Compose already uses.
- Each entry under `identityProviders.additional` is rendered with `toYaml` into an
  `identity-providers.yml` data key under `consent-manager.identity-providers.<name>`. It is
  mounted read-only and loaded through `MICRONAUT_CONFIG_FILES`. Both the key and the mount are
  emitted only when `additional` is non-empty. A key named `primary` is refused with `fail`,
  because it would silently merge into the env-configured entry.
- State this in a `values.yaml` comment.
- Check: `helm template` with `ci/minimal-values.yaml` (primary only) and with
  `ci/all-enabled-values.yaml` (primary plus one additional). Add
  `IdentityProviderConfigFileTest`, which starts an `ApplicationContext` with `IDP_*`-style
  properties for `primary` plus a `micronaut.config.files` YAML naming a second provider.
  Assert that both `IdentityProviderConfiguration` beans bind with the expected issuers and
  that validation passes. Discovery is not needed for this, so stub or skip the registry.

**`secret.yaml`:** rendered only when `secrets.existingSecret` is empty. It holds `DB_USER`,
`DB_PASSWORD`, `SMTP_USER`, `SMTP_PASSWORD`, `LOG_PSEUDONYM_KEY` and
`ERASURE_VERIFICATION_SECRET`. Rotating either of the last two silently breaks pseudonym
linkage or erasure verification, so the chart never generates them. There is no `lookup` and no
`randAlphaNum`: `lookup` returns nothing under `helm template`, `--dry-run` and GitOps
renderers (Argo CD), so a generated fallback would rotate both on every sync and change
`checksum/secret`, rolling the pods each time. Instead, when `existingSecret` is unset,
`logPseudonymKey` and `erasureVerificationSecret` go through `required`. Their error message
gives the generation command (`openssl rand -base64 32`) and recommends `existingSecret` for
production. No credential may appear in the ConfigMap. The chart never creates the signing-key
Secret: keys are created out of band (`kubectl create secret generic`) and never live in values.

Acceptance:
- `helm lint --strict -f charts/consent-manager/ci/minimal-values.yaml charts/consent-manager`
  is clean.
- `helm template t charts/consent-manager -f charts/consent-manager/ci/minimal-values.yaml`
  renders.
- `helm template` with default values fails, and the error names the first missing required
  input.
- Rendering twice with the same values gives byte-identical output, so the checksums are
  stable.
- grep the rendered ConfigMap and confirm it holds no password or key.
- `./mvnw verify` is green, including `IdentityProviderConfigFileTest`.

Covers AC 6, 7, 8, 10, 11 and 12.

### Step 5: Optional Helm templates (ingress, HPA, ServiceMonitor)
Add `templates/ingress.yaml`, `templates/hpa.yaml`, `templates/servicemonitor.yaml` and
`templates/NOTES.txt`. Each optional template is gated by `enabled: false` in `values.yaml`.

- **Ingress:** `ingress.{className,annotations,hosts,tls}`. It routes to the `http` port only;
  the management port is never exposed.
- **HPA:** `autoscaling/v2`, with `min`/`max` replicas and CPU and memory targets. When enabled,
  `deployment.yaml` omits `replicas`.
- **ServiceMonitor:** `monitoring.coreos.com/v1`, scraping `/prometheus` on the `management`
  port. Add an optional `bearerTokenSecret` because `METRICS_AUTHENTICATION_ENABLED` defaults
  to true, and an `interval`. Guard it with
  `.Capabilities.APIVersions.Has "monitoring.coreos.com/v1"` or document that the CRD is
  required.
- **NOTES.txt:** how to reach the service, and a reminder to create the signing-key Secret.

Add `charts/consent-manager/ci/all-enabled-values.yaml`, which turns everything on.

It includes the `ci/minimal-values.yaml` inputs and one `identityProviders.additional` entry.

Acceptance: `helm lint --strict` and `helm template` are clean for both
`ci/minimal-values.yaml` and `ci/all-enabled-values.yaml`.

### Step 6: Chart README and deployment documentation
Create `charts/consent-manager/README.md` and add a short `docs/deployment.md` that links the
chart README, `docker/README.md` and `.env.sample`. Content:

- **Install:** prerequisites (PostgreSQL 16, a trusted IDP), creating the signing-key Secret
  (`kubectl create secret generic <release>-signing-keys --from-file=signing-key.jwk=...`), the
  required inputs from `ci/minimal-values.yaml` (the AC 6 interpretation), `existingSecret` as
  the production form for the two stable secrets, `primary` (env) versus `additional` (file)
  identity providers, and a values table.
- **Signing-key file mode:** `defaultMode: 0400` appears in the pod as 0440 `root:<fsGroup>`.
  Explain why.
- **Configuration and secret matrix:** what is in the ConfigMap, what is in the Secret, and the
  signing keys as mounted files. Note that the IDP registry is security-relevant and immutable at
  runtime, so changing it is a deliberate redeploy.
- **Key rotation procedure,** mapped onto the active key plus retired JWKS model:
  1. Add the new key's public JWK to the retired/upcoming JWKS in the Secret, enable
     `retiredVerificationKeys`, and roll. The new key is now published.
  2. Wait at least one verifier JWKS cache period, then swap: the new private JWK becomes
     `signing-key.jwk`, the old public JWK moves into the retired JWKS (a `kid` in both fails
     startup), and roll.
  3. Remove the old public key only once no receipt signed with it is still in circulation.

  Reuse the generation commands from `.env.sample`.
- **Probes:** why liveness never points at readiness, and why the startup probe exists.
- **Database migrations:** Flyway runs at startup by default and Flyway's PostgreSQL lock
  serialises concurrent replicas. Describe the alternatives, an init container or a
  `pre-install,pre-upgrade` hook Job running migrations once, as documented options rather than
  templates. State the expand-then-contract rule for rolling updates.
- **Read-only root filesystem and `/tmp`.**

Also update the `AGENTS.md` project-structure entry for `charts/` and remove the "being added"
note.

Covers AC 16 and the documentation half of AC 13.

### Step 7: Verify multi-replica migration and an end-to-end chart install
- Add `src/test/java/com/seamware/consentmanager/FlywayConcurrentMigrationIT.java`.
  - Start two or more Flyway migrations concurrently against one fresh Testcontainers PostgreSQL
    database, using the same `classpath:db/migration` locations the app uses. Run them on a
    fixed thread pool released by a `CountDownLatch`.
  - Assert that every version appears exactly once in `flyway_schema_history` with
    `success = true`, and that no run failed.
  - Parameterize over the replica count, for example 2 and 4, as named constants.
- Chart install check, recorded in the PR rather than wired into Maven:
  1. Create a `kind` cluster and load `consent-manager:local`.
  2. Install PostgreSQL (for example the `postgres:16-alpine` image as a throwaway Deployment).
  3. Install an identity provider, because `IdentityProviderHealthIndicator` is `@Readiness`
     and stays DOWN until every configured provider resolves. Use a throwaway Keycloak
     Deployment and Service (`quay.io/keycloak/keycloak:26.0`, the same image Compose uses).
     Import `docker/keycloak/consent-manager-realm.json` from a ConfigMap. Pin
     `KC_HOSTNAME=http://keycloak.<ns>.svc:8080` so the discovery document's `issuer` matches
     what the app expects, byte for byte. Pass
     `identityProviders.primary.issuer=http://keycloak.<ns>.svc:8080/realms/consent-manager`,
     the matching `discoveryUrl`, the realm's audience and `allowInsecureTransport=true`. Plain
     http is for this throwaway check only, and the transcript should say so.
  4. Create the signing-key Secret. Supply `secrets.logPseudonymKey` and
     `secrets.erasureVerificationSecret` with `--set`, generated once by `openssl rand`.
  5. Run `helm install --wait` with `replicaCount=2`.
  6. Confirm:
     - both pods become Ready, and `/health/readiness` reports `UP` with the identity-provider
       and datasource components each `UP`;
     - migrations were applied once;
     - `stat -c '%a %U:%G'` on the key file prints `440 root:10001` (the AC 10 decision in
       Step 4), and the app read the key (startup log; `GET /.well-known/jwks.json` shows the
       configured `kid`);
     - `kubectl exec ... -- touch /x` fails because the filesystem is read-only.
  7. Only after readiness has been shown `UP`, scale PostgreSQL to 0. Confirm the pods go
     NotReady with readiness `DOWN` on the datasource component and no restarts. Scale it back
     and confirm they return to Ready.
  8. Optionally, port-forward and run `docker/smoke-test.sh` against the cluster. Tokens are
     issued with the pinned issuer.
- Fix any chart or app issue this exposes, within this step.
- Acceptance: `./mvnw verify` is green, `helm lint --strict` is clean, and the kind transcript
  is in the PR. Tick the remaining ACs: 1, 6, 9, 10 and 13.
