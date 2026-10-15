package com.seamware.consentmanager.signing;

import static com.seamware.consentmanager.signing.SigningFixtures.canonical;
import static com.seamware.consentmanager.signing.SigningFixtures.signing;
import static com.seamware.consentmanager.signing.SigningFixtures.store;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.Base64URL;
import com.seamware.consentmanager.support.ReceiptSignatures;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Pins that a signed receipt verifies against the published keys and that nothing else does. */
@DisplayName("Consent receipt signing")
class JwsConsentSigningServiceTest {

    private static final String KEY_ID = "receipts-2026";

    private static final String RETIRED_KEY_ID = "receipts-2025";

    private static final int RSA_KEY_SIZE = 3072;

    private static final String STATUS = "status";

    private static final String CONSENT_ID = "consentId";

    private static final String CONSENT_TIMESTAMP = "consentTimestamp";

    private static final String PURPOSES = "purposes";

    /** A value no member of {@link #receipt()} holds. */
    private static final String TAMPERED = "tampered";

    /** Separates the parts of a compact JWS. */
    private static final String DOT = ".";

    private static final int HEADER_PART = 0;

    private static final int PAYLOAD_PART = 1;

    private static final int SIGNATURE_PART = 2;

    private static final int COMPACT_PARTS = 3;

    /** Bit flipped in the first signature byte by the tamper matrix. */
    private static final int FLIPPED_BIT = 0x01;

    /** Not {@code @Serdeable}, so the application's mapper cannot encode it. */
    private record Unencodable(String value) {}

    /** Signs with {@link #KEY_ID} and also publishes {@link #RETIRED_KEY_ID}. */
    private static JwsConsentSigningService rotated;

    /** A receipt whose members a verifier recomputes. */
    private static Map<String, Object> receipt() {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put(CONSENT_ID, "0199e1f0-0000-7000-8000-000000000001");
        receipt.put(STATUS, "GRANTED");
        receipt.put("consented", true);
        receipt.put(CONSENT_TIMESTAMP, 1_767_225_600L);
        receipt.put(PURPOSES, List.of("Clinical research", "Ünïcode ✓"));
        return receipt;
    }

    @BeforeAll
    static void rotate() throws JOSEException {
        JWK retired = new ECKeyGenerator(Curve.P_256).keyID(RETIRED_KEY_ID).generate();
        rotated =
                signing(
                        store(
                                new ECKeyGenerator(Curve.P_256).keyID(KEY_ID).generate(),
                                new JWKSet(retired.toPublicJWK()).toString()));
    }

    static Stream<Arguments> algorithms() throws JOSEException {
        return Stream.of(
                Arguments.of("ephemeral", null, JWSAlgorithm.ES256),
                Arguments.of(
                        "EC P-256 with kid",
                        new ECKeyGenerator(Curve.P_256).keyID(KEY_ID).generate(),
                        JWSAlgorithm.ES256),
                Arguments.of(
                        "EC P-384 without kid",
                        new ECKeyGenerator(Curve.P_384).generate(),
                        JWSAlgorithm.ES384),
                Arguments.of(
                        "EC P-521", new ECKeyGenerator(Curve.P_521).generate(), JWSAlgorithm.ES512),
                Arguments.of(
                        "RSA 3072",
                        new RSAKeyGenerator(RSA_KEY_SIZE).keyID(KEY_ID).generate(),
                        JWSAlgorithm.RS256),
                Arguments.of(
                        "RSA naming PS256",
                        new RSAKeyGenerator(RSA_KEY_SIZE).algorithm(JWSAlgorithm.PS256).generate(),
                        JWSAlgorithm.PS256));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("algorithms")
    @DisplayName("signs a detached JWS naming alg, kid and typ that the published key verifies")
    void signsVerifiably(String name, JWK configured, JWSAlgorithm algorithm) throws Exception {
        SigningKeyStore keys = store(configured);
        JwsConsentSigningService signing = signing(keys);
        Map<String, Object> receipt = receipt();

        SignedReceipt<Map<String, Object>> signed = signing.sign(receipt);

        String[] parts = signed.jws().split("\\" + DOT, -1);
        assertThat(parts).hasSize(COMPACT_PARTS);
        assertThat(parts[PAYLOAD_PART]).isEmpty();
        JWSHeader header = JWSHeader.parse(Base64URL.from(parts[HEADER_PART]));
        assertThat(header.getAlgorithm()).isEqualTo(algorithm);
        assertThat(header.getKeyID()).isEqualTo(keys.keyId()).isEqualTo(signed.keyId());
        assertThat(header.getType()).isEqualTo(JOSEObjectType.JOSE);
        assertThat(header.getJWKURL()).isNull();
        if (configured != null && configured.getKeyID() != null) {
            assertThat(keys.keyId()).isEqualTo(configured.getKeyID());
        }

        JWK published = keys.verificationKeys().getFirst();
        assertThat(published.isPrivate()).isFalse();
        assertThat(published.getKeyUse()).isEqualTo(KeyUse.SIGNATURE);
        assertThat(published.getAlgorithm()).isEqualTo(algorithm);
        assertThat(published.getKeyID()).isEqualTo(keys.keyId());

        assertThat(signed.receipt()).isSameAs(receipt);
        assertThat(signed.canonicalJson()).isEqualTo(canonical().toJson(receipt));
        assertThat(signing.verify(receipt, signed.jws())).isTrue();
        assertThat(signing.verifyCanonical(signed.canonicalJson(), signed.jws())).isTrue();
        assertThat(ReceiptSignatures.verifies(receipt, signed.jws(), new JWKSet(published)))
                .isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {CONSENT_ID, STATUS, "consented", CONSENT_TIMESTAMP, PURPOSES})
    @DisplayName("signs the whole receipt, so altering any one member breaks verification")
    void coversEveryMember(String member) {
        Map<String, Object> receipt = receipt();
        String jws = rotated.sign(receipt).jws();

        receipt.put(member, TAMPERED);

        assertThat(rotated.verify(receipt, jws)).isFalse();
    }

    static Stream<Arguments> tampering() {
        return Stream.of(
                receiptTamper(
                        "one character of a string member changed",
                        receipt -> {
                            String id = (String) receipt.get(CONSENT_ID);
                            receipt.put(CONSENT_ID, id.substring(0, id.length() - 1) + "2");
                            return receipt;
                        }),
                receiptTamper(
                        "a number changed",
                        receipt -> {
                            receipt.put(
                                    CONSENT_TIMESTAMP, (Long) receipt.get(CONSENT_TIMESTAMP) + 1);
                            return receipt;
                        }),
                receiptTamper(
                        "a member added",
                        receipt -> {
                            receipt.put(TAMPERED, TAMPERED);
                            return receipt;
                        }),
                receiptTamper(
                        "a member removed",
                        receipt -> {
                            receipt.remove(PURPOSES);
                            return receipt;
                        }),
                jwsTamper("a byte of the signature flipped", JwsConsentSigningServiceTest::flipped),
                jwsTamper(
                        "the kid swapped for another published key's",
                        jws -> withHeader(jws, header(JWSAlgorithm.ES256, RETIRED_KEY_ID))),
                jwsTamper(
                        "a kid no key is published under",
                        jws -> withHeader(jws, header(JWSAlgorithm.ES256, TAMPERED))),
                jwsTamper(
                        "an alg the named key does not publish",
                        jws -> withHeader(jws, header(JWSAlgorithm.ES384, KEY_ID))),
                jwsTamper("an attached payload", JwsConsentSigningServiceTest::attached),
                jwsTamper("not a JWS", jws -> TAMPERED));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tampering")
    @DisplayName("refuses a tampered receipt or JWS without throwing")
    void refusesTampering(
            String name,
            UnaryOperator<Map<String, Object>> receiptTamper,
            UnaryOperator<String> jwsTamper) {
        String jws = rotated.sign(receipt()).jws();

        Map<String, Object> presented = receiptTamper.apply(receipt());
        String presentedJws = jwsTamper.apply(jws);

        assertThat(rotated.verify(presented, presentedJws)).isFalse();
        assertThat(rotated.verifyCanonical(canonical().toJson(presented), presentedJws)).isFalse();
    }

    @Test
    @DisplayName("refuses a signature by a different key that carries the same kid")
    void refusesAnImpostorKeyWithTheSameKeyId() throws JOSEException {
        JwsConsentSigningService impostor =
                signing(store(new ECKeyGenerator(Curve.P_256).keyID(KEY_ID).generate()));
        Map<String, Object> receipt = receipt();

        assertThat(rotated.verify(receipt, impostor.sign(receipt).jws())).isFalse();
    }

    @Test
    @DisplayName("verifies a receipt signed before a rotation by the now-retired key")
    void verifiesARetiredKeysSignature() throws JOSEException {
        JWK retired = new ECKeyGenerator(Curve.P_256).keyID(RETIRED_KEY_ID).generate();
        Map<String, Object> receipt = receipt();
        String jws = signing(store(retired)).sign(receipt).jws();

        JwsConsentSigningService afterRotation =
                signing(
                        store(
                                new ECKeyGenerator(Curve.P_384).keyID(KEY_ID).generate(),
                                new JWKSet(retired.toPublicJWK()).toString()));

        assertThat(afterRotation.verify(receipt, jws)).isTrue();
    }

    @Test
    @DisplayName("fails rather than return an unsigned receipt when the receipt cannot be encoded")
    void neverReturnsAnUnsignedReceipt() {
        Unencodable unencodable = new Unencodable(TAMPERED);

        assertThatThrownBy(() -> rotated.sign(unencodable))
                .isInstanceOf(ReceiptSigningException.class)
                .hasCauseInstanceOf(RuntimeException.class);
        assertThat(rotated.verify(unencodable, rotated.sign(receipt()).jws())).isFalse();
    }

    private static Arguments receiptTamper(
            String name, UnaryOperator<Map<String, Object>> receiptTamper) {
        return Arguments.of(name, receiptTamper, UnaryOperator.<String>identity());
    }

    private static Arguments jwsTamper(String name, UnaryOperator<String> jwsTamper) {
        return Arguments.of(name, UnaryOperator.<Map<String, Object>>identity(), jwsTamper);
    }

    private static JWSHeader header(JWSAlgorithm algorithm, String keyId) {
        return new JWSHeader.Builder(algorithm).type(JOSEObjectType.JOSE).keyID(keyId).build();
    }

    private static String withHeader(String jws, JWSHeader header) {
        String[] parts = jws.split("\\" + DOT, -1);
        return header.toBase64URL() + DOT + DOT + parts[SIGNATURE_PART];
    }

    private static String flipped(String jws) {
        String[] parts = jws.split("\\" + DOT, -1);
        byte[] signature = Base64URL.from(parts[SIGNATURE_PART]).decode();
        signature[0] ^= FLIPPED_BIT;
        return parts[HEADER_PART] + DOT + DOT + Base64URL.encode(signature);
    }

    private static String attached(String jws) {
        String[] parts = jws.split("\\" + DOT, -1);
        Base64URL payload =
                Base64URL.encode(canonical().toJson(receipt()).getBytes(StandardCharsets.UTF_8));
        return parts[HEADER_PART] + DOT + payload + DOT + parts[SIGNATURE_PART];
    }
}
