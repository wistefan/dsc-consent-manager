package com.seamware.consentmanager.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Mutates a compact JWS the way an attacker would, leaving every untouched segment byte-identical,
 * so a refusal is attributable to the one change a case makes.
 */
public final class TokenTampering {

    private static final String SEPARATOR = ".";

    private static final String SEPARATOR_PATTERN = "\\.";

    /** A compact JWS has a header, a payload and a signature segment. */
    private static final int SEGMENT_COUNT = 3;

    private static final int HEADER = 0;

    private static final int PAYLOAD = 1;

    private static final int SIGNATURE = 2;

    /** Bit flipped in the signature, inside its first byte so no length changes. */
    private static final int FLIPPED_BIT = 0x01;

    /** JOSE header parameter naming the algorithm. */
    public static final String ALG = "alg";

    /** JOSE header parameter naming the signing key. */
    public static final String KID = "kid";

    /** The algorithm value of an unsecured JWS (RFC 7515 appendix A.5). */
    private static final String ALG_NONE = "none";

    /** Replaces the JOSE header, keeping the original payload and signature bytes. */
    public static String withHeader(String token, Consumer<Map<String, Object>> edit) {
        String[] segments = split(token);
        Map<String, Object> header = new LinkedHashMap<>(decode(segments[HEADER]));
        edit.accept(header);
        return join(encode(header), segments[PAYLOAD], segments[SIGNATURE]);
    }

    /** Replaces the claim set, keeping the original header and signature bytes. */
    public static String withClaims(String token, Consumer<Map<String, Object>> edit) {
        String[] segments = split(token);
        Map<String, Object> claims = new LinkedHashMap<>(decode(segments[PAYLOAD]));
        edit.accept(claims);
        return join(segments[HEADER], encode(claims), segments[SIGNATURE]);
    }

    /** Flips one bit of the signature, keeping header and payload. */
    public static String withFlippedSignatureBit(String token) {
        String[] segments = split(token);
        byte[] signature = new Base64URL(segments[SIGNATURE]).decode();
        signature[0] ^= FLIPPED_BIT;
        return join(segments[HEADER], segments[PAYLOAD], Base64URL.encode(signature).toString());
    }

    /** The same claims as an unsecured JWS: {@code alg: none} and an empty signature. */
    public static String unsecured(String token) {
        String[] segments = split(token);
        Map<String, Object> header = new LinkedHashMap<>(decode(segments[HEADER]));
        header.put(ALG, ALG_NONE);
        return join(encode(header), segments[PAYLOAD], "");
    }

    /**
     * Algorithm confusion: the same claims and {@code kid}, re-signed {@code HS256} keyed by bytes
     * the attacker can read, which verifies only if the header may choose the algorithm.
     */
    public static String hmacSigned(String token, byte[] secret) {
        SignedJWT original = parse(token);
        return sign(
                new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(original.getHeader().getKeyID()),
                original,
                () -> new MACSigner(secret));
    }

    /** The same claims re-signed {@code RS256} by {@code key} under an attacker-chosen header. */
    public static String resigned(String token, RSAKey key, JWSHeader.Builder header) {
        return sign(header, parse(token), () -> new RSASSASigner(key));
    }

    private static String sign(JWSHeader.Builder header, SignedJWT original, SignerSource signer) {
        try {
            SignedJWT forged = new SignedJWT(header.build(), original.getJWTClaimsSet());
            forged.sign(signer.get());
            return forged.serialize();
        } catch (JOSEException | ParseException e) {
            throw new IllegalStateException("Could not re-sign a tampered token", e);
        }
    }

    private static SignedJWT parse(String token) {
        try {
            return SignedJWT.parse(token);
        } catch (ParseException e) {
            throw new IllegalArgumentException("Not a parseable signed JWT", e);
        }
    }

    private static String[] split(String token) {
        String[] segments = token.split(SEPARATOR_PATTERN, -1);
        if (segments.length != SEGMENT_COUNT) {
            throw new IllegalArgumentException("Not a compact JWS");
        }
        return segments;
    }

    private static Map<String, Object> decode(String segment) {
        try {
            return JSONObjectUtils.parse(new Base64URL(segment).decodeToString());
        } catch (ParseException e) {
            throw new IllegalArgumentException("A JWS segment is not a JSON object", e);
        }
    }

    private static String encode(Map<String, Object> json) {
        return Base64URL.encode(JSONObjectUtils.toJSONString(json)).toString();
    }

    private static String join(String header, String payload, String signature) {
        return String.join(SEPARATOR, header, payload, signature);
    }

    /** A signer factory that may throw, as every Nimbus signer constructor does. */
    @FunctionalInterface
    private interface SignerSource {
        JWSSigner get() throws JOSEException;
    }

    private TokenTampering() {}
}
