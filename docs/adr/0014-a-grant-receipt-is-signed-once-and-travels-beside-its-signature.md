# ADR 0014 — A grant receipt is signed once and travels beside its signature

- **Status:** Accepted.
- **Date:** 2026-10-15
- **Ticket:** Consent Signing (Taiga #77)
- **Supersedes in part:** [ADR-0013](0013-the-query-side-scopes-by-predicate-and-signs-what-it-attests.md)'s
  "A receipt carries a detached JWS" decision, as far as *where* the JWS travels and *when* a
  Kantara receipt is signed. The canonical form, the missing `jku`, the origin-root JWKS and the
  startup failure on a missing key all stand.
- **Relates to:** `format=receipt` / `format=receipt-kantara` on `GET /consents/me/{id}` and
  `GET /consents/{id}`, and `GET /.well-known/jwks.json`, in `api/openapi.yaml`; the
  `…consentmanager.signing` package (`ConsentSigningService`, `JwsConsentSigningService`,
  `SigningKeyStore`, `CanonicalJsonSerializer`); `ConsentReceiptIssuer`, `ConsentReceiptService`,
  `ConsentWriter` and `UserService` in `…consentmanager.service`; `IssuedReceipt` /
  `consent_receipts` (`V5__consent_receipts.sql`); [ADR-0007](0007-erasure-pseudonymises-the-user-and-retains-the-audit-trail.md),
  whose erasure now deletes stored receipts; and [ADR-0012](0012-a-consent-receipt-names-its-subject-and-is-dated-by-its-grant.md),
  which decides what a receipt says.

## Context

ADR-0013 signed every receipt when it was read and embedded the compact JWS as a `signature` member
of the receipt. That design had three gaps:

1. **The signature was not detached from the object it signed.** The payload was "the receipt minus
   `signature`". A verifier therefore had to know which member to strip, and the member was not part
   of the ISO/IEC 29184 or Kantara vocabularies.
2. **Signing at read time made a signing failure invisible at the moment that mattered.** A grant
   could succeed while its receipt could never be signed, and each read produced a new JWS for the
   same grant.
3. **The JWKS was not cacheable,** so every verifier fetched it on every check. Making it cacheable
   creates a window in which a verifier holds an outdated key set, and that window constrains key
   rotation.

The configuration contract stays as it is: an inline private JWK in `RECEIPTS_SIGNING_KEY` and a
public JWKS in `RECEIPTS_RETIRED_VERIFICATION_KEYS`. The ticket proposed PEM files listed under
`keys[]` with an `active-key-id`. The human reviewer confirmed keeping the existing contract on
PR #25 ("I confirm the decision", review comment 5792).

*Amended by ticket #82:* the same two values may also be read from mounted files,
`RECEIPTS_SIGNING_KEY_FILE` and `RECEIPTS_RETIRED_VERIFICATION_KEYS_FILE`, which is the production
form; the inline variables remain for local and test use. The content and model are unchanged, and
setting both forms of one value fails startup.

## Decision

**The JWS travels beside the receipt, not inside it.** `format=receipt` and
`format=receipt-kantara` answer `{ "receipt": {...}, "jws": "<header>..<signature>" }`
(`SignedConsentReceipt`, `SignedConsentReceiptKantara`). `jws` is a detached compact JWS (RFC 7515
Appendix F) over the RFC 8785 canonical form of the *whole* `receipt`. The receipt carries no
`signature` member and conforms to its vocabulary unchanged. The header names `alg`, `kid` and
`typ: "JOSE"`, and still has no `jku`. This is a breaking change to both receipt formats.

**The Kantara receipt is issued once, in the grant transaction, and stored.** It attests that
consent was given (ADR-0012), which is a fact fixed at the moment of the grant.
- `ConsentReceiptIssuer` renders it, dated by the granting event, signs it through
  `ConsentSigningService`, and stores the canonical text, the JWS and the `kid` in
  `consent_receipts`. A consent reaches `GRANTED` at most once, so the table is keyed by
  `consent_id`.
- Issuance is structural. `ConsentWriter` calls the issuer from its two persistence helpers,
  `saveNew` and `saveUpdated`, whenever the *recorded* status is `GRANTED`. It is not called from
  `decide`, `resume` or `move`. Every route into `GRANTED` that `ConsentStateMachine` admits issues a
  receipt, including routes added later, without further wiring.
- A signing failure raises `ReceiptSigningException` inside the `@Transactional` write. The consent,
  its event, any revocation of a superseded grant and the receipt all roll back, and the caller gets
  `500`. A grant is never recorded without its receipt.
- `format=receipt-kantara` keeps ADR-0012's `400` for a non-`GRANTED` consent. It then serves the
  stored canonical text and JWS and never signs again.

**The ISO/IEC 29184 record is signed on each read.** It carries the live `status` and `updatedAt`
and is rendered for every status, so a stored copy would be wrong after the next move. A signing
failure on that read answers `500`.

**The grant response is unchanged.** The subject fetches the signed receipt from
`GET /consents/me/{id}?format=receipt-kantara`. Placing it on `Consent` would also hand it to the
catalog on `POST /consents/{id}/resume`, and the catalog is not a party to the consent (ADR-0012).
No operation was added.

**Erasure deletes stored receipts.** A signed receipt names `piiPrincipalId`. Pseudonymising it would
break its signature. `UserService.erase` therefore deletes the subject's `consent_receipts` rows in
the erasure transaction (ADR-0007). Erasure already revokes every grant, so no Kantara read for that
subject is possible afterwards.

**No backfill.** A `GRANTED` consent with no stored receipt breaks an invariant and answers `500`,
the same way a grant with no granting event already does (ADR-0012). No deployment holds grants
older than V5.

**The JWKS is cacheable for one hour, so rotation takes two deploys.**
`GET /.well-known/jwks.json` is sent `Cache-Control: public, max-age=3600`, so a verifier may hold a
key set up to an hour old. A key must therefore be published one cache lifetime before it signs, and
it must stay published after it stops signing.
1. **Pre-publish.** Add the new key's *public* JWK to `RECEIPTS_RETIRED_VERIFICATION_KEYS`, leave
   `RECEIPTS_SIGNING_KEY` unchanged, and deploy. The new key is published but signs nothing.
2. **Wait** at least one `max-age` (one hour).
3. **Switch.** Set `RECEIPTS_SIGNING_KEY` to the new private JWK. In the same change, remove the new
   key's entry from `RECEIPTS_RETIRED_VERIFICATION_KEYS` (a `kid` present in both fails startup) and
   add the old key's public JWK. Deploy.
4. **Retire** the old public JWK only when no stored or distributed receipt still needs it. Receipts
   are long-lived, so normally it stays.

`RECEIPTS_RETIRED_VERIFICATION_KEYS` therefore holds every published key that does not sign: keys
that are *upcoming* as well as retired ones. The name is narrower than that role. It is kept because
renaming it would change the configuration contract that was deliberately kept. `SigningKeyStore`
treats both kinds alike, so the procedure needs no code.

## Consequences

- Clients of `format=receipt|receipt-kantara` must read `body.receipt` and `body.jws`. A verifier
  canonicalises `receipt` with RFC 8785, re-attaches it as the JWS payload, and checks it against the
  key the header's `kid` selects from the JWKS. It never strips a member first.
- Every Kantara read of a grant returns the same receipt, canonicalising to the stored text, and the
  same JWS. The stored JWS
  names the key that signed it, so a receipt stays verifiable for as long as that key is published.
- The signing key is now on the grant path. A missing or broken key already failed startup. A key
  that fails at run time now fails grants instead of degrading receipt reads only.
- A consent's grant receipt keeps the identifier the subject held at the time of the grant. Erasure
  is the only operation that removes it.
- A single-deploy key switch is unsafe. Receipts signed by the new key fail verification for up to
  an hour at any verifier holding a cached JWKS.

## Alternatives considered

- **Keep the embedded `signature` member.** Rejected. The payload is "the receipt minus one member",
  which a generic JWS verifier cannot compute, and the member is foreign to both vocabularies.
- **A JWS in a response header.** Rejected, as in ADR-0013. It is lost when the receipt is saved or
  forwarded as a document.
- **Persist the ISO/IEC 29184 record too.** Rejected. It carries the live status and `updatedAt`, so a
  stored copy would go stale on the next lifecycle move.
- **Keep signing the Kantara receipt on every read.** Rejected. A grant could be recorded whose
  receipt can never be signed, and the JWS for one grant would differ from read to read.
- **The ticket's PEM `keys[]` / `active-key-id` contract.** Declined, and the human reviewer
  confirmed this on PR #25 (review comment 5792). The inline-JWK variables already express an active
  key plus published non-signing keys. A second contract would add file mounts and a parsing path
  without adding a capability. Every acceptance criterion that names a PEM file applies to
  `RECEIPTS_SIGNING_KEY` or `RECEIPTS_RETIRED_VERIFICATION_KEYS` instead.
- **A short JWKS `max-age` or `no-store`.** Rejected. Every verifier would hit this service on every
  check, while a receipt's keys change only on a planned rotation, which the two-deploy procedure
  covers.
