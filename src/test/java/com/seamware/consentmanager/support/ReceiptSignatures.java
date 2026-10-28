package com.seamware.consentmanager.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.factories.DefaultJWSVerifierFactory;
import com.nimbusds.jose.jwk.AsymmetricJWK;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import io.micronaut.core.type.Argument;
import java.io.IOException;
import java.text.ParseException;
import java.util.Map;
import org.erdtman.jcs.JsonCanonicalizer;

/**
 * Verifies a receipt's detached JWS the way an outside verifier would: from the served JSON and the
 * published JWKS alone, recomputing the RFC 8785 payload independently of the signer.
 */
public final class ReceiptSignatures {

    private static final String RECEIPT = "receipt";

    private static final String JWS = "jws";

    private ReceiptSignatures() {}

    /** Whether a served {@code {receipt, jws}} body's {@code jws} verifies over its receipt. */
    public static boolean verifies(String signedReceiptJson, JWKSet published)
            throws IOException, ParseException, JOSEException {
        Map<String, Object> body =
                TestJson.mapper()
                        .readValue(signedReceiptJson, Argument.mapOf(String.class, Object.class));
        return verifies(body.get(RECEIPT), (String) body.get(JWS), published);
    }

    /**
     * Whether {@code signature} verifies over {@code receipt} with the key its kid names, which
     * must publish the header's alg.
     */
    public static boolean verifies(Object receipt, String signature, JWKSet published)
            throws IOException, ParseException, JOSEException {
        JWSObject jws = parse(receipt, signature);
        JWK key = published.getKeyByKeyId(jws.getHeader().getKeyID());
        if (key == null || !jws.getHeader().getAlgorithm().equals(key.getAlgorithm())) {
            return false;
        }
        return signedBy(jws, key);
    }

    /**
     * Whether {@code signature} verifies over {@code receipt} with {@code key} under the header's
     * alg, whatever kid it names: all a lax verifier would check.
     */
    public static boolean signedBy(Object receipt, String signature, JWK key)
            throws IOException, ParseException, JOSEException {
        return signedBy(parse(receipt, signature), key);
    }

    private static JWSObject parse(Object receipt, String signature)
            throws IOException, ParseException {
        byte[] payload =
                new JsonCanonicalizer(TestJson.mapper().writeValueAsBytes(receipt))
                        .getEncodedUTF8();
        return JWSObject.parse(signature, new Payload(payload));
    }

    private static boolean signedBy(JWSObject jws, JWK key) throws JOSEException {
        return jws.verify(
                new DefaultJWSVerifierFactory()
                        .createJWSVerifier(jws.getHeader(), ((AsymmetricJWK) key).toPublicKey()));
    }
}
