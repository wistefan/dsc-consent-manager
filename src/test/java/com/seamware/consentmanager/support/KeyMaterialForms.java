package com.seamware.consentmanager.support;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.Base64;
import com.nimbusds.jose.util.Base64URL;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/** Every textual form a private key member could leak in, for leak tests to search for. */
public final class KeyMaterialForms {

    private static final int HEX = 16;

    private KeyMaterialForms() {}

    /** Each private member of the RSA key in every {@link #of(Base64URL)} form. */
    public static Set<String> of(RSAKey key) {
        Set<String> secrets = new HashSet<>();
        Stream.of(
                        key.getPrivateExponent(),
                        key.getFirstPrimeFactor(),
                        key.getSecondPrimeFactor(),
                        key.getFirstFactorCRTExponent(),
                        key.getSecondFactorCRTExponent(),
                        key.getFirstCRTCoefficient())
                .forEach(member -> secrets.addAll(of(member)));
        return secrets;
    }

    /** The member as base64url, base64, and the decimal and hex a key's toString may use. */
    public static Set<String> of(Base64URL member) {
        BigInteger value = member.decodeToBigInteger();
        return Set.of(
                member.toString(),
                Base64.encode(member.decode()).toString(),
                value.toString(),
                value.toString(HEX));
    }
}
