package com.seamware.consentmanager.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.KeycloakAndPostgresTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource.RealmPrincipal;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests the authorization matrix end to end with real Keycloak tokens: the status each caller,
 * outsider and anonymous included, gets from each kind of route. Not transactional, because the
 * server reads the seeded participant on its own connection.
 */
@MicronautTest(transactional = false)
@DisplayName("Role matrix against a real identity provider")
class KeycloakRoleMatrixIT extends KeycloakAndPostgresTestResource {

    /** Enables the probe controller, so its routes exist only for this test. */
    private static final String PROBE_ENABLED = "test.role-matrix.enabled";

    /** Prefix of every probe route. */
    private static final String PROBE_PATH = "/role-matrix-probe";

    /** Probe serving any caller this service resolves to a principal, whatever its role. */
    private static final String AUTHENTICATED_ROUTE = PROBE_PATH + "/authenticated";

    /** Probe serving {@link Role#USER} only. */
    private static final String USER_ROUTE = PROBE_PATH + "/user";

    /** Probe serving {@link Role#PARTICIPANT} only. */
    private static final String PARTICIPANT_ROUTE = PROBE_PATH + "/participant";

    /** Probe serving {@link Role#CATALOG} only. */
    private static final String CATALOG_ROUTE = PROBE_PATH + "/catalog";

    /** The public reachability probe, which stays reachable for every caller including none. */
    private static final String ANONYMOUS_ROUTE = "/api-status";

    /** Media type every refusal carries, per RFC 7807. */
    private static final String PROBLEM_JSON = "application/problem+json";

    /** Header carrying the challenge a 401 must invite the client to answer. */
    private static final String WWW_AUTHENTICATE = "WWW-Authenticate";

    /** Legal name of the participant the {@code participant_id} claim points at. */
    private static final String PARTICIPANT_LEGAL_NAME = "Keycloak Test Participant Ltd";

    /** How long discovery against the container may take before the test gives up. */
    private static final Duration RESOLUTION_TIMEOUT = Duration.ofSeconds(60);

    @Inject
    @Client("/")
    HttpClient client;

    @Inject IdentityProviderRegistry registry;

    @Inject ParticipantRepository participants;

    /**
     * Callers in the matrix: one per realm role, plus {@link #OUTSIDER} (authenticated, no mapped
     * role) and {@link #ANONYMOUS} (no token).
     */
    enum Caller {
        /** Holds the realm role mapped to {@link Role#USER}. */
        USER(RealmPrincipal.USER),

        /** Holds the realm role mapped to {@link Role#PARTICIPANT}. */
        PARTICIPANT(RealmPrincipal.PARTICIPANT),

        /** Holds the realm role mapped to {@link Role#CATALOG}. */
        CATALOG(RealmPrincipal.CATALOG),

        /** Holds a realm role the trust list maps to no role of this service. */
        OUTSIDER(RealmPrincipal.OUTSIDER),

        /** Sends no credential whatsoever. */
        ANONYMOUS(null);

        private final RealmPrincipal realmClient;

        Caller(RealmPrincipal realmClient) {
            this.realmClient = realmClient;
        }

        /** A freshly issued token for this caller, or {@code null} when it sends none. */
        String token() {
            return realmClient == null ? null : KeycloakTestResource.accessToken(realmClient);
        }
    }

    /** Enables the probe controller on top of the Testcontainers datasource and trust list. */
    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new LinkedHashMap<>(super.getProperties());
        properties.put(PROBE_ENABLED, "true");
        return properties;
    }

    /** Waits for discovery against the container and commits the participant the realm names. */
    @BeforeEach
    void prepare() {
        Await.until(
                "the Keycloak realm to resolve",
                RESOLUTION_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        if (!participants.existsByIdentifier(KeycloakTestResource.PARTICIPANT_IDENTIFIER)) {
            participants.save(
                    new Participant(
                            KeycloakTestResource.PARTICIPANT_IDENTIFIER,
                            PARTICIPANT_LEGAL_NAME,
                            null,
                            null,
                            Map.of(),
                            null));
        }
    }

    @ParameterizedTest(name = "{0} on {1} is {2}")
    @MethodSource("roleMatrix")
    @DisplayName("each role reaches exactly the routes it is entitled to")
    void roleMatrixHolds(Caller caller, String route, HttpStatus expected) {
        HttpResponse<String> response = get(route, caller.token());

        assertThat(response.code()).isEqualTo(expected.getCode());
        if (expected != HttpStatus.OK) {
            assertThatIsRefusal(response, expected);
        }
    }

    /**
     * One row per caller, one column per route. A caller with no role here gets 403, not 401, as
     * its token was valid; the anonymous probe answers 200 to every caller.
     */
    static Stream<Arguments> roleMatrix() {
        HttpStatus ok = HttpStatus.OK;
        HttpStatus denied = HttpStatus.FORBIDDEN;
        HttpStatus challenged = HttpStatus.UNAUTHORIZED;
        return Stream.of(
                        row(Caller.USER, ok, ok, denied, denied, ok),
                        row(Caller.PARTICIPANT, ok, denied, ok, denied, ok),
                        row(Caller.CATALOG, ok, denied, denied, ok, ok),
                        row(Caller.OUTSIDER, denied, denied, denied, denied, ok),
                        row(Caller.ANONYMOUS, challenged, challenged, challenged, challenged, ok))
                .flatMap(row -> row);
    }

    /** Expands one caller's row of statuses into one case per route. */
    private static Stream<Arguments> row(
            Caller caller,
            HttpStatus authenticated,
            HttpStatus user,
            HttpStatus participant,
            HttpStatus catalog,
            HttpStatus anonymous) {
        return Stream.of(
                Arguments.of(caller, AUTHENTICATED_ROUTE, authenticated),
                Arguments.of(caller, USER_ROUTE, user),
                Arguments.of(caller, PARTICIPANT_ROUTE, participant),
                Arguments.of(caller, CATALOG_ROUTE, catalog),
                Arguments.of(caller, ANONYMOUS_ROUTE, anonymous));
    }

    /** The participant is loaded from the database, not taken from the token. */
    @Test
    @DisplayName("a participant token resolves to the registered participant it names")
    void participantTokenResolvesToTheRegisteredParticipant() {
        HttpResponse<String> response = get(PARTICIPANT_ROUTE, Caller.PARTICIPANT.token());

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(response.getBody(String.class))
                .hasValue(
                        KeycloakTestResource.PARTICIPANT_IDENTIFIER + " " + PARTICIPANT_LEGAL_NAME);
    }

    /** Asserts a refusal carries a problem body and, for a 401 only, a challenge. */
    private static void assertThatIsRefusal(HttpResponse<String> response, HttpStatus expected) {
        assertThat(response.getContentType())
                .hasValueSatisfying(type -> assertThat(type.toString()).startsWith(PROBLEM_JSON));
        assertThat(response.getBody(String.class).orElse(""))
                .contains("\"status\":" + expected.getCode());
        if (expected == HttpStatus.FORBIDDEN) {
            assertThat(response.getHeaders().getAll(WWW_AUTHENTICATE))
                    .as("no credential fixes a 403, so it must not invite the client to retry")
                    .isEmpty();
        } else {
            assertThat(response.getHeaders().getAll(WWW_AUTHENTICATE)).isNotEmpty();
        }
    }

    /** Performs a GET with an optional bearer token, returning refusals as well. */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> get(String path, String token) {
        MutableHttpRequest<Object> request = HttpRequest.GET(path);
        if (token != null) {
            request = request.bearerAuth(token);
        }
        try {
            return client.toBlocking().exchange(request, String.class);
        } catch (HttpClientResponseException e) {
            return (HttpResponse<String>) e.getResponse();
        }
    }

    /**
     * Test-only routes, one per role, shaped like a real endpoint: the access rule names the role
     * and the handler takes its caller from the declared principal.
     */
    @Requires(property = PROBE_ENABLED, value = "true")
    @Controller(PROBE_PATH)
    static class RoleMatrixProbeController {

        /** Serves every caller that resolves to a principal of this service. */
        @Get("/authenticated")
        @Secured(SecurityRule.IS_AUTHENTICATED)
        String authenticated(ConsentManagerPrincipal principal) {
            return principal.role().name();
        }

        /** Serves users only. */
        @Get("/user")
        @Secured("USER")
        String user(UserPrincipal user) {
            return user.identifier();
        }

        /**
         * Serves participants only; {@code requireRegistered()} turns a missing seeded row into a
         * clean {@code 403} rather than a {@code NullPointerException}.
         */
        @Get("/participant")
        @Secured("PARTICIPANT")
        String participant(ParticipantPrincipal participant) {
            return participant.identifier() + " " + participant.requireRegistered().getLegalName();
        }

        /** Serves the catalog service only. */
        @Get("/catalog")
        @Secured("CATALOG")
        String catalog(CatalogPrincipal catalog) {
            return catalog.subject();
        }
    }
}
