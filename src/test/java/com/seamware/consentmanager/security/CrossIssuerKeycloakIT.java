package com.seamware.consentmanager.security;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.KeycloakAndPostgresTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource.RealmPrincipal;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cross-issuer key confusion against two live Keycloak realms, both trusted: a key genuinely valid
 * at realm B must not verify a token claiming realm A, nor make A's refusals dial B's key set.
 * Realm B signs with a key this test generated and imported, so the test can mint its tokens.
 */
@MicronautTest(transactional = false)
@DisplayName("Cross-issuer key confusion against live Keycloak")
class CrossIssuerKeycloakIT extends KeycloakAndPostgresTestResource {

    /** A real route the catalog role may read. */
    private static final String ROUTE = "/participants";

    private static final String PROBLEM_JSON = "application/problem+json";

    /** Trust-list entry for realm B; realm A is {@code primary}. */
    private static final String SECOND_PROVIDER = "realm-b";

    /** Unique per run, since the Keycloak container outlives the test class. */
    private static final String REALM_B = "cross-issuer-" + UUID.randomUUID();

    private static final int RSA_KEY_SIZE = 2048;

    /** Cross-issuer tokens presented while realm B's key-set fetches are counted. */
    private static final int CROSS_ISSUER_ATTEMPTS = 20;

    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(5);

    private static final Duration RESOLUTION_TIMEOUT = Duration.ofSeconds(60);

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private static final String DISCOVERY_PATH = "/.well-known/openid-configuration";

    private static final String JWKS_URI_MEMBER = "jwks_uri";

    private static final String CONTENT_TYPE = "Content-Type";

    private static final String JSON = "application/json";

    private static final String ROLES_CLAIM = "realm_access";

    private static final String ROLES_MEMBER = "roles";

    private static final String ROLES_CLAIM_PATH = ROLES_CLAIM + "," + ROLES_MEMBER;

    private static final String PARTICIPANT_IDENTIFIER_CLAIM = "participant_id";

    private static final String SUBJECT = "cross-issuer-catalog";

    /** Realm B's signing key, private half included; Keycloak signs with it as well. */
    private RSAKey realmBKey;

    /** The {@code kid} realm B publishes {@link #realmBKey} under. */
    private String realmBKeyId;

    /**
     * Fronts realm B's discovery and key set, so each fetch of B's keys is counted; the key set is
     * proxied from Keycloak unchanged.
     */
    private WireMockServer realmBFront;

    @Inject
    @Client("/")
    HttpClient client;

    @Inject IdentityProviderRegistry registry;

    /** Creates realm B before the context starts and trusts it as a second issuer. */
    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new LinkedHashMap<>(super.getProperties());
        if (realmBFront == null) {
            startRealmB();
        }
        String prefix = IdentityProviderConfiguration.PREFIX + "." + SECOND_PROVIDER + ".";
        properties.put(prefix + "issuer", KeycloakTestResource.issuer(REALM_B));
        properties.put(prefix + "discovery-url", realmBFront.baseUrl() + DISCOVERY_PATH);
        properties.put(prefix + "audience", KeycloakTestResource.AUDIENCE);
        properties.put(prefix + "allow-insecure-transport", "true");
        properties.put(prefix + "claims.roles", ROLES_CLAIM_PATH);
        properties.put(prefix + "claims.participant-identifier", PARTICIPANT_IDENTIFIER_CLAIM);
        properties.put(prefix + "role-mapping.catalog", RealmPrincipal.CATALOG.rawRole());
        return properties;
    }

    @AfterAll
    void removeRealmB() {
        if (realmBFront != null) {
            realmBFront.stop();
        }
        KeycloakTestResource.deleteRealm(REALM_B);
    }

    @BeforeEach
    void awaitBothRealms() {
        Await.until(
                "both Keycloak realms to resolve",
                RESOLUTION_TIMEOUT,
                () ->
                        registry.findByIssuer(KeycloakTestResource.issuer()).isPresent()
                                && registry.findByIssuer(KeycloakTestResource.issuer(REALM_B))
                                        .isPresent());
    }

    /** Pins that the refusal below is the claimed issuer's doing, not the key's or the claims'. */
    @Test
    @DisplayName("realm B's key under realm B's issuer is accepted")
    void ownIssuerIsAccepted() {
        assertThat(exchange(signedByRealmB(KeycloakTestResource.issuer(REALM_B))).code())
                .isEqualTo(HttpStatus.OK.getCode());
    }

    @Test
    @DisplayName("realm B's key and kid under realm A's issuer is refused with a 401 problem")
    void crossIssuerTokenIsUnauthorized() {
        HttpResponse<String> response = exchange(signedByRealmB(KeycloakTestResource.issuer()));

        assertThat(response.code()).isEqualTo(HttpStatus.UNAUTHORIZED.getCode());
        assertThat(response.getContentType())
                .hasValueSatisfying(type -> assertThat(type.toString()).startsWith(PROBLEM_JSON));
        assertThat(response.getBody(String.class))
                .hasValueSatisfying(
                        body ->
                                assertThat(body)
                                        .contains(
                                                "\"status\":" + HttpStatus.UNAUTHORIZED.getCode()));
    }

    /** Security #7: an unknown {@code kid} at A must not send the service to another issuer. */
    @Test
    @DisplayName("repeated cross-issuer tokens never fetch realm B's key set")
    void crossIssuerTokensLeaveRealmBKeySetUntouched() {
        assertThat(exchange(signedByRealmB(KeycloakTestResource.issuer(REALM_B))).code())
                .as("realm B's key set is cached before counting starts")
                .isEqualTo(HttpStatus.OK.getCode());
        int fetchesBefore = realmBJwksRequestCount();

        for (int attempt = 0; attempt < CROSS_ISSUER_ATTEMPTS; attempt++) {
            assertThat(exchange(signedByRealmB(KeycloakTestResource.issuer())).code())
                    .isEqualTo(HttpStatus.UNAUTHORIZED.getCode());
        }

        assertThat(realmBJwksRequestCount())
                .as("a token is verified only against the keys of the issuer it claims")
                .isEqualTo(fetchesBefore);
    }

    /** Creates realm B with a generated key and fronts it with a counting WireMock server. */
    private void startRealmB() {
        try {
            realmBKey = new RSAKeyGenerator(RSA_KEY_SIZE).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not generate realm B's key", e);
        }
        realmBKeyId = KeycloakTestResource.createRealmSigningWith(REALM_B, realmBKey);
        realmBFront = new WireMockServer(options().dynamicPort());
        realmBFront.start();
        URI keycloakJwks = URI.create(KeycloakTestResource.jwksUrl(REALM_B));
        realmBFront.stubFor(
                WireMock.get(urlEqualTo(keycloakJwks.getPath()))
                        .willReturn(aResponse().proxiedFrom(origin(keycloakJwks))));
        realmBFront.stubFor(
                WireMock.get(urlEqualTo(DISCOVERY_PATH))
                        .willReturn(
                                aResponse()
                                        .withHeader(CONTENT_TYPE, JSON)
                                        .withBody(
                                                discoveryPointingJwksAt(
                                                        realmBFront.baseUrl()
                                                                + keycloakJwks.getPath()))));
    }

    /** Realm B's genuine discovery document, its {@code jwks_uri} rewritten to the front. */
    private static String discoveryPointingJwksAt(String jwksUri) {
        java.net.http.HttpRequest request =
                java.net.http.HttpRequest.newBuilder(
                                URI.create(KeycloakTestResource.discoveryUrl(REALM_B)))
                        .timeout(HTTP_TIMEOUT)
                        .GET()
                        .build();
        try (java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient()) {
            String body =
                    http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
            Map<String, Object> document = new LinkedHashMap<>(JSONObjectUtils.parse(body));
            document.put(JWKS_URI_MEMBER, jwksUri);
            return JSONObjectUtils.toJSONString(document);
        } catch (IOException | ParseException e) {
            throw new IllegalStateException("Could not read realm B's discovery document", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted reading realm B's discovery document", e);
        }
    }

    private static String origin(URI uri) {
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    /** A catalog token signed by realm B's key under B's {@code kid}, claiming {@code issuer}. */
    private String signedByRealmB(String issuer) {
        Instant now = Instant.now();
        JWTClaimsSet claims =
                new JWTClaimsSet.Builder()
                        .issuer(issuer)
                        .subject(SUBJECT)
                        .audience(KeycloakTestResource.AUDIENCE)
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plus(TOKEN_LIFETIME)))
                        .jwtID(UUID.randomUUID().toString())
                        .claim(
                                ROLES_CLAIM,
                                Map.of(ROLES_MEMBER, List.of(RealmPrincipal.CATALOG.rawRole())))
                        .build();
        SignedJWT token =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(realmBKeyId).build(),
                        claims);
        try {
            token.sign(new RSASSASigner(realmBKey));
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign with realm B's key", e);
        }
        return token.serialize();
    }

    private int realmBJwksRequestCount() {
        String path = URI.create(KeycloakTestResource.jwksUrl(REALM_B)).getPath();
        return realmBFront
                .countRequestsMatching(getRequestedFor(urlEqualTo(path)).build())
                .getCount();
    }

    /** Presents {@code token} to {@link #ROUTE}; a refusal is returned rather than thrown. */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> exchange(String token) {
        try {
            return client.toBlocking()
                    .exchange(HttpRequest.GET(ROUTE).bearerAuth(token), String.class);
        } catch (HttpClientResponseException e) {
            return (HttpResponse<String>) e.getResponse();
        }
    }
}
