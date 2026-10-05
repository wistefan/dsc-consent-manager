package com.seamware.consentmanager.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.UserRepository;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The authorization matrix, exercised with real tokens from a Testcontainers Keycloak: for every
 * role this service knows, and for the two callers it does not serve, the status each kind of route
 * answers with.
 *
 * <p>The tokens here are issued by a conforming identity provider rather than minted by the test,
 * so this is also the end-to-end proof that discovery, JWK Set retrieval, signature verification,
 * claim mapping and principal resolution agree with how a real Keycloak actually shapes a token.
 * Hand-minted tokens stay in {@link PrincipalResolutionIT} and {@code
 * ConsentManagerTokenValidatorTest}, for the cryptographic negatives no conforming provider can be
 * made to issue.
 *
 * <p>Not transactional: the participant row has to be committed before the server, which answers on
 * its own connection, can see it.
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

    @Inject UserRepository users;

    /**
     * The callers in the matrix: one per role the realm grants, plus the two the service refuses.
     *
     * <p>{@link #OUTSIDER} holds a realm role the trust list maps to nothing, which is the
     * authenticated-but-unauthorized case; {@link #ANONYMOUS} sends no token at all.
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

    /**
     * Enables the probe controller on top of the Testcontainers datasource and trust list.
     *
     * @return the property overrides for this test's context
     */
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

    /** Removes the rows that outlive the test: the participant and the provisioned user. */
    @AfterAll
    void removeCommittedRows() {
        participants
                .findByIdentifier(KeycloakTestResource.PARTICIPANT_IDENTIFIER)
                .ifPresent(participants::delete);
        users.findByIdentifier(KeycloakTestResource.subject(Caller.USER.token()))
                .ifPresent(users::delete);
    }

    /**
     * Every documented combination of caller and route answers with its documented status.
     *
     * @param caller who sends the request
     * @param route the route requested
     * @param expected the status the combination must produce
     */
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
     * The matrix itself: one row per caller, one column per route.
     *
     * <p>A caller with no role of this service is {@code 403} rather than {@code 401} on every
     * secured route: its signature was good and its issuer is trusted, so asking it to authenticate
     * again would be a lie. The anonymous probe stays {@code 200} throughout, including for a
     * caller whose token names nobody, because a reachability check must not depend on a
     * credential.
     *
     * @return one case per cell
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

    /**
     * Expands one caller's row into one case per route.
     *
     * @param caller the row's caller
     * @param authenticated status on the any-principal route
     * @param user status on the user-only route
     * @param participant status on the participant-only route
     * @param catalog status on the catalog-only route
     * @param anonymous status on the public reachability probe
     * @return the row's five cases
     */
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

    /**
     * A real participant token reaches its handler as the organisation its {@code participant_id}
     * claim names, loaded from the database rather than taken from the token.
     */
    @Test
    @DisplayName("a participant token resolves to the registered participant it names")
    void participantTokenResolvesToTheRegisteredParticipant() {
        HttpResponse<String> response = get(PARTICIPANT_ROUTE, Caller.PARTICIPANT.token());

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(response.getBody(String.class))
                .hasValue(
                        KeycloakTestResource.PARTICIPANT_IDENTIFIER + " " + PARTICIPANT_LEGAL_NAME);
    }

    /**
     * Asserts a refusal carries a problem body and, for a 401 only, a challenge.
     *
     * @param response the refusal
     * @param expected the status it must carry
     */
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

    /**
     * Performs a GET, returning the response even when it is a refusal.
     *
     * @param path the path to request
     * @param token the bearer token, or {@code null} to send none
     * @return the response
     */
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
     * One route per role, in the shape every real endpoint is expected to have: the access rule
     * names the role, and the handler takes its caller from the principal it declares.
     *
     * <p>They exist only for this test; the matrix they make testable is the template the endpoint
     * tickets extend with their own rows.
     */
    @Requires(property = PROBE_ENABLED, value = "true")
    @Controller(PROBE_PATH)
    static class RoleMatrixProbeController {

        /**
         * Serves every caller that resolves to a principal of this service.
         *
         * @param principal the resolved caller
         * @return the caller's role
         */
        @Get("/authenticated")
        @Secured(SecurityRule.IS_AUTHENTICATED)
        String authenticated(ConsentManagerPrincipal principal) {
            return principal.role().name();
        }

        /**
         * Serves users only.
         *
         * @param user the resolved caller
         * @return the user's identifier
         */
        @Get("/user")
        @Secured("USER")
        String user(UserPrincipal user) {
            return user.identifier();
        }

        /**
         * Serves participants only.
         *
         * @param participant the resolved caller
         * @return the participant's identifier and legal name
         */
        @Get("/participant")
        @Secured("PARTICIPANT")
        String participant(ParticipantPrincipal participant) {
            return participant.identifier() + " " + participant.participant().getLegalName();
        }

        /**
         * Serves the catalog service only.
         *
         * @param catalog the resolved caller
         * @return the catalog service's subject
         */
        @Get("/catalog")
        @Secured("CATALOG")
        String catalog(CatalogPrincipal catalog) {
            return catalog.subject();
        }
    }
}
