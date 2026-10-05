package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.domain.UserParticipant;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.UserParticipantRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.security.IdentityProviderRegistry;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.KeycloakAndPostgresTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource.RealmPrincipal;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises {@code POST /users/register} and {@code GET /users/me} end to end against a real
 * Keycloak and a real PostgreSQL.
 *
 * <p>Both routes are served from the principal the resolution filter already provisioned, so what
 * is under test is as much the wiring - the generated route, its typed principal parameter and the
 * created flag carried on it - as the two handler bodies.
 */
@MicronautTest(transactional = false)
@DisplayName("User self-service endpoints")
class UserSelfServiceIT extends KeycloakAndPostgresTestResource {

    /** Participant this test links the caller to, distinct from the realm's own participant. */
    private static final String LINKED_PARTICIPANT = "urn:test:participant:self-service";

    /** Every property the {@code User} representation is allowed to carry. */
    private static final List<String> ALLOWED_PROPERTIES =
            List.of(
                    "identifier",
                    "email",
                    "firstName",
                    "lastName",
                    "participants",
                    "createdAt",
                    "updatedAt");

    /** Substrings that would betray authentication material having reached a response. */
    private static final List<String> AUTHENTICATION_MATERIAL =
            List.of("token", "secret", "credential", "password", "bearer", "assertion");

    /** Longest a cold Keycloak container may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    /** Body of a request to an operation that specifies none; the route ignores it. */
    private static final String NO_BODY = "";

    private static final String PATH_REGISTER = "/users/register";

    private static final String PATH_ME = "/users/me";

    @Inject
    @Client("/")
    HttpClient client;

    @Inject ObjectMapper json;

    @Inject IdentityProviderRegistry registry;

    @Inject UserRepository users;

    @Inject ParticipantRepository participants;

    @Inject UserParticipantRepository links;

    /** The caller's global identifier, resolved once because reading it costs a token request. */
    private String identifier;

    /**
     * The routes under test, each building a fresh request so no case mutates another's.
     *
     * <p>A parameterized test may not take the request factory directly: Micronaut's JUnit
     * extension claims a {@code Supplier} parameter as a bean to inject and competes with the
     * argument source, which fails the case before it runs. An enum declared here is claimed by
     * neither.
     */
    enum Route {
        REGISTER("POST " + PATH_REGISTER, () -> HttpRequest.POST(PATH_REGISTER, NO_BODY)),
        CURRENT("GET " + PATH_ME, () -> HttpRequest.GET(PATH_ME));

        private final String label;
        private final Supplier<MutableHttpRequest<?>> factory;

        Route(String label, Supplier<MutableHttpRequest<?>> factory) {
            this.label = label;
            this.factory = factory;
        }

        MutableHttpRequest<?> request() {
            return factory.get();
        }

        @Override
        public String toString() {
            return label;
        }
    }

    static Stream<Route> routes() {
        return Stream.of(Route.values());
    }

    /** Every route paired with a role that authenticates but is not the one the route serves. */
    static Stream<Arguments> refusedRoles() {
        return routes().flatMap(
                        route ->
                                Stream.of(RealmPrincipal.PARTICIPANT, RealmPrincipal.CATALOG)
                                        .map(role -> Arguments.of(route, role)));
    }

    @BeforeEach
    void startFromAnUnregisteredUser() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        identifier = KeycloakTestResource.subject(userToken());
        // The realm holds a single dataspace-user, so "not registered yet" has to be re-established
        // rather than assumed: otherwise only the first test to run would ever observe a 201.
        removeCommittedRows();
        if (!participants.existsByIdentifier(LINKED_PARTICIPANT)) {
            participants.save(
                    new Participant(
                            LINKED_PARTICIPANT,
                            "Self Service Test Ltd",
                            null,
                            null,
                            Map.of(),
                            null));
        }
    }

    /** Rows committed by these tests outlive the transaction, so they are removed by hand. */
    @AfterAll
    void removeCommittedRows() {
        storedUser()
                .ifPresent(
                        user -> {
                            links.findByIdUserId(user.getId()).forEach(links::delete);
                            users.delete(user);
                        });
        participants.findByIdentifier(LINKED_PARTICIPANT).ifPresent(participants::delete);
    }

    @Test
    @DisplayName("registers the caller with 201 and answers 200 with the same body on repeat")
    void registersOnceAndIsIdempotentAfterwards() throws IOException {
        HttpResponse<String> created = register();
        assertThat(created.code()).isEqualTo(HttpStatus.CREATED.getCode());
        assertThat(body(created))
                .containsEntry("identifier", identifier)
                .as("`participants` is required, so it is published even while empty")
                .containsEntry("participants", List.of());

        HttpResponse<String> repeated = register();
        assertThat(repeated.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(body(repeated)).isEqualTo(body(created));
    }

    @Test
    @DisplayName("answers 200 for a user another authenticated route already provisioned")
    void answersOkForAUserProvisionedJustInTime() throws IOException {
        HttpResponse<String> justInTime = exchange(Route.CURRENT.request(), userToken());
        assertThat(justInTime.code()).isEqualTo(HttpStatus.OK.getCode());

        HttpResponse<String> registered = register();
        assertThat(registered.code())
                .as("the row already existed, so this request did not create it")
                .isEqualTo(HttpStatus.OK.getCode());
        assertThat(body(registered)).isEqualTo(body(justInTime));
    }

    @Test
    @DisplayName("returns the caller's record with the participants they are linked to")
    void returnsTheCallersRecordWithItsParticipantLinks() throws IOException {
        register();
        link(storedUser().orElseThrow());

        Map<String, Object> record = body(exchange(Route.CURRENT.request(), userToken()));
        assertThat(record).containsEntry("identifier", identifier);
        assertThat(record.get("participants")).isEqualTo(List.of(LINKED_PARTICIPANT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    @DisplayName("publishes no authentication material")
    void publishesNoAuthenticationMaterial(Route route) throws IOException {
        HttpResponse<String> response = exchange(route.request(), userToken());

        assertThat(body(response).keySet()).isSubsetOf(ALLOWED_PROPERTIES);
        assertThat(response.body().toLowerCase(Locale.ROOT))
                .as("no property name or value may read as authentication material")
                .doesNotContain(AUTHENTICATION_MATERIAL.toArray(String[]::new));
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("refusedRoles")
    @DisplayName("refuses a token that authenticates but holds no USER role")
    void refusesANonUserRole(Route route, RealmPrincipal role) {
        assertThat(exchange(route.request(), KeycloakTestResource.accessToken(role)).code())
                .isEqualTo(HttpStatus.FORBIDDEN.getCode());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    @DisplayName("refuses an unauthenticated request")
    void refusesAnUnauthenticatedRequest(Route route) {
        assertThat(exchange(route.request(), null).code())
                .isEqualTo(HttpStatus.UNAUTHORIZED.getCode());
    }

    private HttpResponse<String> register() {
        return exchange(Route.REGISTER.request(), userToken());
    }

    /** The {@code users} row the realm's user token maps to, once this service has created it. */
    private Optional<User> storedUser() {
        return users.findByIdentifier(identifier);
    }

    private static String userToken() {
        return KeycloakTestResource.accessToken(RealmPrincipal.USER);
    }

    /** Commits a link row directly, because no endpoint creates one until a later step. */
    private void link(User user) {
        Participant participant = participants.findByIdentifier(LINKED_PARTICIPANT).orElseThrow();
        links.save(new UserParticipant(user.getId(), participant.getId(), null));
    }

    private Map<String, Object> body(HttpResponse<String> response) throws IOException {
        return json.readValue(response.body(), Argument.mapOf(String.class, Object.class));
    }

    /** Issues the request, returning a refusal rather than throwing it. */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> exchange(MutableHttpRequest<?> request, String token) {
        MutableHttpRequest<?> sent = token == null ? request : request.bearerAuth(token);
        try {
            return client.toBlocking().exchange(sent, String.class);
        } catch (HttpClientResponseException e) {
            return (HttpResponse<String>) e.getResponse();
        }
    }
}
