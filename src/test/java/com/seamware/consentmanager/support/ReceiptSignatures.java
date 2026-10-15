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
import java.util.HashMap;
import java.util.Map;
import org.erdtman.jcs.JsonCanonicalizer;

/**
 * Verifies a receipt's detached JWS the way an outside verifier would: from the served JSON and the
 * published JWKS alone, recomputing the RFC 8785 payload independently of the signer.
 */
public final class ReceiptSignatures {

    private static final String SIGNATURE = "signature";

    private ReceiptSignatures() {}

    /**
     * Whether {@code receiptJson}'s {@code signature} verifies against the key its {@code kid}
     * names.
     */
    public static boolean verifies(String receiptJson, JWKSet published)
            throws IOException, ParseException, JOSEException {
        Map<String, Object> receipt =
                new HashMap<>(
                        TestJson.mapper()
                                .readValue(
                                        receiptJson, Argument.mapOf(String.class, Object.class)));
        String signature = (String) receipt.remove(SIGNATURE);
        byte[] payload =
                new JsonCanonicalizer(TestJson.mapper().writeValueAsBytes(receipt))
                        .getEncodedUTF8();
        JWSObject jws = JWSObject.parse(signature, new Payload(payload));
        JWK key = published.getKeyByKeyId(jws.getHeader().getKeyID());
        if (key == null) {
            return false;
        }
        return jws.verify(
                new DefaultJWSVerifierFactory()
                        .createJWSVerifier(jws.getHeader(), ((AsymmetricJWK) key).toPublicKey()));
    }
}
