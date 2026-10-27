#!/usr/bin/env bash
# =============================================================================
# End-to-end smoke test against the running compose stack (compose.yaml).
#
# Waits for readiness, takes real tokens from the local Keycloak, registers the
# two participants the WireMock contracts name, generates a privacy notice from
# a contract, grants consent as the test user and reads it back. Re-runnable:
# a participant that is already registered is accepted as such.
#
#   docker compose up -d --build && docker/smoke-test.sh
#
# Needs curl and jq. Every endpoint can be overridden through the environment.
# =============================================================================
set -euo pipefail

API_BASE_URL="${API_BASE_URL:-http://localhost:${PORT:-8080}${API_PREFIX:-/v1}}"
MANAGEMENT_BASE_URL="${MANAGEMENT_BASE_URL:-http://localhost:${MANAGEMENT_PORT:-9090}}"
KEYCLOAK_BASE_URL="${KEYCLOAK_BASE_URL:-http://localhost:${KEYCLOAK_PORT:-8180}}"
KEYCLOAK_REALM="${KEYCLOAK_REALM:-consent-manager}"
TOKEN_URL="${KEYCLOAK_BASE_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/token"
READINESS_URL="${MANAGEMENT_BASE_URL}/health/readiness"

# Clients, users and the shared password of docker/keycloak/consent-manager-realm.json.
PARTICIPANT_CLIENT_ID="consent-manager-participant-client"
PARTICIPANT_CLIENT_SECRET="participant-secret"
USER_CLIENT_ID="consent-manager-user-client"
USER_CLIENT_SECRET="user-secret"
PROVIDER_USERNAME="test-participant"
CONSUMER_USERNAME="test-participant-2"
DATA_SUBJECT_USERNAME="test-user"
TEST_PASSWORD="test-password"

# The ecosystem contract docker/wiremock serves; the service fetches it by the URI's last
# path segment from the Contract Service it is configured against.
CONTRACT_URI="${CONTRACT_URI:-http://wiremock:8080/contracts/66d0e2f7a1b2c3d4e5f60001}"

READINESS_TIMEOUT_SECONDS="${READINESS_TIMEOUT_SECONDS:-180}"
READINESS_POLL_SECONDS=3
# Bounds every single call, so a hung peer fails the run instead of blocking it.
CONNECT_TIMEOUT_SECONDS="${CONNECT_TIMEOUT_SECONDS:-5}"
REQUEST_TIMEOUT_SECONDS="${REQUEST_TIMEOUT_SECONDS:-60}"
EXPECTED_STATUS="GRANTED"

HTTP_OK=200
HTTP_CREATED=201
HTTP_CONFLICT=409

log() { printf '[smoke] %s\n' "$*"; }
fail() { printf '[smoke] FAIL: %s\n' "$*" >&2; exit 1; }

for tool in curl jq; do
    command -v "$tool" >/dev/null || fail "$tool is required"
done

RESPONSE_BODY="$(mktemp)"
trap 'rm -f "$RESPONSE_BODY"' EXIT

CURL_TIMEOUTS=(--connect-timeout "$CONNECT_TIMEOUT_SECONDS" --max-time "$REQUEST_TIMEOUT_SECONDS")

# Sends a request; prints the HTTP status and leaves the body in $RESPONSE_BODY.
# A transport failure (refused, timed out) prints 000, which expect_status reports.
request() {
    : >"$RESPONSE_BODY"
    curl -sS "${CURL_TIMEOUTS[@]}" -o "$RESPONSE_BODY" -w '%{http_code}' "$@" || true
}

# Fails with the response body unless the status is one of the accepted ones.
expect_status() {
    local step="$1" actual="$2"
    shift 2
    for accepted in "$@"; do
        [[ "$actual" == "$accepted" ]] && return 0
    done
    fail "$step answered HTTP $actual (expected $*): $(cat "$RESPONSE_BODY")"
}

# Password-grant access token for one realm user.
token() {
    local client_id="$1" client_secret="$2" username="$3" status
    status="$(request "$TOKEN_URL" \
        -d grant_type=password -d "client_id=$client_id" -d "client_secret=$client_secret" \
        -d "username=$username" -d "password=$TEST_PASSWORD")"
    expect_status "token request for $username" "$status" "$HTTP_OK"
    jq -er .access_token "$RESPONSE_BODY"
}

log "1. waiting for $READINESS_URL to report UP"
deadline=$((SECONDS + READINESS_TIMEOUT_SECONDS))
until [[ "$(curl -s "${CURL_TIMEOUTS[@]}" "$READINESS_URL" | jq -r .status 2>/dev/null)" == "UP" ]]; do
    ((SECONDS < deadline)) || fail "readiness not UP after ${READINESS_TIMEOUT_SECONDS}s"
    sleep "$READINESS_POLL_SECONDS"
done
log "   readiness UP"

log "2. obtaining tokens from $TOKEN_URL"
PROVIDER_TOKEN="$(token "$PARTICIPANT_CLIENT_ID" "$PARTICIPANT_CLIENT_SECRET" "$PROVIDER_USERNAME")"
CONSUMER_TOKEN="$(token "$PARTICIPANT_CLIENT_ID" "$PARTICIPANT_CLIENT_SECRET" "$CONSUMER_USERNAME")"
USER_TOKEN="$(token "$USER_CLIENT_ID" "$USER_CLIENT_SECRET" "$DATA_SUBJECT_USERNAME")"
log "   tokens for $PROVIDER_USERNAME, $CONSUMER_USERNAME, $DATA_SUBJECT_USERNAME"

log "3. registering both contract parties"
for entry in "$PROVIDER_USERNAME:$PROVIDER_TOKEN" "$CONSUMER_USERNAME:$CONSUMER_TOKEN"; do
    username="${entry%%:*}"
    status="$(request -X POST "$API_BASE_URL/participants" \
        -H "Authorization: Bearer ${entry#*:}" -H 'Content-Type: application/json' \
        -d "$(jq -n --arg name "Smoke test participant ($username)" '{legalName: $name}')")"
    expect_status "registration of $username" "$status" "$HTTP_CREATED" "$HTTP_CONFLICT"
    if [[ "$status" == "$HTTP_CONFLICT" ]]; then
        log "   $username already registered"
    else
        log "   $username registered as $(jq -r .identifier "$RESPONSE_BODY")"
    fi
done

log "4. generating a privacy notice from $CONTRACT_URI"
status="$(request -X POST "$API_BASE_URL/privacy-notices/by-contract" \
    -H "Authorization: Bearer $PROVIDER_TOKEN" -H 'Content-Type: application/json' \
    -d "$(jq -n --arg uri "$CONTRACT_URI" '{contractUri: $uri}')")"
expect_status "notice generation" "$status" "$HTTP_CREATED" "$HTTP_OK"
NOTICE_ID="$(jq -er .id "$RESPONSE_BODY")"
log "   notice $NOTICE_ID (HTTP $status)"

log "5. granting consent as $DATA_SUBJECT_USERNAME"
status="$(request -X POST "$API_BASE_URL/consents" \
    -H "Authorization: Bearer $USER_TOKEN" -H 'Content-Type: application/json' \
    -d "$(jq -n --arg id "$NOTICE_ID" --arg decision "$EXPECTED_STATUS" \
        '{privacyNoticeId: $id, decision: $decision}')")"
expect_status "consent grant" "$status" "$HTTP_CREATED" "$HTTP_OK"
CONSENT_ID="$(jq -er .id "$RESPONSE_BODY")"
log "   consent $CONSENT_ID (HTTP $status)"

log "6. reading the consent back from /consents/me"
status="$(request -G "$API_BASE_URL/consents/me" \
    -H "Authorization: Bearer $USER_TOKEN" --data-urlencode "privacyNoticeId=$NOTICE_ID")"
expect_status "consent listing" "$status" "$HTTP_OK"
actual="$(jq -r --arg id "$CONSENT_ID" '.content[] | select(.id == $id) | .status' "$RESPONSE_BODY")"
[[ "$actual" == "$EXPECTED_STATUS" ]] \
    || fail "consent $CONSENT_ID has status '${actual:-<absent>}', expected $EXPECTED_STATUS"
log "   consent $CONSENT_ID is $actual"

log "PASS"
