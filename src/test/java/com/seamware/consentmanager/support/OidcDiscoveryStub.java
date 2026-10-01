package com.seamware.consentmanager.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.util.ArrayList;
import java.util.List;

/**
 * A stand-in OpenID Connect provider that serves nothing but a discovery document.
 *
 * <p>Discovery is the only thing the identity-provider registry asks a provider for, so a full
 * Keycloak is not needed to test it - and would make the outage and misconfiguration cases, which
 * are the interesting ones, hard to produce on demand. This stub serves a well-formed metadata
 * document, a document naming the wrong issuer, or an error, and can switch between them while the
 * application under test is running.
 *
 * <p>It also publishes a JWK Set at the location its own metadata advertises, with keys it
 * generates on demand, so a key rotation - which against a real provider means administrative
 * action and a wait - is a single method call here.
 *
 * <p>The server listens on a dynamic port, so {@link #issuer()} is only known once the stub is
 * constructed. Tests therefore build the stub first and feed {@link #issuer()} and {@link
 * #discoveryUrl()} into the application context as properties.
 */
public final class OidcDiscoveryStub implements AutoCloseable {

    /** Path prefix the stubbed realm lives under, mirroring Keycloak's URL layout. */
    private static final String REALM_PATH = "/realms/test";

    /** Where OpenID Connect Discovery 1.0 requires the metadata document to be published. */
    private static final String DISCOVERY_PATH = REALM_PATH + "/.well-known/openid-configuration";

    /** Path the stubbed metadata advertises as the JWK Set location. */
    private static final String JWKS_PATH = REALM_PATH + "/protocol/openid-connect/certs";

    /** Content type of a discovery document. */
    private static final String JSON = "application/json";

    /** Modulus length of the generated RSA signing keys, matching what Keycloak issues. */
    private static final int KEY_SIZE_BITS = 2048;

    /** Key identifier of the key the stub publishes before any rotation. */
    private static final String INITIAL_KEY_ID = "initial-key";

    /** Sentinel for {@link #jwksFailureStatus} meaning "serve the key set normally". */
    private static final int JWKS_SERVED_NORMALLY = 0;

    private final WireMockServer server;

    /** The key set currently published, replaced wholesale by {@link #rotateKeysTo(String...)}. */
    private volatile JWKSet jwkSet;

    /** HTTP status to answer JWK Set requests with, or {@link #JWKS_SERVED_NORMALLY}. */
    private volatile int jwksFailureStatus = JWKS_SERVED_NORMALLY;

    /**
     * Starts the stub on a free port, initially serving a well-formed, matching document and a key
     * set holding exactly {@link #initialKeyId()}.
     */
    public OidcDiscoveryStub() {
        this.server = new WireMockServer(options().dynamicPort());
        this.server.start();
        this.jwkSet = generateKeySet(INITIAL_KEY_ID);
        serveMatchingMetadata();
    }

    /**
     * Returns the key identifier the stub publishes until a test rotates it away.
     *
     * @return the initial {@code kid}
     */
    public String initialKeyId() {
        return INITIAL_KEY_ID;
    }

    /**
     * Replaces the published key set with freshly generated keys under the given identifiers.
     *
     * <p>This is a rotation as a relying party sees one: the old {@code kid} disappears and a new
     * one takes its place, with no notification and no restart. Pass several identifiers to model
     * the overlap window a careful provider leaves, during which both the retiring and the incoming
     * key are published.
     *
     * @param keyIds the {@code kid} values the new key set holds, in order
     */
    public void rotateKeysTo(String... keyIds) {
        this.jwkSet = generateKeySet(keyIds);
        stubJwks();
    }

    /**
     * Makes JWK Set requests fail, simulating a provider whose key endpoint is down.
     *
     * @param statusCode the HTTP status to answer the JWK Set request with
     */
    public void failJwksRequests(int statusCode) {
        this.jwksFailureStatus = statusCode;
        stubJwks();
    }

    /**
     * Counts how many JWK Set requests have reached this stub.
     *
     * <p>The caching, the rate limit and the single-flight refresh all exist to keep this number
     * down, and none of them can be observed any other way from outside.
     *
     * @return the number of requests for the key set so far
     */
    public int jwksRequestCount() {
        return server.countRequestsMatching(getRequestedFor(urlEqualTo(JWKS_PATH)).build())
                .getCount();
    }

    /**
     * Generates an RSA signing key per identifier and collects them into a set.
     *
     * @param keyIds the {@code kid} values to generate keys for
     * @return the generated key set, private halves included
     */
    private static JWKSet generateKeySet(String... keyIds) {
        List<JWK> keys = new ArrayList<>(keyIds.length);
        for (String keyId : keyIds) {
            try {
                keys.add(
                        new RSAKeyGenerator(KEY_SIZE_BITS)
                                .keyID(keyId)
                                .keyUse(KeyUse.SIGNATURE)
                                .generate());
            } catch (JOSEException failure) {
                throw new IllegalStateException("Could not generate a test signing key", failure);
            }
        }
        return new JWKSet(keys);
    }

    /**
     * Installs the JWK Set mapping, serving either the current key set or the configured failure.
     *
     * <p>Only the public halves are published, exactly as a real provider does.
     */
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

    /**
     * Clears the discovery mappings and reinstates the JWK Set one.
     *
     * <p>Every {@code serve*} method replaces the discovery stub wholesale, and WireMock's reset
     * clears the key-set stub along with it. Routing those resets through here keeps the two
     * documents independent: changing what discovery says never silently stops the keys being
     * served.
     */
    private void resetDiscoveryMappings() {
        server.resetMappings();
        stubJwks();
    }

    /**
     * Returns the issuer identifier this stub claims, and that tests should configure.
     *
     * @return an absolute issuer URL on the stub's dynamic port
     */
    public String issuer() {
        return server.baseUrl() + REALM_PATH;
    }

    /**
     * Returns the URL of the stubbed discovery document.
     *
     * @return an absolute discovery URL on the stub's dynamic port
     */
    public String discoveryUrl() {
        return server.baseUrl() + DISCOVERY_PATH;
    }

    /**
     * Returns the JWK Set URL the stubbed metadata advertises.
     *
     * @return an absolute JWK Set URL on the stub's dynamic port
     */
    public String jwksUri() {
        return server.baseUrl() + JWKS_PATH;
    }

    /** Serves a well-formed document whose {@code issuer} matches {@link #issuer()}. */
    public void serveMatchingMetadata() {
        serveMetadataDeclaring(issuer());
    }

    /**
     * Serves a well-formed document that declares some other issuer.
     *
     * <p>This is the misconfiguration case: a {@code discovery-url} pointing at a provider that is
     * not the configured one. The registry must reject it permanently rather than retry it.
     *
     * @param declaredIssuer the value to put in the document's {@code issuer} member
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
     * Serves a 200 with an empty body, as a reverse proxy mid-reload or a truncated response does.
     *
     * <p>The status says the request succeeded while the payload says nothing at all, so the
     * provider's identity is unconfirmed rather than contradicted. The registry must treat this as
     * the outage it almost certainly is and retry, not condemn the entry permanently.
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
     * Serves a well-formed document that carries a {@code jwks_uri} but no {@code issuer} member.
     *
     * <p>Unlike an empty body this is valid JSON the registry can deserialize - it simply declines
     * to say who the provider is, as a provider whose metadata is still being written or a gateway
     * serving a stripped-down document does. The provider has not claimed to be anybody else, so
     * the entry must be retried rather than condemned, and this is the case that pins that rule: an
     * empty body never reaches the issuer check at all.
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

    /**
     * Serves a well-formed document that confirms the issuer but advertises no {@code jwks_uri}.
     *
     * <p>Without it there is nowhere to fetch signing keys from, so the entry cannot become usable
     * - but the provider has not claimed to be anybody else either, so this is retryable too.
     */
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

    /**
     * Serves an error instead of a document, simulating an outage.
     *
     * @param statusCode the HTTP status to answer the discovery request with
     */
    public void serveFailure(int statusCode) {
        resetDiscoveryMappings();
        server.stubFor(
                get(urlEqualTo(DISCOVERY_PATH)).willReturn(aResponse().withStatus(statusCode)));
    }

    /**
     * Counts how many discovery requests have reached this stub.
     *
     * <p>Used to assert that a permanently failed provider really is never polled again, which no
     * amount of state inspection can show on its own.
     *
     * @return the number of requests for the discovery document so far
     */
    public int discoveryRequestCount() {
        return server.countRequestsMatching(getRequestedFor(urlEqualTo(DISCOVERY_PATH)).build())
                .getCount();
    }

    /**
     * Builds a minimal but realistic metadata document.
     *
     * <p>It carries several members the registry does not read, so the tests also cover that an
     * unknown member does not break deserialization.
     *
     * @param declaredIssuer the value of the {@code issuer} member
     * @return the document as JSON
     */
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

    /** Stops the stub server. */
    @Override
    public void close() {
        server.stop();
    }
}
