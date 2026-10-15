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
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Pins that a signed receipt verifies against the published key and nothing else does. */
@DisplayName("Receipt signing")
class ReceiptSignerTest {

    private static final String KEY_ID = "receipts-2026";

    private static final int RSA_KEY_SIZE = 2048;

    private static final int HMAC_KEY_SIZE = 256;

    private static final String STATUS = "status";

    /** A receipt whose members a verifier recomputes; it arrives carrying a stale signature. */
    private static Map<String, Object> receipt() {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("consentId", "0199e1f0-0000-7000-8000-000000000001");
        receipt.put(STATUS, "GRANTED");
        receipt.put("consented", true);
        receipt.put("consentTimestamp", 1_767_225_600L);
        receipt.put("purposes", List.of("Clinical research", "Ünïcode ✓"));
        receipt.put(ReceiptSigner.SIGNATURE_MEMBER, "stale");
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

        receipt.put(ReceiptSigner.SIGNATURE_MEMBER, signer.sign(receipt));

        assertThat(signer.publicKey().isPrivate()).isFalse();
        assertThat(signer.publicKey().getKeyUse()).isEqualTo(KeyUse.SIGNATURE);
        assertThat(signer.publicKey().getAlgorithm()).isEqualTo(algorithm);
        if (configured != null && configured.getKeyID() != null) {
            assertThat(signer.publicKey().getKeyID()).isEqualTo(configured.getKeyID());
        }
        assertThat((String) receipt.get(ReceiptSigner.SIGNATURE_MEMBER)).contains("..");
        assertThat(ReceiptSignatures.verifies(json(receipt), published)).isTrue();

        receipt.put(STATUS, "REVOKED");
        assertThat(ReceiptSignatures.verifies(json(receipt), published)).isFalse();
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
                .hasMessageContaining("RECEIPTS_SIGNING_KEY");
    }

    private static ReceiptSigner signer(JWK configured) {
        ConsentManagerConfiguration.Receipts settings = new ConsentManagerConfiguration.Receipts();
        if (configured == null) {
            settings.setEphemeralSigningKey(true);
        } else {
            settings.setSigningKey(configured.toJSONString());
        }
        return new ReceiptSigner(settings, TestJson.mapper());
    }

    private static String json(Map<String, Object> receipt) throws IOException {
        return TestJson.mapper().writeValueAsString(receipt);
    }
}
