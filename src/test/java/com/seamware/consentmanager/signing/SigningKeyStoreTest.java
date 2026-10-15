package com.seamware.consentmanager.signing;

import static com.seamware.consentmanager.signing.SigningFixtures.store;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.OctetSequenceKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jose.util.BigIntegerUtils;
import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins which keys fail startup, that the failure names its variable, and that it leaks no secret.
 */
@DisplayName("Receipt signing keys")
class SigningKeyStoreTest {

    private static final String KEY_ID = "receipts-2026";

    private static final String RETIRED_KEY_ID = "receipts-2025";

    private static final String SIGNING_KEY_VARIABLE = "RECEIPTS_SIGNING_KEY";

    private static final String RETIRED_KEYS_VARIABLE = "RECEIPTS_RETIRED_VERIFICATION_KEYS";

    private static final String SIGNING_KEY_PROPERTY = "consent-manager.receipts.signing-key";

    private static final String RETIRED_KEYS_PROPERTY =
            "consent-manager.receipts.retired-verification-keys";

    /** The JWK members (RFC 7518 Section 6) that hold private or symmetric key material. */
    private static final Set<String> PRIVATE_MEMBERS = Set.of("d", "p", "q", "dp", "dq", "qi", "k");

    private static final int HMAC_KEY_SIZE = 256;

    /** Below the 2048 bits Nimbus signs with, so the key parses but cannot sign. */
    private static final int WEAK_RSA_KEY_SIZE = 1024;

    private static final int RSA_KEY_SIZE = 2048;

    /** Hex radix of the secp256k1 generator coordinates below. */
    private static final int HEX = 16;

    /** Byte length of a secp256k1 coordinate or private scalar. */
    private static final int SECP256K1_FIELD_BYTES = 32;

    /** The secp256k1 generator point (SEC 2 Section 2.4.1): a valid key on an unsupported curve. */
    private static final String SECP256K1_X =
            "79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798";

    private static final String SECP256K1_Y =
            "483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8";

    /** RFC 8037 Appendix A.1 Ed25519 private key: an OKP key this service never signs with. */
    private static final String ED25519_PRIVATE_KEY =
            "{\"kty\":\"OKP\",\"crv\":\"Ed25519\","
                    + "\"d\":\"nWGxne_9WmC6hEr0kuwsxERJxWl7MmkZcDusAxyuf2A\","
                    + "\"x\":\"11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo\"}";

    /** The public half of {@link #ED25519_PRIVATE_KEY}. */
    private static final String ED25519_PUBLIC_KEY =
            "{\"kty\":\"OKP\",\"crv\":\"Ed25519\","
                    + "\"x\":\"11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo\"}";

    /** A startup configuration, the variable its failure must name and the secrets it holds. */
    private record Misconfiguration(
            String signingKey, String retiredKeys, String variable, List<JWK> secrets) {}

    static Stream<Arguments> misconfigurations() throws JOSEException, ParseException {
        ECKey active = new ECKeyGenerator(Curve.P_256).keyID(KEY_ID).generate();
        ECKey malformed = new ECKeyGenerator(Curve.P_256).generate();
        String truncated = malformed.toJSONString();
        JWK symmetric =
                new OctetSequenceKeyGenerator(HMAC_KEY_SIZE)
                        .algorithm(JWSAlgorithm.HS256)
                        .generate();
        ECKey mismatched = new ECKeyGenerator(Curve.P_256).algorithm(JWSAlgorithm.ES384).generate();
        JWK weak = new RSAKeyGenerator(WEAK_RSA_KEY_SIZE, true).generate();
        JWK secp256k1 = secp256k1();
        JWK ed25519 = OctetKeyPair.parse(ED25519_PRIVATE_KEY);
        ECKey privateRetired = new ECKeyGenerator(Curve.P_256).keyID(RETIRED_KEY_ID).generate();
        return Stream.of(
                signingKey("missing", null, List.of()),
                signingKey("blank", " ", List.of()),
                signingKey(
                        "malformed",
                        truncated.substring(0, truncated.length() - 1),
                        List.of(malformed)),
                signingKey(
                        "public half only",
                        new ECKeyGenerator(Curve.P_256).generate().toPublicJWK().toJSONString(),
                        List.of()),
                signingKey(
                        "unsupported curve secp256k1",
                        secp256k1.toJSONString(),
                        List.of(secp256k1)),
                signingKey("unsupported OKP Ed25519", ED25519_PRIVATE_KEY, List.of(ed25519)),
                signingKey(
                        "symmetric, naming its alg", symmetric.toJSONString(), List.of(symmetric)),
                signingKey(
                        "EC P-256 naming another curve's alg",
                        mismatched.toJSONString(),
                        List.of(mismatched)),
                signingKey("RSA under 2048 bits", weak.toJSONString(), List.of(weak)),
                retiredKeys(active, "retired: not a JWKS", "{\"keys\":", List.of()),
                retiredKeys(
                        active,
                        "retired: a private key",
                        new JWKSet(privateRetired).toString(false),
                        List.of(privateRetired)),
                retiredKeys(
                        active,
                        "retired: an OKP key",
                        "{\"keys\":[" + ED25519_PUBLIC_KEY + "]}",
                        List.of()),
                retiredKeys(
                        active,
                        "retired: an EC key naming another curve's alg",
                        new JWKSet(
                                        new ECKeyGenerator(Curve.P_256)
                                                .algorithm(JWSAlgorithm.ES512)
                                                .generate()
                                                .toPublicJWK())
                                .toString(),
                        List.of()),
                retiredKeys(
                        active,
                        "retired: duplicates the active kid",
                        new JWKSet(active.toPublicJWK()).toString(),
                        List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("misconfigurations")
    @DisplayName("fails startup naming the variable at fault, quoting no key material")
    void refusesAMisconfiguration(String name, Misconfiguration configured) {
        ConsentManagerConfiguration.Receipts settings = new ConsentManagerConfiguration.Receipts();
        settings.setSigningKey(configured.signingKey());
        settings.setRetiredVerificationKeys(configured.retiredKeys());

        Throwable failure = catchThrowable(() -> new SigningKeyStore(settings));

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(configured.variable());
        assertQuotesNoSecret(failure, configured.secrets());
    }

    static Stream<Arguments> contextMisconfigurations() throws JOSEException, ParseException {
        return misconfigurations()
                .filter(
                        row -> {
                            Misconfiguration configured = (Misconfiguration) row.get()[1];
                            return configured.retiredKeys() == null
                                    && !configured.secrets().isEmpty();
                        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contextMisconfigurations")
    @DisplayName("fails application startup the same way, quoting no key material")
    void failsApplicationStartup(String name, Misconfiguration configured) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("datasources.default.enabled", false);
        properties.put("flyway.enabled", false);
        properties.put(SIGNING_KEY_PROPERTY, configured.signingKey());
        if (configured.retiredKeys() != null) {
            properties.put(RETIRED_KEYS_PROPERTY, configured.retiredKeys());
        }

        Throwable failure =
                catchThrowable(
                        () -> {
                            try (ApplicationContext ignored =
                                    ApplicationContext.run(properties, Environment.TEST)) {
                                // Startup alone must fail.
                            }
                        });

        assertThat(failure).isNotNull();
        assertThat(causes(failure))
                .anySatisfy(
                        cause ->
                                assertThat(cause)
                                        .isInstanceOf(IllegalStateException.class)
                                        .hasMessageContaining(configured.variable()));
        assertQuotesNoSecret(failure, configured.secrets());
    }

    @Test
    @DisplayName("publishes the active key first, then the retired ones, all public")
    void publishesActiveThenRetiredKeys() throws JOSEException {
        JWK retired = new RSAKeyGenerator(RSA_KEY_SIZE).keyID(RETIRED_KEY_ID).generate();
        SigningKeyStore keys =
                store(
                        new ECKeyGenerator(Curve.P_384).keyID(KEY_ID).generate(),
                        new JWKSet(retired.toPublicJWK()).toString());

        assertThat(keys.verificationKeys())
                .extracting(JWK::getKeyID)
                .containsExactly(KEY_ID, RETIRED_KEY_ID);
        assertThat(keys.verificationKeys()).noneMatch(JWK::isPrivate);
        assertThat(keys.verificationKey(RETIRED_KEY_ID)).contains(keys.verificationKeys().get(1));
        assertThat(keys.verificationKey("unknown")).isEmpty();
        assertThat(keys.verificationKey(null)).isEmpty();
    }

    private static Arguments signingKey(String name, String signingKey, List<JWK> secrets) {
        return Arguments.of(
                name, new Misconfiguration(signingKey, null, SIGNING_KEY_VARIABLE, secrets));
    }

    private static Arguments retiredKeys(
            JWK active, String name, String retiredKeys, List<JWK> secrets) {
        List<JWK> held = new ArrayList<>(secrets);
        held.add(active);
        return Arguments.of(
                name,
                new Misconfiguration(
                        active.toJSONString(), retiredKeys, RETIRED_KEYS_VARIABLE, held));
    }

    /** Nimbus does not check {@code d} against the public point, so any scalar is a secret. */
    private static JWK secp256k1() {
        byte[] scalar = new byte[SECP256K1_FIELD_BYTES];
        new SecureRandom().nextBytes(scalar);
        return new ECKey.Builder(Curve.SECP256K1, coordinate(SECP256K1_X), coordinate(SECP256K1_Y))
                .d(Base64URL.encode(scalar))
                .build();
    }

    private static Base64URL coordinate(String hex) {
        return Base64URL.encode(BigIntegerUtils.toBytesUnsigned(new BigInteger(hex, HEX)));
    }

    /** Asserts that neither the messages along the cause chain nor the stack trace quote one. */
    private static void assertQuotesNoSecret(Throwable failure, List<JWK> keys) {
        List<String> secrets =
                keys.stream()
                        .flatMap(key -> key.toJSONObject().entrySet().stream())
                        .filter(member -> PRIVATE_MEMBERS.contains(member.getKey()))
                        .map(member -> member.getValue().toString())
                        .toList();
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        List<String> said =
                new ArrayList<>(
                        causes(failure).stream()
                                .map(Throwable::getMessage)
                                .filter(Objects::nonNull)
                                .toList());
        said.add(trace.toString());

        for (String secret : secrets) {
            assertThat(said).noneMatch(text -> text.contains(secret));
        }
    }

    private static List<Throwable> causes(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            chain.add(cause);
        }
        return chain;
    }
}
