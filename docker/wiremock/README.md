# Contract Service stub (local development)

`compose.yaml` runs `wiremock/wiremock` with this directory as its root, and points the app's
`CONTRACT_SERVICE_URL` at it. It answers the two endpoints `ContractServiceClient` calls:

| Request | Answer |
| --- | --- |
| `GET /contracts/66d0e2f7a1b2c3d4e5f60001` | ecosystem contract, `__files/ecosystem-contract.json` |
| `GET /bilaterals/66d0e2f7a1b2c3d4e5f60002` | bilateral contract, `__files/bilateral-contract.json` |
| any other `/contracts/*` or `/bilaterals/*` | `404`, "no such contract" |

Both contracts name `did:example:participant-1` (the `test-participant` user of the local Keycloak
realm) as data provider and `did:example:participant-2` as data consumer. A notice is generated
only when both parties are registered, so register the consumer with a token carrying that
`participant_id` first.

The service takes a contract's identifier from the last path segment of the contract URI, so
`http://wiremock:8080/contracts/66d0e2f7a1b2c3d4e5f60001` works as `contractUri`. The shapes follow
`src/test/resources/contracts/`. Nothing here ships: `ProductionArtefactTest` fails the build when
`target/classes` references WireMock.
