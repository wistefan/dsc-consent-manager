# Implementation Plan: Consent Manager - Reimplementation - Consent Signing

## Overview
Receipt signing already exists. `security/ReceiptSigner` makes a detached JWS over the RFC 8785 form,
`ReceiptSigningController` serves `GET /.well-known/jwks.json`, and `ConsentReceiptService` signs on
every read and embeds the JWS as a `signature` member. This ticket fills the gaps and does not rebuild
that work:
- make the signature truly detached;
- extract a `signing` package with a `ConsentSigningService` that can also `verify`;
- sign and **persist** the grant receipt inside the grant transaction, so a signing failure fails the grant;
- make the JWKS cacheable;
- prove the stated security properties with tests.

The configuration contract stays as it is (`consent-manager.receipts.signing-key` /
`retired-verification-keys` / `ephemeral-signing-key`, inline JWK). The ticket's PEM `keys[]` /
`active-key-id` contract is **not** added; the human decided this on the ticket.

### Design decisions (recorded in ADR-0014 in Step 5)
1. **Detached means beside the receipt, not inside it.** `format=receipt|receipt-kantara` answers a
   wrapper `{ "receipt": {...}, "jws": "<header>..<signature>" }`. The wrappers are declared inline in
   `openapi.yaml`. The `signature` member is removed from `ConsentReceipt` and `ConsentReceiptKantara`,
   and the JWS payload is the RFC 8785 form of the *whole* receipt. ADR-0013 already rejected a
   response header (it is lost when the receipt is saved as a document) and an attached JWS (it hides
   the vocabulary). This is a breaking API change, which the human allowed.
2. **The grant receipt is persisted. The status record is signed on read.**
   - The Kantara receipt attests that consent was given (ADR-0012). It is issued once, in the
     transaction that moves a row to `GRANTED`: a decision via `ConsentWriter.decide` (create, or
     answer a `PENDING`) and a resume via `ConsentWriter.resume`. Its canonical text and JWS are stored
     in `consent_receipts`, and `format=receipt-kantara` serves those stored bytes. A row reaches
     `GRANTED` at most once (`ConsentStateMachine`: only `PENDING → GRANTED` or created `GRANTED`), so
     the table is keyed by `consent_id`.
   - The ISO/IEC 29184 record carries the live `status` and `updatedAt` and is rendered for every
     status. It therefore stays rendered at read time and is signed on each read.
   - The ADR-0012 refusal (`400` for Kantara on a non-`GRANTED` consent) is unchanged.
3. **The grant response is unchanged.** The subject fetches the signed receipt from
   `GET /consents/me/{id}?format=receipt-kantara`. Putting it on `Consent` would also hand it to the
   catalog on `POST /consents/{id}/resume`, and the catalog is not a party to the consent (ADR-0012).
   No operation is added, so `SPECIFIED_OPERATION_COUNT` stays **34**.
4. **Erasure deletes stored receipts.** A signed receipt names `piiPrincipalId` and cannot be
   pseudonymised without breaking its signature, so `UserService.erase` deletes the subject's
   `consent_receipts` rows in its transaction (ADR-0007). Erasure already revokes every grant, so no
   Kantara read for that subject is possible afterwards.
5. **No backfill.** A `GRANTED` consent with no stored receipt breaks an invariant and answers `500`,
   just as a grant with no granting event already does (ADR-0012). No deployment holds pre-V5 grants.

## Steps

### Step 1: Make the receipt signature detached in the API
Spec and rendering only. Signing still happens on read through the existing `ReceiptSigner`.
- `api/components/schemas/ConsentReceipt.yaml`, `ConsentReceiptKantara.yaml`:
  - remove the `signature` property and its `required` entry;
  - update the header comments (`ConsentReceiptKantara.yaml` lines 14-15 call `signature` a non-KI-CR
    key).
- `api/openapi.yaml`:
  - declare inline `SignedConsentReceipt` (`receipt: $ref "#/components/schemas/ConsentReceipt"`,
    `jws: string`) and `SignedConsentReceiptKantara` (same shape, Kantara receipt). Both are required,
    neither is nullable.
  - Describe `jws` as a detached compact JWS (RFC 7515 Appendix F) over the RFC 8785 form of
    `receipt`, verified against `/.well-known/jwks.json` by the header's `kid`.
  - Change the `ConsentRepresentation` `oneOf` to `ConsentResponse | SignedConsentReceipt |
    SignedConsentReceiptKantara`.
  - Update the `format` parameter text on `getConsent` and `getMyConsent` and the JWKS operation
    description, which talks about "a receipt's `signature`".
- Run `./mvnw generate-sources` and **check the generated types**: the `receipt` fields must be the
  typed models, not `Object`, and both wrappers must implement `ConsentRepresentation`.
- `security/ReceiptSigner`: drop `SIGNATURE_MEMBER` and the member-stripping in `canonicalForm`, so the
  payload is the canonical form of the whole receipt.
- `service/ConsentReceiptService`: `toIso29184`/`toKantara` return the wrappers. Remove the `UNSIGNED`
  constructor argument.
- `api/ConsentQueryController.render` returns the wrappers.
- Tests to update: `ConsentReceiptServiceTest` (it pins the Kantara key set, which now has no
  `signature`), `ConsentReceiptIT` (`carriesASignatureThePublishedKeysVerify` reads
  `body.receipt` + `body.jws`), `ReceiptSignerTest`, the test helper `support/ReceiptSignatures`
  (which re-attaches the payload), `ConsentRepresentationSerdeTest`, and any parity test touching
  receipt properties.
- Acceptance: `./mvnw verify` is green. A receipt read returns `{receipt, jws}`, the receipt carries no
  `signature`, and the JWS verifies over `JCS(receipt)` with the published key.

### Step 2: Extract `signing` package with `ConsentSigningService` and `verify`
Replace `security/ReceiptSigner` with a package `com.seamware.consentmanager.signing`. Behaviour and
configuration stay as they are.
- `CanonicalJsonSerializer` (`@Singleton`): RFC 8785 bytes/string of any serialisable value or raw JSON
  text, through `JsonMapper` + `org.erdtman.jcs.JsonCanonicalizer`, which is already in `pom.xml`.
- `SigningKeyStore` (`@Context`, eager): moves `ReceiptSigner`'s `load` / `ephemeral` /
  `verificationKeys` / `algorithmFor` / `keyIdOf` / `publish` and its startup messages verbatim. It
  exposes the active signing key + algorithm + `kid`, `verificationKeys()` (active first, then
  retired), and `verificationKey(kid)` → `Optional<JWK>`.
  - Every startup message names the property/variable at fault (`RECEIPTS_SIGNING_KEY`,
    `RECEIPTS_RETIRED_VERIFICATION_KEYS`) and what is wrong. No message or cause may quote key
    material.
  - Today `UNUSABLE_KEY` keeps its `JOSEException` cause. Drop that cause unless a test proves it
    carries no key material.
  - `ConsentManagerConfiguration.Receipts` must never gain `@ToString`/`@Data`, because `signingKey` is
    a secret.
- `SignedReceipt<R>` record: `receipt`, `jws`, `keyId`, plus the canonical JSON that was signed.
  Persisting that canonical JSON is what Step 3 needs.
- `ConsentSigningService` interface:
  - `<R> SignedReceipt<R> sign(R receipt)`
  - `boolean verify(Object receipt, String detachedJws)`
  - `boolean verifyCanonical(String canonicalJson, String detachedJws)`
- `JwsConsentSigningService` (`@Singleton`):
  - the JWS header is `alg`, `kid`, `typ: "JOSE"` (`JOSEObjectType.JOSE`), with no `jku`;
  - `sign` throws `ReceiptSigningException` (new, unchecked, **not** a `DataAccessException`) on any
    failure and never returns an unsigned result;
  - `verify` picks the key by the header's `kid` from `SigningKeyStore`, requires the header `alg` to
    equal the key's published `alg`, re-attaches the canonical payload, and answers `false` (never
    throws) for an unknown `kid`, a malformed JWS, an attached payload or a bad signature.
- Repoint `ConsentReceiptService` and `ReceiptSigningController` to the new beans. Delete
  `security/ReceiptSigner.java` and move `ReceiptSignerTest` into `signing/`.
- Tests (`src/test/java/.../signing/`), parameterized:
  - `JwsConsentSigningServiceTest`:
    - algorithm matrix (`@MethodSource`: EC P-256 → ES256, RSA 3072 → RS256, plus the existing
      P-384/P-521 rows), each asserting header `alg`/`kid`/`typ`, detached form (empty payload part),
      and sign → verify;
    - tamper matrix: one character of a string member changed, a number changed, a member
      added/removed, a byte of the JWS signature flipped, the header's `kid` swapped, all → `false`;
    - a signature by a different key carrying the same `kid` → `false`;
    - a signature by a retired key whose public half is configured → `true`.
  - `CanonicalJsonSerializerTest`: the same object with permuted key order, and with numerically equal
    forms (`1`, `1.0`, `1e0`, `10e-1`), canonicalise identically. A receipt serialised by Micronaut
    serde, parsed and re-serialised through a different JSON library (Nimbus's
    `JSONObjectUtils.parse`/`toJSONString`; add `jackson-databind` at test scope only if that proves
    insufficient), still verifies.
  - `SigningKeyStoreTest`: `@ParameterizedTest` over missing / malformed / public-only / unsupported
    curve / mismatched `alg` / private-in-retired / duplicate `kid`. Assert the message names the
    variable and that the message, **every cause** and the printed stack trace contain none of the
    key's private members (`d`, `p`, `q`, `dp`, `dq`, `qi`). Also boot an `ApplicationContext` with a
    malformed key and assert the startup exception fails the same way.
- Acceptance: `./mvnw verify` is green and no reference to `ReceiptSigner` remains.

### Step 3: Sign and persist the grant receipt inside the grant transaction
- `src/main/resources/db/migration/V5__consent_receipts.sql`, creating `consent_receipts`:
  - `consent_id UUID PRIMARY KEY REFERENCES consents(id) ON DELETE CASCADE`
  - `event_id UUID NOT NULL REFERENCES consent_events(id) ON DELETE CASCADE`
  - `canonical_receipt TEXT NOT NULL`. This is TEXT, not JSONB, because JSONB normalises and the
    stored bytes must be the ones that were signed.
  - `jws TEXT NOT NULL`
  - `key_id TEXT NOT NULL`
  - `issued_at TIMESTAMPTZ NOT NULL`
  - Comment the table the way V1-V4 are commented.
- `domain/IssuedReceipt` (`@MappedEntity("consent_receipts")`; the name avoids clashing with the
  generated `ConsentReceipt`) and `repository/IssuedReceiptRepository`, with `findById(consentId)` and
  a `deleteByUserId(UUID)` query (`DELETE … WHERE consent_id IN (SELECT id FROM consents WHERE
  user_id = :userId)`).
- `ConsentReceiptService`: split Kantara rendering so it takes the grant instant explicitly
  (`toKantara(details, grantedAt)`). Issuance passes the event it just wrote and does not re-query it.
- New `service/ConsentReceiptIssuer` (`@Singleton`), `issue(RecordedConsent)`:
  1. resolve `ConsentDetails` through `ConsentParties`;
  2. render the Kantara receipt dated by `recorded.event().getOccurredAt()`;
  3. `ConsentSigningService.sign`;
  4. save an `IssuedReceipt`.
  It is a separate bean, called from inside the writer's transaction.
- `ConsentWriter`: after the consent and its event are saved, call the issuer whenever the recorded
  status is `GRANTED`, in `decide` (both the `create` and the `answer` branch) and in `resume`.
  REFUSED/PENDING write nothing. A `ReceiptSigningException` propagates out of the `@Transactional`
  method, so the consent, its event, any supersede-revocation and the receipt all roll back.
  `ConsentService.record` and `ConsentLifecycleService.resume` catch only `DataAccessException`, so
  the failure becomes `500`; confirm that and leave it so.
- Read side: `format=receipt-kantara` keeps the ADR-0012 `400` for non-`GRANTED`. It then loads the
  `IssuedReceipt`, deserialises `canonical_receipt` into `ConsentReceiptKantara`, and wraps it with the
  stored `jws` without re-signing. A missing row throws `IllegalStateException` (an invariant, `500`).
  `format=receipt` (ISO) keeps signing on read through `ConsentSigningService`. If
  `ConsentEventRepository.findFirstByConsentIdAndEventStateInOrderByOccurredAtDesc` is now unused,
  remove it together with its stubs (`UserServiceTest`, `ConsentReceiptServiceTest`) and any
  `ConsentQueryIndexUsageIT` row.
- `UserService.erase`: call `IssuedReceiptRepository.deleteByUserId` inside the erasure transaction,
  and extend the erasure test/IT so that no `consent_receipts` row of the subject survives.
- Tests:
  - `ConsentReceiptIssuanceIT` (Postgres), parameterized over the grant paths: fresh decision,
    answering a `PENDING` request, resume.
    - Each path stores exactly one row, and its `jws` verifies over `canonical_receipt` through
      `verifyCanonical`.
    - A `REFUSED` decision stores none.
    - Two consecutive `receipt-kantara` reads return the identical `jws`. ECDSA is randomised, so
      equal values prove the receipt was not re-signed.
    - `JCS(served receipt)` equals the stored `canonical_receipt`.
  - `SigningFailureIT` uses a `@Replaces(JwsConsentSigningService.class)` failing bean gated by
    `@Requires(property = "test.signing.fail", value = "true")`, so it activates only there.
    - `POST /consents` GRANTED → `500` problem detail, with no consent, event or receipt row left
      behind.
    - A grant superseding an existing grant leaves the old grant `GRANTED`.
    - `POST /consents/{id}/resume` → `500`, and the row stays `PENDING`.
    - `format=receipt` read → `500`.
    - A `REFUSED` decision still answers `201`.
  - `ConsentReceiptIT` seeds `GRANTED` rows directly. Seed them through `ConsentWriter` or the issuer
    so they carry a stored receipt.
- Acceptance: `./mvnw verify` is green and the grant criteria above hold.

### Step 4: Make the JWKS cacheable and prove no key material leaks
- `api/openapi.yaml` `getReceiptSigningKeys` `200`: declare the header `Cache-Control` with `enum:
  ["public, max-age=3600"]` and description text, mirroring how `checkConsentValidity` declares
  `no-store`.
- `ReceiptSigningController`: set the header from a named constant (e.g. `JWKS_CACHE_CONTROL`). Keep
  the class and tag names, because renaming the tag renames the generated abstract controller for no
  gain. `security: []` stays, and the `EndpointRoleMatrixIT` row stays `200` for every principal.
- `JwksIT` (with security on, through `KeycloakAndPostgresTestResource`; configure an active key plus a
  retired public key via `@Property`):
  - an anonymous `200` with `Cache-Control: public, max-age=3600`;
  - every key carries `kid`, `alg` and `use: "sig"`, and **no** private member (`d`, `p`, `q`, `dp`,
    `dq`, `qi`, `k`);
  - the set holds the active key first and every retired key;
  - the response parses with Nimbus `JWKSet.parse`;
  - a receipt signed by the retired key's private half verifies against the served JWKS with plain
    Nimbus (`JWSObject.parse(jws, new Payload(canonical))` + `DefaultJWSVerifierFactory`), not only
    through `ConsentSigningService.verify`.
- `KeyMaterialLeakIT` / `-Test`:
  - attach a Logback `ListAppender` to the root logger;
  - boot with a known signing key, sign a receipt, trigger the ephemeral-key warning, and trigger a
    signing failure (reuse Step 3's failing bean or a broken signer);
  - assert that no captured log event (message, arguments, throwable chain) and no problem-detail body
    contains any private member value.
- Acceptance: `./mvnw verify` is green.

### Step 5: Documentation, ADR and configuration guidance
- New `docs/adr/0014-a-grant-receipt-is-signed-once-and-travels-beside-its-signature.md`. It records
  the five design decisions in the Overview: the wrapper versus the embedded member, Kantara persisted
  versus ISO signed on read, the unchanged grant response, erasure deleting stored receipts, and no
  backfill. Alternatives: persisting the ISO record (it would go stale on the next move), a response
  header, signing on every read, and the PEM `keys[]` contract (declined, with the human's reason).
  Add a "Superseded in part by ADR-0014" note to ADR-0013's receipt-signature bullet. Do not rewrite
  ADR-0013's decision.
- `docs/security.md` "Receipt signing" paragraph:
  - `signing` package names;
  - `typ: JOSE`;
  - the wrapper;
  - grant-time issuance and that a failure fails the grant;
  - JWKS caching, and that a rotation becomes visible to verifiers within `max-age`. This is why a
    retired key must stay published, and a new key should be added a cache lifetime before it
    signs.
  Also update the endpoint table, the `security` package listing and the erasure section.
- `.env.sample` + `application.yml` comments on `RECEIPTS_SIGNING_KEY`: document generating a key.
  ES256 is preferred and RS256 is for compatibility. Give the ticket's `openssl` commands plus a
  conversion to the private JWK the variable takes (e.g. `step crypto key format --jwk` or
  `step crypto jwk create … --kty EC --crv P-256 --alg ES256 --use sig --kid … --no-password
  --insecure`; verify the exact flags). Also document the rotation procedure: move the old public JWK
  to `RECEIPTS_RETIRED_VERIFICATION_KEYS`, set the new key, redeploy, and retire the old key only
  when no receipt needs it. No generation scripts are shipped.
- `AGENTS.md`: update the project structure for the `signing/` package, `IssuedReceipt`,
  `ConsentReceiptIssuer`, V5 and ADR-0014, and add a Key Conventions bullet on receipt issuance:
  - the Kantara receipt is issued once, in the grant transaction;
  - a signing failure fails the grant;
  - never re-sign a stored receipt.
- Acceptance: docs are consistent with the code, `./mvnw verify` (Spotless included) is green, and no
  doc still describes an embedded `signature` member or `ReceiptSigner`.
