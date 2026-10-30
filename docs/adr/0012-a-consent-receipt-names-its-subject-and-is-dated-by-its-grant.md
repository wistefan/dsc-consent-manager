# ADR 0012 — A consent receipt names its subject to the parties and is dated by its grant

[Documentation](../README.md) › [ADR index](README.md) › ADR 0012

- **Status:** Accepted.
- **Date:** 2026-10-15
- **Ticket:** TICKET-010 (Taiga #74), step 5
- **Relates to:** `format=receipt` / `format=receipt-kantara` on `GET /consents/me/{id}` and
  `GET /consents/{id}` in `api/openapi.yaml`; `ConsentReceiptService`, `ConsentFactory` and
  `ConsentEventRepository`; [ADR-0007](0007-erasure-pseudonymises-the-user-and-retains-the-audit-trail.md),
  whose pseudonym a receipt renders; and [ADR-0011](0011-a-lifecycle-move-is-recorded-here-and-notified-elsewhere.md),
  which already dates a re-confirmation by its own event.

## Context

A consent can be read back as an ISO/IEC 29184 record or a Kantara KI-CR-v1.1.0 receipt, on the
data subject's route and on the participant's. Both renderings are drawn only from the frozen
`consents.snapshot` and `contract_uri`, never from the live notice, so archiving or regenerating
the notice cannot rewrite one. Three further choices were not forced by that rule.

**Who learns the subject's identifier.** Both receipt vocabularies name the data subject
(`piiPrincipalId`). `format=full` deliberately carries no user identifier, and the participant
listing answers an empty page for an identifier the caller is not linked to, so that a participant
cannot learn who exists. A receipt read on `GET /consents/{id}` would give the identifier to any
provider or consumer on the consent, linked to the subject or not.

**What fills a field the notice never stated.** KI-CR-v1.1.0 marks keys MUST — `policyUrl`,
`sensitive`, `spiCat`, `purposeCategory`, the controller's `address`, `email` and `phone` — for
which a notice carries no source.

**What dates the receipt.** `consentTimestamp` is the moment consent was given. `updated_at` equals
it only while nothing writes a granted row without moving it, an invariant no code enforces.

## Decision

**A receipt names its subject, on either route.** A receipt is evidence that *this* person agreed,
and one that does not say who agreed attests nothing. The subject agreed to share with exactly the
provider and consumer on the consent, and only they reach `GET /consents/{id}`: the route answers
`404` to any other caller, and it is addressed by the consent's own identifier, so it cannot be
used to probe whether an identifier exists. The empty-page rule on the listing protects identifiers
the caller has no consent for; it does not apply to one it does. An erased subject renders as the
ADR-0007 pseudonym, because the receipt reads the identifier off the `users` row erasure rewrites.

**Every MUST key is on the wire, and none is invented.** A field without a source carries an honest
empty value: `""` for the controller's `email` and `phone`, an empty `address`, an empty
`purposeCategory` and `spiCat`, and `sensitive: false` because a notice classifies no PII as
sensitive. `policyUrl` falls back to the notice as `urn:uuid:<noticeId>` where the consent carries
no contract, and `termination` to this service's revocation route where the notice stated none. A
consumer validating against the published schema accepts the receipt; a reader sees what was never
stated rather than a plausible fiction.

**`consentTimestamp` comes from the event that granted the consent.** It is the latest
`CONSENT_GIVEN` or `CONSENT_RESUMED` event on the consent — `ConsentFactory.GRANTING_EVENTS` — read
through `idx_consent_events_consent_id_occurred_at`. A re-confirmation is excluded: it affirms a
grant already in force and does not date a new one. A granted consent with no granting event
violates the factory's invariant and is refused as an internal error rather than dated by guess.

**A Kantara receipt attests a grant, so it is refused for any other status** with `400`; the ISO
record renders every status. Neither format is offered on the listings, which answer `400`.

## Consequences

- **A participant party to a consent can learn its subject's identifier without a link.** This is
  intended, and the only such route; a new route that renders a receipt must keep to the consent's
  own parties.
- **A receipt's date cannot drift.** A later write to the consent row that is not a status move —
  erasure, lineage linking, a backfill — leaves `consentTimestamp` alone. The ISO record's
  `updatedAt` is labelled as the row's last state change and is *not* so protected.
- **Rendering a Kantara receipt costs one indexed lookup on `consent_events`** besides the subject's
  row.
- **The empty values are part of the contract.** If a notice later gains a source for one of these
  fields, the receipt starts carrying it, and the empty value stays the answer for older snapshots.
