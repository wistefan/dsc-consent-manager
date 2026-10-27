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
  `config.database.url`, and either `secrets.existingSecret` or the four mandatory
  `secrets.{dbUser,dbPassword,logPseudonymKey,erasureVerificationSecret}`), and lint and template run
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
    (private-only check, duplicate-`kid` check, and the existing `MISSING_KEY` / opt-in
    ephemeral path when nothing is configured). The ephemeral key stays opt-in through
    `RECEIPTS_EPHEMERAL_SIGNING_KEY=true`: a missing or empty mount must never degrade to it.
  - Keep the read in a small private helper. Define named constants for the new messages.
  - **Messages name the source.** Today `MALFORMED_KEY`, `PUBLIC_KEY_ONLY`, `UNUSABLE_KEY`,
    `MALFORMED_RETIRED_KEYS`, `PRIVATE_RETIRED_KEY` and `DUPLICATE_KEY_ID` hard-code
    `RECEIPTS_SIGNING_KEY` / `RECEIPTS_RETIRED_VERIFICATION_KEYS`. Turn them into `%s` formats
    and pass the source that supplied the value: the variable name for inline, or
    `RECEIPTS_SIGNING_KEY_FILE (<path>)` for a file. A bad mounted file is then never blamed on
    the inline variable. `MISSING_KEY` and `EPHEMERAL_KEY` name both forms ("set
    RECEIPTS_SIGNING_KEY_FILE, or RECEIPTS_SIGNING_KEY for local use").
- `signing/SigningKeyStoreTest.java`: add a parameterized test over (inline, file, both, missing
  file, file holding a public-only key, file holding malformed JSON, retired-keys file holding a
  private key, and no inline key plus no file with `ephemeral-signing-key=false`, which must
  still fail with `MISSING_KEY`). Write the files with JUnit `@TempDir`. Each failing case asserts that the
  message names the expected source (variable name or `_FILE` variable plus path) and that it
  contains no key material.
- `AGENTS.md`: Step 1 is the first step to run, so it brings every deployment claim the plan PR
  added into line before Steps 2–7 read them:
  - Reword the Receipts bullet and the "receipt signing keys are mounted as files" clause of the
    Deployment bullet together, so both say the `*_FILE` form is production and the inline form
    is local/test only.
  - Write the final lint command now:
    `helm lint --strict -f charts/consent-manager/ci/minimal-values.yaml charts/consent-manager`.
    Note that a default-values failure is the `required` guard working, not a bug.
  - Mark the `charts/consent-manager/` structure entry as "added in Step 4" until Step 6
    replaces it.
  - Restore the sentence the Management-routes rewrite dropped: tests (`application-test.yml`)
    serve management on the main port (`endpoints.all.port: ~`) with
    `details-visible: ANONYMOUS`, so an IT sees health components without a token.
- Docs: in `.env.sample` and `application.yml`, document both `*_FILE` variables as the
  production form and the inline variables as the local or test form. Mention the
  mutual-exclusion rule.
- **`.env.sample` parity (AC 14):** add `config/EnvSampleParityTest`, a plain unit test.
  - It collects every upper-snake placeholder name (`${NAME` or `${NAME:default}`) from
    `src/main/resources/application.yml`. Lower-case dotted property references such as
    `${consent-manager.contract-service.url}` are not env vars and are skipped.
  - It asserts each name appears in `.env.sample`. Today that fails for `OTEL_TRACING_ENABLED`,
    so document it there in the same step.
  - Some variables are bound straight from the environment and never appear as placeholders:
    `SMTP_HOST`, `SMTP_PORT` and `SMTP_STARTTLS` (`application.yml` "read only from the
    environment"; defaults in `NotificationConfiguration`, names in
    `SmtpConfigurationValidator`). The test also asserts a named constant list of these
    environment-only variables, so they cannot drift out of `.env.sample` either. A later change
    that adds such a variable extends the list.
  - It asserts that none of the obsolete auth settings appear in `.env.sample`:
    `JWT_SECRET_KEY`, `OAUTH_SECRET_KEY`, `SESSION_SECRET`, `SALT_ROUNDS` and
    `X_CATALOG_CONSENT_KEY`, held as a named constant list. Parameterise over that list.
  - Later steps that add a variable (Step 3 renames, the `*_FILE` pair here) keep the test green
    by documenting it.
- Acceptance: `./mvnw verify` is green and `./mvnw spotless:apply` has been run. Existing inline
  behaviour is unchanged.

Covers AC 14 and the application half of AC 10.

### Step 2: Harden the container image and `.dockerignore`
Files: `docker/Dockerfile`, `.dockerignore`.

**Build stage:** keep `eclipse-temurin:25-jdk` and the existing cache split: `.mvn/`, `mvnw` and
`pom.xml`, then `dependency:go-offline`, then `api/` and `src/`, then `package -DskipTests`.

**Runtime stage:**
- Switch to `eclipse-temurin:25-jre-alpine`. Busybox `wget` keeps the `HEALTHCHECK` working
  without an `apt-get install` layer.
- Verify the tag exists with `docker pull`. If it does not, use `eclipse-temurin:25-jre` and
  drop the apt `wget` install in favour of a `HEALTHCHECK` that needs no extra package. The
  compose healthcheck (`compose.yaml`, `wget -qO- .../health/readiness`) also runs inside the
  image, so in that case change it in the same commit to the bash `/dev/tcp` probe the
  `keycloak` service already uses; otherwise `consent-manager` stays `unhealthy` forever. Record
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
- It must exclude build output (`target/`), keys at any depth (`**/*.pem`, `**/*.key`,
  `**/*.jwk`, `**/*.jwks`, `**/keys/`; `.dockerignore` patterns anchor at the context root, so a
  bare `*.jwk` would miss `src/main/resources/x.jwk`, which `COPY src/` would bake into the JAR),
  `.env` and `.env.*` (but keep `!.env.sample` as today), test sources and resources
  (`src/test/`), `charts/`, and `docker/`, `compose.yaml` and the docs it already excludes.
- Confirm that `./mvnw package -DskipTests` still succeeds without `src/test`.

Verification (record the output in the PR):
- `docker build -f docker/Dockerfile -t consent-manager:local .`
- `docker run --rm --read-only --tmpfs /tmp -e MICRONAUT_ENVIRONMENTS=dev ... consent-manager:local`
  reaches the point of DB connection, and nothing fails on a read-only path.
- The read-only check must be able to fail, so it does not rely on `touch /x` as UID 10001
  (`/` is root-owned and refuses that even when writable). Instead,
  `docker run --rm --read-only --tmpfs /tmp --entrypoint sh consent-manager:local -c
  "grep ' / ' /proc/mounts; touch /tmp/x"` shows `/` mounted `ro` and `/tmp` writable.
- `docker run --rm --entrypoint id consent-manager:local` shows uid 10001.
- `docker history --no-trunc` shows no key material.
- Key exclusion works below the root: create a dummy `src/main/resources/x.jwk` (untracked,
  deleted afterwards), build, and show that `unzip -l` of the image's JAR (copied out with
  `docker create` + `docker cp`) does not list `x.jwk`.

Covers AC 1, 2 and 15.

### Step 3: Harden the Compose environment and add an end-to-end smoke script
Files: `compose.yaml`, a new `docker/smoke-test.sh`, `docker/README.md` (new, short), and
`.env.sample` where variable names change.

**Rename services** to the ticket's names: `app` becomes `consent-manager` and `db` becomes
`postgres`. Update `DB_URL`, `depends_on`, comments, and every reference in `docs/` and
`docker/wiremock/README.md` (`grep -rn "\bapp\b\|\bdb\b"` across those files).

**`postgres`:** use `image: postgres:16-alpine`. It needs no init script.
- The switch moves from Debian/glibc to musl, so collation behaviour changes. An existing
  `postgres-data` volume was initialised under glibc, and opening it under musl risks silently
  wrong text-index and unique lookups. Rename the volume (`postgres-data` becomes
  `postgres16-alpine-data`) so every stack reinitialises, and say in `docker/README.md` and the
  commit message that the old volume can be removed with `docker volume rm` (or
  `docker compose down -v` once before upgrading).

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
In either case, add `DatasourceOutageReadinessIT`, asserting readiness reports the datasource
`DOWN` and liveness stays `UP` when the database becomes unreachable. Document the manual
compose check in `docker/README.md`.

**How the IT takes the database down.** It must not touch `PostgresTestResource`: that holds one
`static` container shared by every IT in the JVM, and stopping it would break whichever IT
Failsafe runs next. So the IT does not extend it. It implements `TestPropertyProvider` itself
over its **own** `PostgreSQLContainer` (class-scoped, started in `getProperties()`), so Flyway
migrates normally at context startup. The test asserts the `jdbc` component `UP`, stops its own
container, then asserts the outage. It also sets a short
`datasources.default.connection-timeout` (named constant, a few seconds) for its context, so a
readiness call during the outage answers before the HTTP client's read timeout instead of
waiting out Hikari's 30 s default.

**What the IT asserts.** In tests, `application-test.yml` serves management on the main port
(`endpoints.all.port: ~`) with `details-visible: ANONYMOUS` and security disabled, so a plain
anonymous `@MicronautTest` client sees the components; no Keycloak container is needed. The
test profile's dummy IdP (`localhost:1`) fails discovery, so top-level readiness can already be
`DOWN` before the outage. The IT therefore asserts the **`jdbc` component**: `UP` before the
stop, `DOWN` after, with readiness HTTP 503 and liveness HTTP 200 `UP` throughout.

**Deployed environments differ.** In compose and kind, `details-visible` is `AUTHENTICATED`, so
an anonymous call sees only the top-level status and HTTP code. The manual compose check
asserts top-level status and HTTP code only (503 `DOWN` / 200 `UP`).

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
- `service.{type: ClusterIP, port: 8080}` and `managementService.port: 9090`. See
  `service.yaml` below.
- `config` for non-sensitive env: `consentManagerUrl`, `contractServiceUrl`, `apiPrefix`,
  `database.url`, smtp host/port/from, cache and page settings. There is no
  `metricsAuthenticationEnabled` value; see "Metrics" below.
- `identityProviders.primary.{issuer,discoveryUrl,audience,allowInsecureTransport,clockSkew,
  claims,roleMapping}`, which is required, and `identityProviders.additional` (a map keyed by
  provider name, empty by default). See the ConfigMap section for why `primary` is special.
  `claims.{userIdentifier,participantIdentifier,roles}` and
  `roleMapping.{user,participant,catalog}` default to what `application.yml` defaults to
  (`[sub]`, `[participant_id]`, `[realm_access, roles]`; `consent-user`,
  `consent-participant`, `consent-catalog`).
- `secrets.{dbUser,dbPassword,smtpUser,smtpPassword,logPseudonymKey,erasureVerificationSecret}`
  and `secrets.existingSecret`. Which keys are mandatory is set out under `secret.yaml` below.
- `probes.{liveness,readiness,startup}.timeoutSeconds`, explicit (see `deployment.yaml`).
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
  - `timeoutSeconds` set explicitly from values (default 5 for each probe) rather than the
    Kubernetes default of 1 s. With 1 s, a GC pause under a tight CPU limit would fail liveness
    three times and restart a healthy pod. During a database outage a readiness call can still
    outlast the timeout, because Hikari's `connection-timeout` is 30 s. The pod goes NotReady
    either way; Step 7 accepts a timeout as an outcome of the outage check.
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
  - The secret values via `secretKeyRef`, against the chart's Secret or `existingSecret`. The
    refs for `DB_USER`, `DB_PASSWORD`, `LOG_PSEUDONYM_KEY` and `ERASURE_VERIFICATION_SECRET`
    are mandatory. The refs for `SMTP_USER` and `SMTP_PASSWORD` carry `optional: true`, so an
    `existingSecret` without SMTP keys does not fail the pod with `CreateContainerConfigError`.
- `checksum/config` and `checksum/secret` pod annotations, so a config or IDP-registry change
  rolls the pods. `checksum/config` hashes both ConfigMaps (`configmap.yaml` and
  `configmap-identity-providers.yaml`), so a change to an additional provider rolls them too. Changing the trust list is a deliberate redeploy.

**`service.yaml`:** two Services. The API Service (`service.type`, port `http`) is the one
ingress targets. A separate `<fullname>-management` Service is **always `ClusterIP`** and
exposes only the `management` port. The management port therefore stays inside the cluster
even when `service.type` is set to `LoadBalancer` or `NodePort`.

**`configmap.yaml`:** plain env keys (`PORT`, `MANAGEMENT_PORT`, `API_PREFIX`,
`CONSENT_MANAGER_URL`, `CONTRACT_SERVICE_URL`, `DB_URL`, `SMTP_HOST`, ...), plus:
- `METRICS_AUTHENTICATION_ENABLED: "false"`, fixed, as decided by the reviewer. The chart
  holds no static metrics credential. Metrics are reachable without a token, but only inside the
  cluster through the management Service (see `service.yaml`). That management Service is never
  routed by ingress and never exposed externally. Health detail stays `AUTHENTICATED`, because
  that is a separate setting.
- The `primary` IdP keys, listed in full: `IDP_ISSUER`, `IDP_DISCOVERY_URL`, `IDP_AUDIENCE`,
  `IDP_ALLOW_INSECURE_TRANSPORT`, `IDP_CLOCK_SKEW`, `IDP_CLAIM_USER_IDENTIFIER`,
  `IDP_CLAIM_PARTICIPANT_IDENTIFIER`, `IDP_CLAIM_ROLES`, `IDP_ROLE_USER`,
  `IDP_ROLE_PARTICIPANT` and `IDP_ROLE_CATALOG`. Every claim is a list of segments in values
  and renders comma-joined (`join ","`), which is the form the binder splits. For example,
  `roles: [realm_access, roles]` becomes `IDP_CLAIM_ROLES: "realm_access,roles"`. These keys
  together are the IdP registry that AC 12 puts in the ConfigMap: issuer URLs, claim names and
  role mappings.

**Identity providers, decided:** Micronaut merges maps across property sources, so no layered
file can remove the `consent-manager.identity-providers.primary` entry from `application.yml`.
Its issuer, discovery-url and audience default to empty and carry `@NotBlank`. So:
- `primary` is always configured through the existing `IDP_*` env vars, rendered into the
  ConfigMap from `identityProviders.primary`. Wrap `issuer`, `discoveryUrl` and `audience` in
  `required`, so a missing value fails rendering instead of failing startup. This is the same
  path Compose already uses.
- Each entry under `identityProviders.additional` is rendered into an `identity-providers.yml`
  data key under `consent-manager.identity-providers.<name>`, in a **separate** ConfigMap
  `<fullname>-identity-providers` (`templates/configmap-identity-providers.yaml`). It must not
  sit in the env ConfigMap: `envFrom` has no key filter, so the file would either be skipped
  with an `InvalidEnvironmentVariableNames` event on every pod start or injected whole as an env
  var. The template maps every
  setting explicitly to its kebab-case key (`discovery-url`, `allow-insecure-transport`,
  `clock-skew`, `claims.user-identifier`, `role-mapping.user`, ...). It does not `toYaml` the
  camelCase values map, because `IdentityProviderRegistryValidator.rejectUnrecognisedSettingKeys`
  compares against kebab-case `SUPPORTED_SETTING_KEYS`, and whether camelCase normalises there
  is unproven. The file is mounted read-only and loaded through `MICRONAUT_CONFIG_FILES`. The
  ConfigMap, the volume, the mount and the `MICRONAUT_CONFIG_FILES` env entry are emitted only
  when `additional` is non-empty. A key named `primary` is refused with `fail`,
  because it would silently merge into the env-configured entry.
- State this in a `values.yaml` comment.
- Check: `helm template` with `ci/minimal-values.yaml` (primary only) and with
  `ci/all-enabled-values.yaml` (primary plus one additional). Add
  `IdentityProviderConfigFileTest`, which starts an `ApplicationContext` with `IDP_*`-style
  properties for `primary` plus a `micronaut.config.files` YAML naming a second provider.
  That YAML is the file the chart actually renders, not a hand-written one: check in
  `src/test/resources/helm/identity-providers.yml`, produced by
  `helm template -f ci/all-enabled-values.yaml --show-only
  templates/configmap-identity-providers.yaml` (the `identity-providers.yml` key). A header comment gives the regeneration command, and the
  Step 5 acceptance diffs a fresh render against it. Assert that both
  `IdentityProviderConfiguration` beans bind with the expected issuers, role mapping and claims,
  and that `IdentityProviderRegistryValidator` passes. Discovery is not needed for this, so stub
  or skip the registry.

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

Key set, decided:
- **Mandatory:** `DB_USER`, `DB_PASSWORD`, `LOG_PSEUDONYM_KEY`, `ERASURE_VERIFICATION_SECRET`.
  When the chart renders the Secret, `dbUser` and `dbPassword` also go through `required`. An
  empty `DB_USER: ""` would reach the container as an empty env var and override
  `application.yml`'s `${DB_USER:consent_manager}` default, so the chart never renders one.
- **Optional:** `SMTP_USER`, `SMTP_PASSWORD`. The chart omits each key when its value is empty,
  and the deployment's refs are `optional: true`. SMTP stays off unless `config.smtp.host` is
  set.
- With `existingSecret`, the operator's Secret must hold the four mandatory keys; the chart
  README lists them.
- `ERASURE_VERIFICATION_SECRET` is mandatory by **chart policy**. The app treats it as optional
  ("unset means no verifier"), but a production deployment without a verifier cannot prove an
  erasure later, and adding one afterwards does not cover records already erased. The chart
  README states this policy.

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
- With `ci/all-enabled-values.yaml`, the env ConfigMap has no `identity-providers.yml` key, the
  `<fullname>-identity-providers` ConfigMap holds it, and changing an additional provider's
  issuer changes `checksum/config`. With `ci/minimal-values.yaml`, that ConfigMap and its mount
  are not rendered.
- `helm template --set 'identityProviders.primary.roleMapping.user=other-user'` renders
  `IDP_ROLE_USER: other-user`, and the default `IDP_CLAIM_ROLES` renders as
  `realm_access,roles`.
- With `--set service.type=LoadBalancer`, the management Service is still `ClusterIP`.
- With `--set secrets.smtpUser=` (empty), the rendered Secret has no `SMTP_USER` key and the
  deployment's SMTP refs are `optional: true`. Without `secrets.dbUser`, rendering fails naming
  it.
- `./mvnw verify` is green, including `IdentityProviderConfigFileTest`.
- `AGENTS.md`: confirm the lint command Step 1 wrote still matches the chart.

Covers AC 6, 7, 8, 10, 11 and 12.

### Step 5: Optional Helm templates (ingress, HPA, ServiceMonitor)
Add `templates/ingress.yaml`, `templates/hpa.yaml`, `templates/servicemonitor.yaml` and
`templates/NOTES.txt`. Each optional template is gated by `enabled: false` in `values.yaml`.

- **Ingress:** `ingress.{className,annotations,hosts,tls}`. It routes to the `http` port only;
  the management port is never exposed.
- **HPA:** `autoscaling/v2`, with `min`/`max` replicas and CPU and memory targets. When enabled,
  `deployment.yaml` omits `replicas`.
- **ServiceMonitor:** `monitoring.coreos.com/v1`. It selects the management Service and scrapes
  `/prometheus` on the `management` port at a configurable `interval`. It sets **no
  credentials**: no `bearerTokenSecret`, no `oauth2`, no `basicAuth`. Metrics authentication is
  off in the chart (Step 4 ConfigMap), and the scrape stays inside the cluster. Guard it with
  `.Capabilities.APIVersions.Has "monitoring.coreos.com/v1"` or document that the CRD is
  required.
- **NOTES.txt:** how to reach the service, and a reminder to create the signing-key Secret.

Add `charts/consent-manager/ci/all-enabled-values.yaml`, which turns everything on.

It includes the `ci/minimal-values.yaml` inputs and one `identityProviders.additional` entry.

Acceptance: `helm lint --strict` and `helm template` are clean for both
`ci/minimal-values.yaml` and `ci/all-enabled-values.yaml`. The rendered ServiceMonitor holds no
secret reference, and the rendered Ingress has no backend on the management port. The rendered
`identity-providers.yml` is byte-identical to the
`src/test/resources/helm/identity-providers.yml` fixture (Step 4).

### Step 6: Chart README and deployment documentation
Create `charts/consent-manager/README.md` and add a short `docs/deployment.md` that links the
chart README, `docker/README.md` and `.env.sample`. Content:

- **Install:** prerequisites (PostgreSQL 16, a trusted IDP), creating the signing-key Secret
  (`kubectl create secret generic <release>-signing-keys --from-file=signing-key.jwk=...`), the
  required inputs from `ci/minimal-values.yaml` (the AC 6 interpretation), `existingSecret` as
  the production form for the two stable secrets, the exact key set an `existingSecret` must
  hold (mandatory and optional, from Step 4), the policy that makes
  `ERASURE_VERIFICATION_SECRET` mandatory although the app accepts it unset, `primary` (env)
  versus `additional` (file) identity providers, and a values table.
- **Signing-key file mode:** `defaultMode: 0400` appears in the pod as 0440 `root:<fsGroup>`.
  Explain why.
- **Metrics:** authentication is off. `/prometheus` is reachable only inside the cluster
  through the ClusterIP management Service. Clusters that need more can add a NetworkPolicy on
  the management port (documented as an option, not templated).
- **Configuration and secret matrix:** what is in the ConfigMap (including the full `IDP_*`
  key list and the comma-joined claim form), what is in the Secret, and the signing keys as
  mounted files. Note that the IDP registry is security-relevant and immutable at
  runtime, so changing it is a deliberate redeploy.
- **Key rotation procedure,** mapped onto the active key plus retired JWKS model:
  The signing-key Secret is created out of band, so editing it changes neither
  `checksum/config` nor `checksum/secret`. A `helm upgrade` with unchanged values does **not**
  roll the pods. Every "roll" below is an explicit
  `kubectl rollout restart deployment/<fullname>` followed by
  `kubectl rollout status`. The one exception is enabling `retiredVerificationKeys`, which is a
  values change, so `helm upgrade` rolls the pods for it.
  1. Add the new key's public JWK to the retired/upcoming JWKS in the Secret, enable
     `retiredVerificationKeys`, and roll. The new key is now published.
  2. Wait **at least one hour**, the `Cache-Control` max-age of `/.well-known/jwks.json`
     (`application.yml`, `retired-verification-keys` comment), so every verifier has re-fetched.
     Then swap, in **one** Secret edit: the new private JWK becomes `signing-key.jwk`, the new
     key's public entry is **removed** from the retired JWKS, and the old key's public entry is
     added to it. A `kid` in both places fails startup with `DUPLICATE_KEY_ID`, and with
     `maxUnavailable: 0` the rollout would stall on the first new pod. Roll again.
  3. Remove the old public key only once no receipt signed with it is still in circulation.
     Roll once more.

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
  4. Create the signing-key Secret. Supply `secrets.dbUser` and `secrets.dbPassword` (matching
     the throwaway PostgreSQL), and `secrets.logPseudonymKey` and
     `secrets.erasureVerificationSecret` generated once by `openssl rand`, all with `--set`.
  5. Run `helm install --wait` with `replicaCount=2`.
  6. Confirm. Health detail is `AUTHENTICATED`, so every component-level check below sends
     `Authorization: Bearer <token>`. The token comes from the in-cluster Keycloak through the
     password grant (`test-catalog` through `consent-manager-catalog-client`, run from a
     `kubectl run curlimages/curl` pod so its `iss` matches the pinned hostname). Any valid
     token reveals detail, whatever its role. Anonymous calls are used only for top-level status.
     - both pods become Ready, and the authenticated `/health/readiness` reports `UP` with the
       identity-provider and `jdbc` components each `UP`;
     - `GET /prometheus` on the management Service answers `200` with no token, from inside the
       cluster;
     - migrations were applied once;
     - `stat -L -c '%a %U:%G'` on the key file prints `440 root:10001` (the AC 10 decision in
       Step 4). `-L` is required: a projected secret file is a symlink into `..data/`, and
       without it `stat` reports the link (`777`). The app read the key (startup log;
       `GET /.well-known/jwks.json` shows the configured `kid`);
     - the root filesystem is read-only: `kubectl exec ... -- grep ' / ' /proc/mounts` shows
       `ro`, the pod spec carries `readOnlyRootFilesystem: true`, and
       `kubectl exec ... -- touch /tmp/x` succeeds. A `touch /x` is not used, because UID 10001
       cannot write the root-owned `/` even on a writable filesystem, so it could not fail.
  7. Only after readiness has been shown `UP`, scale PostgreSQL to 0. Confirm the pods go
     NotReady and that `kubectl get pods` shows no new restarts. Anonymous readiness answers
     503 `DOWN` or, while Hikari waits out its 30 s connection timeout, times out; either is
     accepted, and the transcript records which. Liveness answers 200 `UP`. The token, fetched
     while readiness was still `UP`, shows the `jdbc` component `DOWN` and the
     identity-provider component still `UP` (use a client timeout above 30 s for that call).
     Scale PostgreSQL back up and confirm the pods return to Ready.
  8. Optionally, port-forward and run `docker/smoke-test.sh` against the cluster. Tokens are
     issued with the pinned issuer.
- Fix any chart or app issue this exposes, within this step.
- Acceptance: `./mvnw verify` is green, `helm lint --strict` is clean, and the kind transcript
  is in the PR. Tick the remaining ACs: 1, 6, 9, 10 and 13 (AC 14 is ticked by Step 1).
