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
 * Signing-key rotation at the identity provider, survived without restarting this service.
 *
 * <p>A provider rotates its signing keys on its own schedule and tells nobody. The keys this
 * service verifies with are cached and there is no refresh-on-miss path, so a token signed by a key
 * minted after that cache was filled is refused until the cached set expires. That expiry is the
 * whole budget for rotation, and the previous key has to keep working while tokens signed by it are
 * still in flight.
 *
 * <p>The rotation here only <em>adds</em> a higher-priority key to the realm, which is what
 * Keycloak itself does, so tokens other test classes hold stay verifiable; the added key provider
 * is removed again afterwards, leaving the shared container as the realm import left it.
 *
 * <p>Uses the catalog role on purpose: it is the one principal that resolves from the token alone,
 * so nothing in this test depends on a database row.
 */
@MicronautTest(transactional = false)
@DisplayName("Identity provider signing-key rotation")
class KeycloakKeyRotationIT extends KeycloakAndPostgresTestResource {

    /** Enables the probe controller, so its route exists only for this test. */
    private static final String PROBE_ENABLED = "test.key-rotation.enabled";

    /** The secured route the tokens are presented to. */
    private static final String PROBE_ROUTE = "/key-rotation-probe";

    /**
     * Name of the key provider this test adds to the realm.
     *
     * <p>Suffixed per run because Keycloak does not enforce unique component names: a leftover
     * provider from an earlier run in the same container would sit at the same priority as this
     * one, leaving which key signs undecided. {@link #removeRotatedKey()} deletes it either way.
     */
    private static final String ROTATED_KEY_PROVIDER = "rotated-rsa-key-" + UUID.randomUUID();

    /**
     * JWK Set cache lifetime for this test's context.
     *
     * <p>Pinned rather than inherited: the rotated key is picked up exactly when the cached set
     * expires, so {@code application.yml}'s production default of {@code 60s} would put a minute of
     * dead waiting into every {@code verify} and silently couple {@link #ACCEPTANCE_TIMEOUT} to an
     * environment variable.
     */
    private static final String JWKS_CACHE_TTL = "2s";

    /** Key of the JWK Set cache lifetime pinned by {@link #JWKS_CACHE_TTL}. */
    private static final String JWKS_CACHE_TTL_PROPERTY =
            "micronaut.caches.jwks.expire-after-write";

    /** How long discovery against the container may take before the test gives up. */
    private static final Duration RESOLUTION_TIMEOUT = Duration.ofSeconds(60);

    /** How long the realm may take to start signing with the key just added. */
    private static final Duration ROTATION_TIMEOUT = Duration.ofSeconds(30);

    /**
     * How long this service may take to accept the rotated key: at most one {@link #JWKS_CACHE_TTL}
     * plus slack for a loaded CI machine.
     */
    private static final Duration ACCEPTANCE_TIMEOUT = Duration.ofSeconds(15);

    /** Id Keycloak assigned to the key provider this test added, for cleanup. */
    private static String rotatedKeyComponentId;

    @Inject
    @Client("/")
    HttpClient client;

    @Inject IdentityProviderRegistry registry;

    /**
     * Enables the probe controller and shortens the JWK Set cache lifetime, on top of the
     * Testcontainers datasource and trust list.
     *
     * @return the property overrides for this test's context
     */
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

    /**
     * A token signed by a key minted after startup is accepted, and the key it replaced keeps
     * verifying the tokens already issued under it.
     */
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

    /**
     * Presents {@code token} to the probe route and reports the status.
     *
     * @param token the bearer token to send
     * @return the response status code, refusals included
     */
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

        /**
         * Confirms the token verified by naming who it resolved to.
         *
         * @param catalog the resolved caller
         * @return the catalog service's subject
         */
        @Get
        @Secured("CATALOG")
        String probe(CatalogPrincipal catalog) {
            return catalog.subject();
        }
    }
}
