# Contract Service stub (local development)

Part of the [local stack](../README.md); see [Documentation](../../docs/README.md) for every guide.

`compose.yaml` runs `wiremock/wiremock` with this directory as its root, and points the
`consent-manager` service's `CONTRACT_SERVICE_URL` at it. It answers the two endpoints `ContractServiceClient` calls:

| Request | Answer |
| --- | --- |
| `GET /contracts/66d0e2f7a1b2c3d4e5f60001` | ecosystem contract, `__files/ecosystem-contract.json` |
| `GET /bilaterals/66d0e2f7a1b2c3d4e5f60002` | bilateral contract, `__files/bilateral-contract.json` |
| any other `/contracts/*` or `/bilaterals/*` | `404`, "no such contract" |

Both contracts name `did:example:participant-1` (the `test-participant` user of the local Keycloak
realm) as data provider and `did:example:participant-2` (the `test-participant-2` user) as data
consumer. A notice is generated only when both parties are registered, and registration is
self-service, so each party registers itself with `POST /v1/participants` under its own token:

```bash
token() {
  curl -s http://localhost:8180/realms/consent-manager/protocol/openid-connect/token \
    -d grant_type=password -d client_id=consent-manager-participant-client \
    -d client_secret=participant-secret -d username="$1" -d password=test-password | jq -r .access_token
}
PROVIDER_TOKEN=$(token test-participant)
CONSUMER_TOKEN=$(token test-participant-2)
for t in "$PROVIDER_TOKEN" "$CONSUMER_TOKEN"; do
  curl -s http://localhost:8080/v1/participants -H "Authorization: Bearer $t" \
    -H 'Content-Type: application/json' -d '{"legalName": "Local test participant"}'
done
```

The service takes a contract's identifier from the last path segment of the contract URI, so
`http://wiremock:8080/contracts/66d0e2f7a1b2c3d4e5f60001` works as `contractUri`. The shapes follow
`src/test/resources/contracts/`. Nothing here ships: `ProductionArtefactTest` fails the build when
`target/classes` references WireMock or a WireMock artifact is a runtime dependency, and
`DevContractStubTest` holds the mappings to the client's routes.
