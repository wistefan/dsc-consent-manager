package com.seamware.consentmanager.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Stand-in OIDC provider serving a switchable discovery document and a rotatable JWK Set. Listens
 * on a dynamic port, so build it first and feed {@link #issuer()} into the context.
 */
public final class OidcDiscoveryStub implements AutoCloseable {

    /** Realm path prefix, mirroring Keycloak's URL layout. */
    private static final String REALM_PATH = "/realms/test";

    /** Metadata path required by OpenID Connect Discovery 1.0. */
    private static final String DISCOVERY_PATH = REALM_PATH + "/.well-known/openid-configuration";

    /** JWK Set path the stubbed metadata advertises. */
    private static final String JWKS_PATH = REALM_PATH + "/protocol/openid-connect/certs";

    private static final String JSON = "application/json";

    /** RSA modulus length, matching what Keycloak issues. */
    private static final int KEY_SIZE_BITS = 2048;

    private static final String INITIAL_KEY_ID = "initial-key";

    /** Sentinel for {@link #jwksFailureStatus}: serve the key set normally. */
    private static final int JWKS_SERVED_NORMALLY = 0;

    private static final String INITIAL_EC_KEY_ID = "initial-ec-key";

    /** The curve {@code ES256} is defined over. */
    private static final Curve EC_CURVE = Curve.P_256;

    private static final JWSAlgorithm RSA_ALGORITHM = JWSAlgorithm.RS256;

    private static final JWSAlgorithm EC_ALGORITHM = JWSAlgorithm.ES256;

    private final WireMockServer server;

    /** Currently published key set; replaced wholesale on rotation. */
    private volatile JWKSet jwkSet;

    /** HTTP status JWK Set requests fail with, or {@link #JWKS_SERVED_NORMALLY}. */
    private volatile int jwksFailureStatus = JWKS_SERVED_NORMALLY;

    /** Starts on a free port serving matching metadata and the initial key set. */
    public OidcDiscoveryStub() {
        this.server = new WireMockServer(options().dynamicPort());
        this.server.start();
        this.jwkSet = initialKeySet();
        serveMatchingMetadata();
    }

    /** {@code kid} of the RSA key published until a test rotates it away. */
    public String initialKeyId() {
        return INITIAL_KEY_ID;
    }

    /** {@code kid} of the EC key published beside the RSA one, for the {@code ES256} path. */
    public String initialEcKeyId() {
        return INITIAL_EC_KEY_ID;
    }

    /** Replaces the published key set with fresh keys; several ids model an overlap window. */
    public void rotateKeysTo(String... keyIds) {
        this.jwkSet = generateKeySet(keyIds);
        stubJwks();
    }

    /**
     * Makes JWK Set requests fail with the given status, simulating a key-endpoint outage. Survives
     * {@link #rotateKeysTo(String...)} until {@link #serveJwksNormally()}.
     */
    public void failJwksRequests(int statusCode) {
        this.jwksFailureStatus = statusCode;
        stubJwks();
    }

    /** Serves the current key set again after {@link #failJwksRequests(int)}. */
    public void serveJwksNormally() {
        this.jwksFailureStatus = JWKS_SERVED_NORMALLY;
        stubJwks();
    }

    /**
     * Signs a token with a published key, setting {@code kid} to that key.
     *
     * @throws IllegalArgumentException if this stub publishes no key under {@code keyId}
     */
    public SignedJWT signToken(String keyId, JWTClaimsSet claims) {
        JWK key = jwkSet.getKeyByKeyId(keyId);
        if (key == null) {
            throw new IllegalArgumentException(
                    "This stub publishes no key with kid '" + keyId + "'");
        }
        SignedJWT token =
                new SignedJWT(new JWSHeader.Builder(algorithmOf(key)).keyID(keyId).build(), claims);
        try {
            token.sign(signerFor(key));
        } catch (JOSEException failure) {
            throw new IllegalStateException("Could not sign a test token", failure);
        }
        return token;
    }

    /** Number of JWK Set requests so far; the only outside view of JWK Set caching. */
    public int jwksRequestCount() {
        return server.countRequestsMatching(getRequestedFor(urlEqualTo(JWKS_PATH)).build())
                .getCount();
    }

    /** {@code ES256} for an elliptic-curve key, {@code RS256} otherwise. */
    private static JWSAlgorithm algorithmOf(JWK key) {
        return key instanceof ECKey ? EC_ALGORITHM : RSA_ALGORITHM;
    }

    private static JWSSigner signerFor(JWK key) throws JOSEException {
        return key instanceof ECKey ecKey
                ? new ECDSASigner(ecKey)
                : new RSASSASigner(key.toRSAKey());
    }

    /** Initial key set: the RSA key first, as providers normally sign with it, then the EC key. */
    private static JWKSet initialKeySet() {
        List<JWK> keys = new ArrayList<>(generateKeySet(INITIAL_KEY_ID).getKeys());
        try {
            keys.add(
                    new ECKeyGenerator(EC_CURVE)
                            .keyID(INITIAL_EC_KEY_ID)
                            .keyUse(KeyUse.SIGNATURE)
                            .algorithm(EC_ALGORITHM)
                            .generate());
        } catch (JOSEException failure) {
            throw new IllegalStateException("Could not generate a test EC signing key", failure);
        }
        return new JWKSet(keys);
    }

    /** Generates an RSA signing key per identifier, private halves included. */
    private static JWKSet generateKeySet(String... keyIds) {
        List<JWK> keys =
                Arrays.stream(keyIds)
                        .<JWK>map(
                                keyId -> {
                                    try {
                                        return new RSAKeyGenerator(KEY_SIZE_BITS)
                                                .keyID(keyId)
                                                .keyUse(KeyUse.SIGNATURE)
                                                .generate();
                                    } catch (JOSEException failure) {
                                        throw new IllegalStateException(
                                                "Could not generate a test signing key", failure);
                                    }
                                })
                        .toList();
        return new JWKSet(keys);
    }

    /** Serves the current key set's public halves, or the configured failure. */
    private void stubJwks() {
        if (jwksFailureStatus != JWKS_SERVED_NORMALLY) {
            server.stubFor(
                    get(urlEqualTo(JWKS_PATH))
                            .willReturn(aResponse().withStatus(jwksFailureStatus)));
            return;
        }
        server.stubFor(
                get(urlEqualTo(JWKS_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", JSON)
                                        .withBody(jwkSet.toString())));
    }

    /** Clears discovery mappings and reinstates the JWK Set one, which WireMock's reset drops. */
    private void resetDiscoveryMappings() {
        server.resetMappings();
        stubJwks();
    }

    /** Issuer this stub claims and tests should configure. */
    public String issuer() {
        return server.baseUrl() + REALM_PATH;
    }

    /** Absolute URL of the stubbed discovery document. */
    public String discoveryUrl() {
        return server.baseUrl() + DISCOVERY_PATH;
    }

    /** Absolute JWK Set URL the stubbed metadata advertises. */
    public String jwksUri() {
        return server.baseUrl() + JWKS_PATH;
    }

    /** Serves a well-formed document whose {@code issuer} matches {@link #issuer()}. */
    public void serveMatchingMetadata() {
        serveMetadataDeclaring(issuer());
    }

    /**
     * Serves a well-formed document declaring another issuer: the misconfiguration case, which the
     * registry must reject permanently rather than retry.
     */
    public void serveMetadataDeclaring(String declaredIssuer) {
        resetDiscoveryMappings();
        server.stubFor(
                get(urlEqualTo(DISCOVERY_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", JSON)
                                        .withBody(metadataDocument(declaredIssuer))));
    }

    /**
     * Serves a 200 with an empty body, as a proxy mid-reload does. Identity is unconfirmed rather
     * than contradicted, so the registry must retry.
     */
    public void serveEmptyBody() {
        resetDiscoveryMappings();
        server.stubFor(
                get(urlEqualTo(DISCOVERY_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", JSON)
                                        .withBody("")));
    }

    /**
     * Serves valid metadata with a {@code jwks_uri} but no {@code issuer}. The provider claims to
     * be nobody else, so the entry must be retried, not condemned.
     */
    public void serveMetadataWithoutIssuer() {
        resetDiscoveryMappings();
        server.stubFor(
                get(urlEqualTo(DISCOVERY_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", JSON)
                                        .withBody(
                                                """
                                                {
                                                  "jwks_uri": "%s",
                                                  "response_types_supported": ["code"]
                                                }
                                                """
                                                        .formatted(jwksUri()))));
    }

    /** Serves metadata confirming the issuer but advertising no {@code jwks_uri}; retryable. */
    public void serveMetadataWithoutJwksUri() {
        resetDiscoveryMappings();
        server.stubFor(
                get(urlEqualTo(DISCOVERY_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", JSON)
                                        .withBody(
                                                """
                                                {
                                                  "issuer": "%s",
                                                  "response_types_supported": ["code"]
                                                }
                                                """
                                                        .formatted(issuer()))));
    }

    /** Serves an error status instead of a document, simulating an outage. */
    public void serveFailure(int statusCode) {
        resetDiscoveryMappings();
        server.stubFor(
                get(urlEqualTo(DISCOVERY_PATH)).willReturn(aResponse().withStatus(statusCode)));
    }

    /** Number of discovery requests so far; shows a permanently failed provider is not polled. */
    public int discoveryRequestCount() {
        return server.countRequestsMatching(getRequestedFor(urlEqualTo(DISCOVERY_PATH)).build())
                .getCount();
    }

    /** Minimal metadata document, with an unread member to cover unknown-member tolerance. */
    private String metadataDocument(String declaredIssuer) {
        return """
                {
                  "issuer": "%s",
                  "jwks_uri": "%s",
                  "authorization_endpoint": "%s/protocol/openid-connect/auth",
                  "token_endpoint": "%s/protocol/openid-connect/token",
                  "response_types_supported": ["code"],
                  "a_member_this_service_does_not_read": true
                }
                """
                .formatted(declaredIssuer, jwksUri(), issuer(), issuer());
    }

    /**
     * Forgets recorded requests and failure modes, serving matching metadata again. Keeps the
     * current key set, since a running context may have cached it.
     */
    public void reset() {
        server.resetAll();
        jwksFailureStatus = JWKS_SERVED_NORMALLY;
        serveMatchingMetadata();
    }

    /** Stops the stub server. */
    @Override
    public void close() {
        server.stop();
    }
}
