# ADR 0006 — Require the identifier claim of the acting role, not of every granted role

- **Status:** Accepted.
- **Date:** 2026-10-05
- **Ticket:** TICKET-003 (Taiga #67), step 6
- **Relates to:** [ADR 0005](0005-own-token-validator-on-micronaut-security-jwt.md), which decides
  the validation pipeline this ADR adjusts one step of. The contract it publishes lives in
  `api/components/security.yaml`.

## Context

The ticket's validation pipeline (section 2, step 8) says a `PARTICIPANT`-role token with no
participant identifier fails validation with `401`, and its Catalog Token table lists the
participant-identifier claim as required. Both were written for a token granting exactly one role.

The same ticket also defines a catalog token as "the participant token, but bearing the role mapped
to `CATALOG`", and the natural way to issue one from a Keycloak realm — the one step 8 has to mint —
is a service account holding both `dataspace-participant` and `dataspace-catalog`. Such an account
has no participant identifier: the catalog acts for the dataspace, not for one organisation, and
`CatalogPrincipal` carries no identifier at all. Read literally, the rule rejects with `401` the
exact token the contract calls the normal catalog token.

The two rules cannot both hold for a multi-role token, because whether an identifier is needed
depends on which role the caller acts as, and that is not known at validation time: it depends on
the operation the request is routed to (see `Role.effective`).

## Decision

Split the requirement across the two layers that each know one half of it.

- **Authentication (`ConsentManagerTokenValidator`)** requires the identifier claim of **at least
  one** granted role; `CATALOG` is satisfied by issuer and subject alone. A token carrying none is
  rejected with `401`. This is the strongest check possible without knowing the route.
- **Authorization (`PrincipalResolutionFilter`)** requires the identifier of the **acting** role,
  which it knows because it has the matched route. A token missing it is refused with `403`.

For a single-role token this is exactly the ticket's rule, with the same status code. It differs
only for multi-role tokens, where the refusal moves from `401` to `403`:

| Token | Ticket as written | This decision |
| --- | --- | --- |
| `PARTICIPANT`, no participant identifier | `401` | `401` (unchanged) |
| `PARTICIPANT` + `CATALOG`, no participant identifier | `401` | authenticates; `403` on a participant-scoped operation |
| `USER` + `PARTICIPANT`, user identifier only | `401` | authenticates; `403` on a participant-scoped operation |

## Consequences

- The catalog service account the contract describes authenticates, so step 8's Keycloak role matrix
  can mint its catalog token the documented way.
- A multi-role token missing an identifier is refused later and with a different status than the
  ticket's prose says. `403` is the correct code for it under this service's own 401/403 rule — the
  token authenticated; it simply does not name a caller this service will act for that operation —
  but a later ticket reading the ticket text alone would not predict it. That is why it is written
  down here and in `api/components/security.yaml`.
- Neither layer ever grants more than the strict rule would: every refusal the strict rule makes is
  still made, by one layer or the other.
- Step 7's just-in-time provisioning is unaffected: it runs behind the acting-role check, so a
  `UserPrincipal` always carries a resolved user identifier.
