package com.seamware.consentmanager.signing;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.Base64URL;
import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.support.TestJson;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Builds the signing beans by hand, configured as the receipt settings would bind them. */
public final class SigningFixtures {

    /** Every algorithm a configured receipt signing key may name. */
    public static final List<JWSAlgorithm> RECEIPT_ALGORITHMS =
            List.of(
                    JWSAlgorithm.ES256,
                    JWSAlgorithm.ES384,
                    JWSAlgorithm.ES512,
                    JWSAlgorithm.RS256,
                    JWSAlgorithm.PS256);

    /**
     * For each algorithm, another that a lax verifier might try with the same key: the next curve
     * for EC, the other padding for RSA.
     */
    private static final Map<JWSAlgorithm, JWSAlgorithm> SIBLING_ALGORITHM =
            Map.of(
                    JWSAlgorithm.ES256, JWSAlgorithm.ES384,
                    JWSAlgorithm.ES384, JWSAlgorithm.ES512,
                    JWSAlgorithm.ES512, JWSAlgorithm.ES256,
                    JWSAlgorithm.RS256, JWSAlgorithm.PS256,
                    JWSAlgorithm.PS256, JWSAlgorithm.RS256);

    private static final int RSA_KEY_SIZE = 3072;

    /** A value no signed receipt member or key id holds. */
    private static final String TAMPERED = "tampered";

    /** Written over the last character of a string member, unless it already is that. */
    private static final char TAMPERED_CHAR = 'x';

    private static final char ALTERNATE_CHAR = 'y';

    /** Separates the parts of a compact JWS. */
    private static final String DOT = ".";

    private static final int HEADER_PART = 0;

    private static final int SIGNATURE_PART = 2;

    /** Bit flipped in the first signature byte. */
    private static final int FLIPPED_BIT = 0x01;

    private SigningFixtures() {}

    /** A store over {@code configured}, or over an ephemeral key when it is null. */
    public static SigningKeyStore store(JWK configured, String retired) {
        ConsentManagerConfiguration.Receipts settings = new ConsentManagerConfiguration.Receipts();
        settings.setRetiredVerificationKeys(retired);
        if (configured == null) {
            settings.setEphemeralSigningKey(true);
        } else {
            settings.setSigningKey(configured.toJSONString());
        }
        return new SigningKeyStore(settings);
    }

    public static SigningKeyStore store(JWK configured) {
        return store(configured, null);
    }

    public static CanonicalJsonSerializer canonical() {
        return new CanonicalJsonSerializer(TestJson.mapper());
    }

    public static JwsConsentSigningService signing(SigningKeyStore keys) {
        return new JwsConsentSigningService(keys, canonical());
    }

    /** A fresh private key under {@code keyId} that a store signs with {@code algorithm}. */
    public static JWK key(JWSAlgorithm algorithm, String keyId) throws JOSEException {
        if (JWSAlgorithm.Family.EC.contains(algorithm)) {
            return new ECKeyGenerator(Curve.forJWSAlgorithm(algorithm).iterator().next())
                    .keyID(keyId)
                    .generate();
        }
        return new RSAKeyGenerator(RSA_KEY_SIZE).algorithm(algorithm).keyID(keyId).generate();
    }

    /**
     * Every way a signed receipt or its detached JWS is altered after signing; a verifier must
     * refuse each. Receipt tampers return a copy; JWS tampers read the alg and kids from the store.
     */
    public enum Tamper {
        STRING_CHANGED(
                "one character of a string member changed",
                SigningFixtures::stringChanged,
                SigningFixtures::untouched),
        SCALAR_CHANGED(
                "a number changed, else a boolean negated",
                SigningFixtures::scalarChanged,
                SigningFixtures::untouched),
        MEMBER_ADDED("a member added", SigningFixtures::memberAdded, SigningFixtures::untouched),
        MEMBER_REMOVED(
                "a member removed", SigningFixtures::memberRemoved, SigningFixtures::untouched),
        SIGNATURE_FLIPPED(
                "a byte of the signature flipped",
                UnaryOperator.identity(),
                (jws, receipt, keys) -> flipped(jws)),
        KID_SWAPPED(
                "the kid swapped for another published key's",
                UnaryOperator.identity(),
                (jws, receipt, keys) -> withHeader(jws, keys.algorithm(), retiredKeyId(keys))),
        KID_UNPUBLISHED(
                "a kid no key is published under",
                UnaryOperator.identity(),
                (jws, receipt, keys) -> withHeader(jws, keys.algorithm(), TAMPERED)),
        ALG_UNPUBLISHED(
                "an alg the named key does not publish",
                UnaryOperator.identity(),
                (jws, receipt, keys) ->
                        withHeader(jws, SIBLING_ALGORITHM.get(keys.algorithm()), keys.keyId())),
        PAYLOAD_ATTACHED(
                "an attached payload",
                UnaryOperator.identity(),
                (jws, receipt, keys) -> attached(jws, receipt)),
        NOT_A_JWS("not a JWS", UnaryOperator.identity(), (jws, receipt, keys) -> TAMPERED);

        /** Alters a detached JWS, given the receipt it signs and the store that signed it. */
        @FunctionalInterface
        private interface JwsTamper {
            String apply(String jws, Map<String, Object> receipt, SigningKeyStore keys);
        }

        private final String description;

        private final UnaryOperator<Map<String, Object>> receiptTamper;

        private final JwsTamper jwsTamper;

        Tamper(
                String description,
                UnaryOperator<Map<String, Object>> receiptTamper,
                JwsTamper jwsTamper) {
            this.description = description;
            this.receiptTamper = receiptTamper;
            this.jwsTamper = jwsTamper;
        }

        /** The receipt presented in place of {@code signed}, which is left untouched. */
        public Map<String, Object> receipt(Map<String, Object> signed) {
            return receiptTamper.apply(new LinkedHashMap<>(signed));
        }

        /**
         * The JWS presented in place of {@code jws}. {@link #KID_SWAPPED} needs {@code keys} to
         * publish a retired key.
         */
        public String jws(String jws, Map<String, Object> receipt, SigningKeyStore keys) {
            return jwsTamper.apply(jws, receipt, keys);
        }

        @Override
        public String toString() {
            return description;
        }
    }

    private static String untouched(String jws, Map<String, Object> receipt, SigningKeyStore keys) {
        return jws;
    }

    private static Map<String, Object> stringChanged(Map<String, Object> receipt) {
        for (Map.Entry<String, Object> member : receipt.entrySet()) {
            if (member.getValue() instanceof String value && !value.isEmpty()) {
                char last = value.charAt(value.length() - 1);
                member.setValue(
                        value.substring(0, value.length() - 1)
                                + (last == TAMPERED_CHAR ? ALTERNATE_CHAR : TAMPERED_CHAR));
                return receipt;
            }
        }
        throw new IllegalStateException("The receipt holds no non-empty string member.");
    }

    private static Map<String, Object> scalarChanged(Map<String, Object> receipt) {
        for (Map.Entry<String, Object> member : receipt.entrySet()) {
            if (member.getValue() instanceof Number number) {
                member.setValue(number.longValue() + 1);
                return receipt;
            }
        }
        for (Map.Entry<String, Object> member : receipt.entrySet()) {
            if (member.getValue() instanceof Boolean flag) {
                member.setValue(!flag);
                return receipt;
            }
        }
        throw new IllegalStateException("The receipt holds no number or boolean member.");
    }

    private static Map<String, Object> memberAdded(Map<String, Object> receipt) {
        receipt.put(TAMPERED, TAMPERED);
        return receipt;
    }

    private static Map<String, Object> memberRemoved(Map<String, Object> receipt) {
        receipt.remove(List.copyOf(receipt.keySet()).getLast());
        return receipt;
    }

    private static String retiredKeyId(SigningKeyStore keys) {
        return keys.verificationKeys().stream()
                .map(JWK::getKeyID)
                .filter(keyId -> !keyId.equals(keys.keyId()))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalStateException("The store publishes no retired key."));
    }

    private static String withHeader(String jws, JWSAlgorithm algorithm, String keyId) {
        JWSHeader header =
                new JWSHeader.Builder(algorithm).type(JOSEObjectType.JOSE).keyID(keyId).build();
        return header.toBase64URL() + DOT + DOT + parts(jws)[SIGNATURE_PART];
    }

    private static String flipped(String jws) {
        String[] parts = parts(jws);
        byte[] signature = Base64URL.from(parts[SIGNATURE_PART]).decode();
        signature[0] ^= FLIPPED_BIT;
        return parts[HEADER_PART] + DOT + DOT + Base64URL.encode(signature);
    }

    private static String attached(String jws, Map<String, Object> receipt) {
        String[] parts = parts(jws);
        Base64URL payload =
                Base64URL.encode(canonical().toJson(receipt).getBytes(StandardCharsets.UTF_8));
        return parts[HEADER_PART] + DOT + payload + DOT + parts[SIGNATURE_PART];
    }

    private static String[] parts(String jws) {
        return jws.split("\\" + DOT, -1);
    }
}
