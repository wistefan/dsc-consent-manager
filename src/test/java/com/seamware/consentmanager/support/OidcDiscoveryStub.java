package com.seamware.consentmanager.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * A stand-in OpenID Connect provider that serves nothing but a discovery document.
 *
 * <p>Discovery is the only thing the identity-provider registry asks a provider for, so a full
 * Keycloak is not needed to test it - and would make the outage and misconfiguration cases, which
 * are the interesting ones, hard to produce on demand. This stub serves a well-formed metadata
 * document, a document naming the wrong issuer, or an error, and can switch between them while the
 * application under test is running.
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

    private final WireMockServer server;

    /** Starts the stub on a free port, initially serving a well-formed, matching document. */
    public OidcDiscoveryStub() {
        this.server = new WireMockServer(options().dynamicPort());
        this.server.start();
        serveMatchingMetadata();
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
        server.resetMappings();
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
        server.resetMappings();
        server.stubFor(
                get(urlEqualTo(DISCOVERY_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", JSON)
                                        .withBody("")));
    }

    /**
     * Serves a well-formed document that confirms the issuer but advertises no {@code jwks_uri}.
     *
     * <p>Without it there is nowhere to fetch signing keys from, so the entry cannot become usable
     * - but the provider has not claimed to be anybody else either, so this is retryable too.
     */
    public void serveMetadataWithoutJwksUri() {
        server.resetMappings();
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
        server.resetMappings();
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
