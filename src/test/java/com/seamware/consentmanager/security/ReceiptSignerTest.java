package com.seamware.consentmanager.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.OctetSequenceKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.support.ReceiptSignatures;
import com.seamware.consentmanager.support.TestJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Pins that a signed receipt verifies against the published key and nothing else does. */
@DisplayName("Receipt signing")
class ReceiptSignerTest {

    private static final String KEY_ID = "receipts-2026";

    private static final int RSA_KEY_SIZE = 2048;

    private static final int HMAC_KEY_SIZE = 256;

    private static final String STATUS = "status";

    /** A value no member of {@link #receipt()} holds. */
    private static final String TAMPERED = "tampered";

    private static final String SIGNING_KEY_VARIABLE = "RECEIPTS_SIGNING_KEY";

    private static final String RETIRED_KEYS_VARIABLE = "RECEIPTS_RETIRED_VERIFICATION_KEYS";

    private static final String RETIRED_KEY_ID = "receipts-2025";

    /** RFC 8037 Appendix A.1 Ed25519 private key: an OKP key this service never signs with. */
    private static final String ED25519_PRIVATE_KEY =
            "{\"kty\":\"OKP\",\"crv\":\"Ed25519\","
                    + "\"d\":\"nWGxne_9WmC6hEr0kuwsxERJxWl7MmkZcDusAxyuf2A\","
                    + "\"x\":\"11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo\"}";

    /** The public half of {@link #ED25519_PRIVATE_KEY}. */
    private static final String ED25519_PUBLIC_KEY =
            "{\"kty\":\"OKP\",\"crv\":\"Ed25519\","
                    + "\"x\":\"11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo\"}";

    /** A receipt whose members a verifier recomputes. */
    private static Map<String, Object> receipt() {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("consentId", "0199e1f0-0000-7000-8000-000000000001");
        receipt.put(STATUS, "GRANTED");
        receipt.put("consented", true);
        receipt.put("consentTimestamp", 1_767_225_600L);
        receipt.put("purposes", List.of("Clinical research", "Ünïcode ✓"));
        return receipt;
    }

    static Stream<Arguments> keys() throws JOSEException {
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
                        "RSA",
                        new RSAKeyGenerator(RSA_KEY_SIZE).keyID(KEY_ID).generate(),
                        JWSAlgorithm.RS256),
                Arguments.of(
                        "RSA naming PS256",
                        new RSAKeyGenerator(RSA_KEY_SIZE).algorithm(JWSAlgorithm.PS256).generate(),
                        JWSAlgorithm.PS256));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keys")
    @DisplayName("signs so the published key verifies the receipt and refuses a tampered one")
    void signsVerifiably(String name, JWK configured, JWSAlgorithm algorithm) throws Exception {
        ReceiptSigner signer = signer(configured);
        JWKSet published = new JWKSet(signer.publicKey());
        Map<String, Object> receipt = receipt();

        String jws = signer.sign(receipt);

        assertThat(signer.publicKey().isPrivate()).isFalse();
        assertThat(signer.publicKey().getKeyUse()).isEqualTo(KeyUse.SIGNATURE);
        assertThat(signer.publicKey().getAlgorithm()).isEqualTo(algorithm);
        if (configured != null && configured.getKeyID() != null) {
            assertThat(signer.publicKey().getKeyID()).isEqualTo(configured.getKeyID());
        }
        assertThat(jws).contains("..");
        assertThat(ReceiptSignatures.verifies(receipt, jws, published)).isTrue();

        receipt.put(STATUS, "REVOKED");
        assertThat(ReceiptSignatures.verifies(receipt, jws, published)).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"consentId", STATUS, "consented", "consentTimestamp", "purposes"})
    @DisplayName("signs the whole receipt, so altering any one member breaks verification")
    void coversEveryMember(String member) throws Exception {
        ReceiptSigner signer = signer(null);
        Map<String, Object> receipt = receipt();
        String jws = signer.sign(receipt);

        receipt.put(member, TAMPERED);

        assertThat(ReceiptSignatures.verifies(receipt, jws, new JWKSet(signer.publicKey())))
                .isFalse();
    }

    static Stream<Arguments> unusableKeys() throws JOSEException {
        return Stream.of(
                Arguments.of("absent", null),
                Arguments.of("blank", " "),
                Arguments.of("not a JWK", "{\"kty\":"),
                Arguments.of(
                        "public half only",
                        new ECKeyGenerator(Curve.P_256).generate().toPublicJWK().toJSONString()),
                Arguments.of(
                        "symmetric, naming its alg",
                        new OctetSequenceKeyGenerator(HMAC_KEY_SIZE)
                                .algorithm(JWSAlgorithm.HS256)
                                .generate()
                                .toJSONString()),
                Arguments.of("OKP Ed25519", ED25519_PRIVATE_KEY),
                Arguments.of(
                        "EC P-256 naming another curve's alg",
                        new ECKeyGenerator(Curve.P_256)
                                .algorithm(JWSAlgorithm.ES384)
                                .generate()
                                .toJSONString()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableKeys")
    @DisplayName("fails startup without a usable private key when no ephemeral key is allowed")
    void refusesAnUnusableKey(String name, String configured) {
        ConsentManagerConfiguration.Receipts settings = new ConsentManagerConfiguration.Receipts();
        settings.setSigningKey(configured);

        assertThatThrownBy(() -> new ReceiptSigner(settings, TestJson.mapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SIGNING_KEY_VARIABLE);
    }

    @Test
    @DisplayName("publishes retired keys after the active one, so a pre-rotation receipt verifies")
    void keepsReceiptsSignedBeforeARotationVerifiable() throws Exception {
        ReceiptSigner retired =
                signer(new ECKeyGenerator(Curve.P_256).keyID(RETIRED_KEY_ID).generate());
        Map<String, Object> receipt = receipt();
        String jws = retired.sign(receipt);

        ReceiptSigner rotated =
                signer(
                        new ECKeyGenerator(Curve.P_384).keyID(KEY_ID).generate(),
                        new JWKSet(retired.publicKey()).toString());

        assertThat(rotated.verificationKeys())
                .extracting(JWK::getKeyID)
                .containsExactly(KEY_ID, RETIRED_KEY_ID);
        assertThat(rotated.verificationKeys()).noneMatch(JWK::isPrivate);
        assertThat(ReceiptSignatures.verifies(receipt, jws, new JWKSet(rotated.verificationKeys())))
                .isTrue();
    }

    static Stream<Arguments> unusableRetiredKeys() throws JOSEException {
        return Stream.of(
                Arguments.of("not a JWKS", "{\"keys\":", RETIRED_KEYS_VARIABLE),
                Arguments.of(
                        "a private key",
                        new JWKSet(new ECKeyGenerator(Curve.P_256).generate()).toString(false),
                        RETIRED_KEYS_VARIABLE),
                Arguments.of(
                        "an OKP key",
                        "{\"keys\":[" + ED25519_PUBLIC_KEY + "]}",
                        RETIRED_KEYS_VARIABLE),
                Arguments.of(
                        "an EC key naming another curve's alg",
                        new JWKSet(
                                        new ECKeyGenerator(Curve.P_256)
                                                .algorithm(JWSAlgorithm.ES512)
                                                .generate()
                                                .toPublicJWK())
                                .toString(),
                        RETIRED_KEYS_VARIABLE),
                Arguments.of(
                        "the active key's kid",
                        new JWKSet(
                                        new ECKeyGenerator(Curve.P_256)
                                                .keyID(KEY_ID)
                                                .generate()
                                                .toPublicJWK())
                                .toString(),
                        KEY_ID));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableRetiredKeys")
    @DisplayName("fails startup on a retired key set it cannot publish safely")
    void refusesAnUnusableRetiredKey(String name, String retired, String named)
            throws JOSEException {
        JWK active = new ECKeyGenerator(Curve.P_256).keyID(KEY_ID).generate();

        assertThatThrownBy(() -> signer(active, retired))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(named);
    }

    private static ReceiptSigner signer(JWK configured) {
        return signer(configured, null);
    }

    private static ReceiptSigner signer(JWK configured, String retired) {
        ConsentManagerConfiguration.Receipts settings = new ConsentManagerConfiguration.Receipts();
        settings.setRetiredVerificationKeys(retired);
        if (configured == null) {
            settings.setEphemeralSigningKey(true);
        } else {
            settings.setSigningKey(configured.toJSONString());
        }
        return new ReceiptSigner(settings, TestJson.mapper());
    }
}
