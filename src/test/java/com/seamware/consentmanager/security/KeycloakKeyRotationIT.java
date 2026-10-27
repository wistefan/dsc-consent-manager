package com.seamware.consentmanager.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.KeycloakAndPostgresTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource.RealmPrincipal;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.security.annotation.Secured;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that a signing-key rotation at the identity provider is picked up within one JWK Set cache
 * lifetime without a restart, while the previous key keeps verifying in-flight tokens.
 */
@MicronautTest(transactional = false)
@DisplayName("Identity provider signing-key rotation")
class KeycloakKeyRotationIT extends KeycloakAndPostgresTestResource {

    /** Enables the probe controller, so its route exists only for this test. */
    private static final String PROBE_ENABLED = "test.key-rotation.enabled";

    /** The secured route the tokens are presented to. */
    private static final String PROBE_ROUTE = "/key-rotation-probe";

    /** Key provider this test adds; suffixed per run since Keycloak allows duplicate names. */
    private static final String ROTATED_KEY_PROVIDER = "rotated-rsa-key-" + UUID.randomUUID();

    /** JWK Set cache lifetime, pinned short so rotation is observed without a minute's wait. */
    private static final String JWKS_CACHE_TTL = "2s";

    /** Key of the JWK Set cache lifetime pinned by {@link #JWKS_CACHE_TTL}. */
    private static final String JWKS_CACHE_TTL_PROPERTY =
            "micronaut.caches.jwks.expire-after-write";

    /** How long discovery against the container may take before the test gives up. */
    private static final Duration RESOLUTION_TIMEOUT = Duration.ofSeconds(60);

    /** How long the realm may take to start signing with the key just added. */
    private static final Duration ROTATION_TIMEOUT = Duration.ofSeconds(30);

    /** How long the rotated key may take to be accepted: one {@link #JWKS_CACHE_TTL} plus slack. */
    private static final Duration ACCEPTANCE_TIMEOUT = Duration.ofSeconds(15);

    /** Id Keycloak assigned to the key provider this test added, for cleanup. */
    private static String rotatedKeyComponentId;

    @Inject
    @Client("/")
    HttpClient client;

    @Inject IdentityProviderRegistry registry;

    /** Enables the probe controller and shortens the JWK Set cache lifetime. */
    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new LinkedHashMap<>(super.getProperties());
        properties.put(PROBE_ENABLED, "true");
        properties.put(JWKS_CACHE_TTL_PROPERTY, JWKS_CACHE_TTL);
        return properties;
    }

    /** Restores the realm to its imported state, so a rerun in the same container starts clean. */
    @AfterAll
    static void removeRotatedKey() {
        if (rotatedKeyComponentId != null) {
            KeycloakTestResource.deleteRealmComponent(rotatedKeyComponentId);
            rotatedKeyComponentId = null;
        }
    }

    /** The key it replaced must also keep verifying tokens already issued under it. */
    @Test
    @DisplayName("a token signed by a newly rotated key is accepted without a restart")
    void rotatedSigningKeyIsPickedUpWithoutARestart() {
        Await.until(
                "the Keycloak realm to resolve",
                RESOLUTION_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());

        String beforeRotation = KeycloakTestResource.accessToken(RealmPrincipal.CATALOG);
        String originalKeyId = KeycloakTestResource.signingKeyId(beforeRotation);
        assertThat(statusOf(beforeRotation))
                .as("the key set cached at startup verifies the token issued before rotation")
                .isEqualTo(HttpStatus.OK.getCode());

        rotatedKeyComponentId = KeycloakTestResource.rotateRealmSigningKey(ROTATED_KEY_PROVIDER);
        Await.until(
                "the realm to sign with the rotated key",
                ROTATION_TIMEOUT,
                () ->
                        !originalKeyId.equals(
                                KeycloakTestResource.signingKeyId(
                                        KeycloakTestResource.accessToken(RealmPrincipal.CATALOG))));

        String afterRotation = KeycloakTestResource.accessToken(RealmPrincipal.CATALOG);
        assertThat(KeycloakTestResource.signingKeyId(afterRotation))
                .as("the realm must have signed this one with the new key")
                .isNotEqualTo(originalKeyId);

        Await.until(
                "the rotated key to be refetched and accepted",
                ACCEPTANCE_TIMEOUT,
                () -> statusOf(afterRotation) == HttpStatus.OK.getCode());
        assertThat(statusOf(beforeRotation))
                .as("the superseded key stays published, so tokens still in flight keep working")
                .isEqualTo(HttpStatus.OK.getCode());
    }

    /** Presents {@code token} to the probe route and returns the status, refusals included. */
    private int statusOf(String token) {
        try {
            return client.toBlocking()
                    .exchange(HttpRequest.GET(PROBE_ROUTE).bearerAuth(token), String.class)
                    .code();
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    /** The one route this test presents its tokens to; exists only for this test. */
    @Requires(property = PROBE_ENABLED, value = "true")
    @Controller(PROBE_ROUTE)
    static class KeyRotationProbeController {

        /** Confirms the token verified by returning the resolved catalog subject. */
        @Get
        @Secured("CATALOG")
        String probe(CatalogPrincipal catalog) {
            return catalog.subject();
        }
    }
}
