# consent-manager Helm chart

Deploys the Consent Manager, a stateless OAuth2 resource server, as one Deployment, an API Service, a
cluster-internal management Service and its configuration. Ingress, HorizontalPodAutoscaler and
ServiceMonitor are optional. The chart does not install PostgreSQL or an identity provider, and it
never creates the receipt signing keys.

```bash
helm lint --strict -f charts/consent-manager/ci/minimal-values.yaml charts/consent-manager
helm template t charts/consent-manager -f charts/consent-manager/ci/minimal-values.yaml
```

## Install

### Prerequisites

- **PostgreSQL 16**, reachable from the pods, with an empty database and a user that may create
  tables in it. Flyway creates the schema on first start (see [Database migrations](#database-migrations)).
- **A trusted OIDC identity provider** whose issuer and discovery document the pods can reach. The
  service validates tokens and issues none, so it holds no client credentials. The provider's tokens
  must carry the audience you configure and the role strings in `roleMapping`.
- **The signing-key Secret**, created out of band (next section).
- For `serviceMonitor.enabled`, the Prometheus Operator CRD `monitoring.coreos.com/v1`. Without it
  the install fails with `no matches for kind ServiceMonitor`.

### 1. Create the signing-key Secret

Every consent receipt is signed with a private JWK, and its public half is served at
`/.well-known/jwks.json`. The key lives in a Secret the chart only mounts. It never sits in values,
in the ConfigMap, or in an env var. Generate the key with the commands under
`RECEIPTS_SIGNING_KEY` in [`.env.sample`](../../.env.sample) (ES256 preferred), then:

```bash
kubectl create secret generic <release>-signing-keys \
  --from-file=signing-key.jwk=./priv.json
# with signingKeys.retiredVerificationKeys.enabled=true, also:
#   --from-file=retired-verification-keys.jwks=./retired.jwks
```

The name defaults to `<release>-signing-keys` (`signingKeys.secretName` overrides it). The data keys
are `signingKeys.signingKeyFile` and `signingKeys.retiredVerificationKeysFile`. A missing Secret
leaves the pod in `ContainerCreating` (`FailedMount`) before the service runs. A Secret without the
expected key, or with an empty or malformed one, fails startup. The service never falls back to an
ephemeral key.

### 2. Supply the required inputs

The defaults do not render on their own. Rendering fails and names the first missing input until
you supply this set, which is checked in as [`ci/minimal-values.yaml`](ci/minimal-values.yaml):

| Value | Why the chart cannot default it |
| --- | --- |
| `identityProviders.primary.issuer`, `.discoveryUrl`, `.audience` | The service refuses to start without a trusted provider. |
| `config.database.url` | The JDBC URL of your database. |
| `secrets.existingSecret`, **or** `secrets.dbUser`, `secrets.dbPassword`, `secrets.logPseudonymKey`, `secrets.erasureVerificationSecret` | Credentials, plus two keys that must stay stable for the life of the data. |

```bash
helm install <release> charts/consent-manager -f my-values.yaml
```

"Installs" means the manifests apply. The pods become Ready only when the database and the
identity provider are reachable and the signing-key Secret exists. Readiness covers the datasource
and every configured provider.

### 3. Credentials: `existingSecret` in production

`logPseudonymKey` and `erasureVerificationSecret` are never generated. Rotating either silently
breaks something: pseudonyms already logged stop linking, and verifiers already written stop
confirming an erasure. A chart-generated value would rotate on every render that cannot `lookup`
(`helm template`, `--dry-run`, Argo CD) and roll the pods each time. Generate each once with
`openssl rand -base64 32` and keep it.

The `secrets.*` values render a chart-owned Secret, which suits a throwaway install. In production
set `secrets.existingSecret` to a Secret you manage (sealed, external-secrets, Vault). It must hold:

| Key | |
| --- | --- |
| `DB_USER` | mandatory |
| `DB_PASSWORD` | mandatory |
| `LOG_PSEUDONYM_KEY` | mandatory |
| `ERASURE_VERIFICATION_SECRET` | mandatory, by chart policy (below) |
| `SMTP_USER` | optional; the reference is `optional: true` |
| `SMTP_PASSWORD` | optional; the reference is `optional: true` |

A missing mandatory key leaves the pod in `CreateContainerConfigError`.

**Why `ERASURE_VERIFICATION_SECRET` is mandatory.** The application accepts it unset and then writes
no verifier when a user is erased (ADR-0007). A deployment without one cannot later confirm that an
erased person consented, and adding the secret afterwards does not cover records already erased.
The chart therefore requires it from the first install.

This removes a lever ADR-0007 gives the operator. A verifier lets whoever holds the secret test
candidate identifiers against erased records, and ADR-0007 presents leaving the secret unset as the
privacy-minimising default that bounds that capability. The chart trades that default for evidence.
A deployment that deliberately wants no verifier sets `existingSecret` and stores
`ERASURE_VERIFICATION_SECRET` as an **empty** value. The key must be present, but the application
treats a blank secret as unset and logs that no verifier will be written. Choose this before the
first erasure; adding a secret later does not reach records already erased.

### 4. Identity providers: `primary` and `additional`

- **`primary`** is always present, because `application.yml` declares it and no property source can
  remove it. It is rendered into the `IDP_*` env vars of the ConfigMap, the same path Compose uses.
  Each of its three `roleMapping` roles must be non-empty: the chart refuses rather than fall back
  to the application default.
- **`additional`** maps further provider names (lower-case letters, digits, inner hyphens; never
  `primary`) to entries of the same shape. They are rendered into `identity-providers.yml` in a
  separate ConfigMap, mounted read-only at `/etc/consent-manager/config` and loaded through
  `MICRONAUT_CONFIG_FILES`. `claims` fall back per key to the defaults. `roleMapping`, when given,
  replaces the default whole, so an additional provider may leave a role unmapped.

An unsupported key in any provider entry fails rendering instead of being silently ignored. Plain
`http` (`allowInsecureTransport: true`) is for local development only.

## Values

| Value | Default | Notes |
| --- | --- | --- |
| `replicaCount` | `2` | Ignored when `autoscaling.enabled`. |
| `image.repository` / `.tag` / `.pullPolicy` | `consent-manager` / appVersion / `IfNotPresent` | |
| `imagePullSecrets` | `[]` | |
| `nameOverride`, `fullnameOverride` | `""` | |
| `podAnnotations`, `podLabels` | `{}` | |
| `resources` | requests `250m`/`512Mi`, limits `1`/`1Gi` | The heap is 75% of `limits.memory` (`-XX:MaxRAMPercentage`). |
| `containerPorts.http` / `.management` | `8080` / `9090` | Rendered into `PORT` and `MANAGEMENT_PORT`. |
| `service.type` / `.port` | `ClusterIP` / `8080` | The API Service; ingress targets it. |
| `managementService.port` | `9090` | Always `ClusterIP`, whatever `service.type` is. |
| `ingress.enabled` | `false` | Every backend is fixed to the API Service's `http` port. No value can route the management port. |
| `ingress.className`, `.annotations`, `.hosts[].paths[]`, `.tls` | `""`, `{}`, `consent-manager.local` `/` Prefix, `[]` | Path `/` also covers the origin-root routes (`/.well-known/jwks.json`, Swagger UI, `/openapi.yaml`). |
| `autoscaling.enabled` | `false` | `autoscaling/v2`. With it on, the Deployment omits `replicas`. |
| `autoscaling.minReplicas` / `.maxReplicas` | `2` / `5` | Rendering fails when min > max. |
| `autoscaling.targetCPUUtilizationPercentage` | `75` | Percent of `resources.requests`. Empty omits the target. |
| `autoscaling.targetMemoryUtilizationPercentage` | `""` | Off by default: the JVM keeps its heap after a peak, so a memory target can pin the replica count at that peak. At least one target must be set. |
| `serviceMonitor.enabled` | `false` | Needs the `monitoring.coreos.com/v1` CRD. Scrapes `/prometheus` on the management Service, without credentials. |
| `serviceMonitor.interval` / `.scrapeTimeout` / `.labels` | `30s` / `""` / `{}` | `scrapeTimeout` must not exceed `interval`. `labels` holds e.g. the label your Prometheus selects ServiceMonitors by. |
| `config.consentManagerUrl` | `""` | Public base URL of this service. |
| `config.contractServiceUrl` | `""` | |
| `config.apiPrefix` | `/v1` | |
| `config.database.url` | `""` | **Required.** JDBC URL. |
| `config.smtp.host` | `""` | Empty disables notifications. |
| `config.smtp.port` / `.startTls` / `.fromAddress` / `.fromName` | `587` / `true` / `""` / `""` | `fromAddress` is required once `host` is set. |
| `config.notificationClientUrl` | `""` | Link in the consent-requested email. |
| `config.receiptsJurisdiction` | `EU` | |
| `config.cache.*` | see `values.yaml` | IdP JWKS, Contract Service and self-description caches. |
| `config.pagination.*` | `20` default, `100` max | Participants and consents. |
| `config.extraEnv` | `{}` | Any other non-sensitive variable from `.env.sample`. Credentials, the signing-key variables and keys a dedicated value sets are refused. |
| `identityProviders.primary.issuer` / `.discoveryUrl` / `.audience` | `""` | **Required.** |
| `identityProviders.primary.allowInsecureTransport` | `false` | Local development only. |
| `identityProviders.primary.clockSkew` | `30s` | |
| `identityProviders.primary.claims.*` | `[sub]`, `[participant_id]`, `[realm_access, roles]` | Each claim is a list of path segments. |
| `identityProviders.primary.roleMapping.*` | `consent-user`, `consent-participant`, `consent-catalog` | Each must be non-empty. |
| `identityProviders.additional` | `{}` | See above. |
| `secrets.existingSecret` | `""` | Production form; see the key table above. |
| `secrets.dbUser`, `.dbPassword`, `.logPseudonymKey`, `.erasureVerificationSecret` | `""` | **Required** unless `existingSecret` is set. |
| `secrets.smtpUser`, `.smtpPassword` | `""` | Omitted from the Secret when empty. |
| `signingKeys.secretName` | `<release>-signing-keys` | Created out of band. |
| `signingKeys.signingKeyFile` | `signing-key.jwk` | |
| `signingKeys.retiredVerificationKeys.enabled` | `false` | Mounts and configures the retired/upcoming JWKS. |
| `signingKeys.retiredVerificationKeysFile` | `retired-verification-keys.jwks` | |
| `probes.liveness` | delay 30, period 30, failures 3, timeout 5 | `/health/liveness` on `management`. |
| `probes.readiness` | delay 10, period 10, failures 3, timeout 5 | `/health/readiness` on `management`. |
| `probes.startup` | period 5, failures 30, timeout 5 | `/health/liveness`; a 150 s startup budget. |
| `podSecurityContext` | non-root UID/GID/fsGroup `10001`, `RuntimeDefault` seccomp | |
| `securityContext` | read-only root FS, no privilege escalation, all capabilities dropped | |
| `tmp.sizeLimit` | `256Mi` | The writable `/tmp`. |
| `nodeSelector`, `tolerations`, `affinity` | empty | |

## Configuration and secret matrix

| Where | What |
| --- | --- |
| ConfigMap `<fullname>` (env) | `PORT`, `MANAGEMENT_PORT`, `DB_URL`, `METRICS_AUTHENTICATION_ENABLED` (fixed `"false"`), `API_PREFIX`, `CONSENT_MANAGER_URL`, `CONTRACT_SERVICE_URL`, `NOTIFICATION_CLIENT_URL`, `RECEIPTS_JURISDICTION`, the cache and page settings, `SMTP_HOST`/`SMTP_PORT`/`SMTP_STARTTLS`/`SMTP_FROM_ADDRESS`/`SMTP_FROM_NAME` (only with `smtp.host`), `config.extraEnv`, and the primary provider: `IDP_ISSUER`, `IDP_DISCOVERY_URL`, `IDP_AUDIENCE`, `IDP_ALLOW_INSECURE_TRANSPORT`, `IDP_CLOCK_SKEW`, `IDP_JWKS_CACHE_TTL`, `IDP_CLAIM_USER_IDENTIFIER`, `IDP_CLAIM_PARTICIPANT_IDENTIFIER`, `IDP_CLAIM_ROLES`, `IDP_ROLE_USER`, `IDP_ROLE_PARTICIPANT`, `IDP_ROLE_CATALOG` |
| ConfigMap `<fullname>-identity-providers` (file) | `identity-providers.yml` with the `additional` providers; only rendered when there are any |
| Secret (`<fullname>` or `existingSecret`) | `DB_USER`, `DB_PASSWORD`, `LOG_PSEUDONYM_KEY`, `ERASURE_VERIFICATION_SECRET`, optionally `SMTP_USER`, `SMTP_PASSWORD` |
| Secret `<release>-signing-keys` (files, out of band) | `signing-key.jwk`, optionally `retired-verification-keys.jwks`, mounted at `/etc/consent-manager/keys`; the pod gets `RECEIPTS_SIGNING_KEY_FILE` and `RECEIPTS_RETIRED_VERIFICATION_KEYS_FILE` |

A claim is a list of path segments, and the env form joins them with commas: `[realm_access, roles]`
becomes `IDP_CLAIM_ROLES=realm_access,roles`, the nested `roles` inside `realm_access`. A dot does
not separate segments, so a URI-shaped claim such as `https://example.com/roles` is one segment.

The identity-provider registry is security-relevant and is read once at startup, so it cannot change
at runtime. `checksum/config` hashes both ConfigMaps, and `checksum/secret` hashes the chart-owned
Secret, so a `helm upgrade` that changes the trust list, the env or a chart-rendered credential rolls
the pods. Changing the trust list is therefore always a deliberate redeploy. The checksums do not
cover the out-of-band Secrets: after editing `existingSecret` or the signing-key Secret, run
`kubectl rollout restart deployment/<fullname>`.

## Signing-key file mode

The volume states `defaultMode: 0400`, but inside the pod the files show as **0440
`root:10001`** (`stat -L -c '%a %U:%G'`; `-L` because a projected file is a symlink into `..data/`).
That is intended. Because `fsGroup` is set, the kubelet chowns projected files to `root:<fsGroup>`
and adds group read. The app's group can read the key, and other users cannot. Without `fsGroup`,
UID 10001 could not read a root-owned 0400 file at all, and startup would fail.

## Metrics

Metrics authentication is off (`METRICS_AUTHENTICATION_ENABLED=false`, fixed), so the chart holds no
metrics credential and the ServiceMonitor carries none. `/prometheus` is reachable only inside the
cluster, through the management Service, which is always `ClusterIP`. No ingress backend can point
at it. Health detail is a separate setting and stays `AUTHENTICATED`: an anonymous probe sees only
`UP`/`DOWN`, never the trust list.

Clusters that need more can add a NetworkPolicy that admits the management port only from the
monitoring namespace, for example:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: <fullname>-management
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: consent-manager
      app.kubernetes.io/instance: <release>
  policyTypes: [Ingress]
  ingress:
    - ports: [{port: http, protocol: TCP}]
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: monitoring
      ports: [{port: management, protocol: TCP}]
```

With most CNI plugins the kubelet's probes are not subject to a NetworkPolicy, but check yours.
The chart does not template one, because the right selector depends on how your cluster runs Prometheus.

## Key rotation

The service holds one active private key (`signing-key.jwk`) and a public-only JWKS of every other
published key, retired and upcoming (`retired-verification-keys.jwks`; the name is narrower than the
role). Both are served at `/.well-known/jwks.json` with `Cache-Control: public, max-age=3600`.
Rotation therefore takes two rolls an hour apart. See ADR-0014 and `docs/security.md`.

The signing-key Secret is created out of band, so editing it changes neither `checksum/config` nor
`checksum/secret`, and a `helm upgrade` with unchanged values does **not** roll the pods. Every
"roll" below is

```bash
kubectl rollout restart deployment/<fullname>
kubectl rollout status deployment/<fullname>
```

The one exception is enabling `retiredVerificationKeys`: that is a values change, so the
`helm upgrade` that sets it rolls the pods itself. Generate the new key and its one-line public
JWKS entry with the commands in `.env.sample` (`jq -c '{keys:[.]}' pub.json`).

1. **Pre-publish.** Add the new key's public JWK to the retired JWKS in the Secret. If
   `retiredVerificationKeys` was off, add the `retired-verification-keys.jwks` key to the Secret,
   then set `signingKeys.retiredVerificationKeys.enabled: true` in the values file the release was
   installed with and run `helm upgrade <release> charts/consent-manager -f my-values.yaml` (this
   rolls the pods); otherwise roll. The new key is now published and signs nothing. Never run
   `helm upgrade` with only `--set`: it renders from chart defaults plus that one override, drops
   every required input, and aborts on the first `required` guard.
2. **Switch,** at least **one hour** later (the JWKS `max-age`), so every verifier has re-fetched.
   In **one** edit of the Secret: the new private JWK becomes `signing-key.jwk`, the new key's
   public entry is **removed** from the retired JWKS, and the old key's public entry is added to it.
   A `kid` present in both files fails startup with a duplicate-key-id error, and with
   `maxUnavailable: 0` the rollout stalls on the first new pod while the old pods keep serving.
   Roll.
3. **Retire.** Remove the old public key only once no receipt signed with it is still in
   circulation. Receipts are long-lived, so normally it stays. Roll.

Switching in a single step makes new receipts fail verification for up to an hour at any verifier
holding a cached JWKS.

## Probes

All three probes target the `management` port.

- **Liveness** (`/health/liveness`) has no external dependency. It fails only when the process itself
  is broken, which a restart can fix.
- **Readiness** (`/health/readiness`) covers the datasource and every identity provider. It takes a
  pod out of the Service while either is unreachable.
- **Liveness never points at readiness.** A database or identity-provider outage would then fail
  liveness on every pod at once, and the kubelet would restart all of them. That cures nothing,
  adds a restart storm and a cold JVM to the outage, and still leaves the pods unready afterwards.
- **The startup probe** exists because the management port answers only after the JVM has started
  and Flyway has migrated the database. While it runs, liveness and readiness are held off, so a slow
  first start or a long migration is not mistaken for a hung pod. Its budget is
  `periodSeconds × failureThreshold` = 150 s. Raise `probes.startup.failureThreshold` before a
  migration that may take longer, rather than loosening liveness.
- `timeoutSeconds` is 5 rather than the Kubernetes default of 1 s, so a GC pause under a tight CPU
  limit does not restart a healthy pod.

## Database migrations

Flyway runs at startup in every pod, by default. With several replicas starting together, Flyway's
PostgreSQL lock serialises them: one pod applies the pending migrations, and the others wait, find
nothing pending, and continue. No extra coordination is needed. `FlywayConcurrentMigrationIT`
pins this: two and four concurrent runs against one fresh database record each version exactly once.

Alternatives, documented as options rather than templated:

- **An init container** running the Flyway CLI image against the same database, with the
  migrations from `src/main/resources/db/migration` baked into an image or mounted from a
  ConfigMap. It still runs once per pod, so it adds isolation rather than fewer runs.
- **A `pre-install,pre-upgrade` hook Job** running the same Flyway CLI once per release, before any
  new pod starts. It keeps DDL rights out of the application's database user. Disable the in-app
  run with `config.extraEnv.FLYWAY_DATASOURCES_DEFAULT_ENABLED: "false"`, and give the Job its
  own credentials.

Whatever runs them, a rolling update keeps old pods serving against the new schema
(`maxUnavailable: 0`). Every migration therefore follows **expand-then-contract**:

1. **Expand.** Release N adds only what the previous release can ignore: new tables, nullable or
   defaulted columns, new indexes.
2. **Migrate.** Release N writes the new shape and backfills.
3. **Contract.** Release N+1, once no pod of release N-1 runs, drops or renames what is no longer
   read.

This also keeps `helm rollback` of the application safe. Flyway never undoes a migration, so the
previous release must still run against the newer schema.

## Read-only root filesystem and `/tmp`

The container runs as UID/GID 10001 with `readOnlyRootFilesystem: true`, no privilege escalation and
every capability dropped. The one writable path is `/tmp`, an `emptyDir` limited to `tmp.sizeLimit`
(256Mi). The image sets `-Djava.io.tmpdir=/tmp`, so every JVM temporary file lands there.
Exceeding the limit evicts the pod. Logs go to stdout as JSON, so nothing else needs to be writable.
The pod mounts no ServiceAccount token, because the service never calls the Kubernetes API.
