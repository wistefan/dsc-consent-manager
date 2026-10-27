# Local stack (`compose.yaml`)

| Service | Image | Host port | Purpose |
| --- | --- | --- | --- |
| `consent-manager` | built from `docker/Dockerfile` | `8080` (API), `9090` (management) | the service, `dev` profile |
| `postgres` | `postgres:16-alpine` | `5432` | database, volume `postgres16-alpine-data` |
| `keycloak` | `quay.io/keycloak/keycloak:26.0` | `8180` | identity provider, realm from `keycloak/consent-manager-realm.json` |
| `wiremock` | `wiremock/wiremock:3.10.0` | `8081` | Contract Service stub, see `wiremock/README.md` |

```bash
docker compose up -d --build && docker/smoke-test.sh
```

`consent-manager` runs the way the Helm chart does: a numeric non-root user, a read-only root
filesystem with a `/tmp` tmpfs, every capability dropped and `no-new-privileges`. Receipts are signed
with an ephemeral key. To try the production file form, put a private JWK at
`docker/keys/signing-key.jwk` (git-ignored) and uncomment the `RECEIPTS_SIGNING_KEY_FILE` line and
the `volumes` entry in `compose.yaml`.

## Upgrading from the `postgres:16` volume

The database moved from the Debian image (glibc) to Alpine (musl), which collate text differently.
A cluster initialised under one and opened under the other can return silently wrong results from
text indexes and unique lookups, so the volume was renamed and every stack starts on a fresh one.
The old volume is no longer used; remove it with

```bash
docker volume rm <project>_postgres-data   # e.g. dsc-consent-manager_postgres-data
```

or run `docker compose down -v` once before pulling this change.

## Smoke test

`docker/smoke-test.sh` (needs `curl` and `jq`) waits for `/health/readiness`, takes password-grant
tokens from Keycloak for `test-participant`, `test-participant-2` and `test-user`, registers both
participants, generates a privacy notice from the WireMock ecosystem contract, grants consent as
`test-user` and reads it back from `GET /v1/consents/me`, expecting `GRANTED`. It exits non-zero on
the first failure and can be re-run against the same stack. `API_BASE_URL`, `MANAGEMENT_BASE_URL`,
`KEYCLOAK_BASE_URL` and `CONTRACT_URI` override its defaults.

## Database outage check

A database outage must drain the instance, never restart it:

```bash
docker compose stop postgres
curl -s -w ' %{http_code}\n' localhost:9090/health/readiness   # {"status":"DOWN",...} 503
curl -s -w ' %{http_code}\n' localhost:9090/health/liveness    # {"status":"UP",...} 200
docker inspect -f '{{.RestartCount}}' "$(docker compose ps -q consent-manager)"   # unchanged
docker compose start postgres                                  # readiness returns to UP 200
```

Assert only the top-level `status` and the HTTP code: outside the `dev` profile an anonymous caller
sees no per-component detail. `DatasourceOutageReadinessIT` holds the same behaviour in the build.
