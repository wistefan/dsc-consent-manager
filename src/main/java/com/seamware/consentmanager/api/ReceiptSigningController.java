package com.seamware.consentmanager.api;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.seamware.consentmanager.api.generated.AbstractReceiptSigningController;
import com.seamware.consentmanager.api.generated.model.JsonWebKey;
import com.seamware.consentmanager.api.generated.model.JsonWebKeySet;
import com.seamware.consentmanager.signing.SigningKeyStore;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;

/**
 * Publishes the JWKS a consent receipt's {@code jws} verifies against; public by spec, and served
 * at the origin root where RFC 8615 places {@code /.well-known/}.
 */
@Controller
@OriginRoot
public class ReceiptSigningController extends AbstractReceiptSigningController {

    /** The set changes only on a restart, so verifiers may hold it for an hour. */
    static final String JWKS_CACHE_CONTROL = "public, max-age=3600";

    private final JsonWebKeySet published;

    public ReceiptSigningController(SigningKeyStore keys) {
        this.published =
                new JsonWebKeySet(
                        keys.verificationKeys().stream()
                                .map(ReceiptSigningController::toJsonWebKey)
                                .toList());
    }

    @Override
    public HttpResponse<JsonWebKeySet> getReceiptSigningKeys() {
        return HttpResponse.ok(published).header(HttpHeaders.CACHE_CONTROL, JWKS_CACHE_CONTROL);
    }

    private static JsonWebKey toJsonWebKey(JWK key) {
        JsonWebKey jwk =
                new JsonWebKey(
                        JsonWebKey.KtyEnum.fromValue(key.getKeyType().getValue()),
                        JsonWebKey.UseEnum.fromValue(key.getKeyUse().identifier()),
                        key.getKeyID(),
                        key.getAlgorithm().getName());
        return switch (key) {
            case ECKey ec ->
                    jwk.crv(ec.getCurve().getName())
                            .x(ec.getX().toString())
                            .y(ec.getY().toString());
            case RSAKey rsa ->
                    jwk.n(rsa.getModulus().toString()).e(rsa.getPublicExponent().toString());
            default ->
                    throw new IllegalStateException(
                            "No JWKS projection for key type " + key.getKeyType());
        };
    }
}
