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
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 * The authorization matrix over the operations this service actually publishes: for every specified
 * route, the status each kind of caller receives, with tokens issued by a real Keycloak.
 *
 * <p>{@code KeycloakRoleMatrixIT} proves the same thing about synthetic probe routes, which answers
 * whether the mechanism works but not whether it was wired onto the published routes. This suite is
 * the one that fails when an operation is left on {@code IS_AUTHENTICATED}, served to the wrong
 * role, or reachable without a token, and {@link #everySpecifiedOperationIsCovered()} makes
 * forgetting to add a route here a failure rather than a silence.
 *
 * <p>The cell for the role a route serves carries that route's own success status rather than a
 * blanket "not refused": a route reached by the right caller has to answer what it specifies, and
 * the fixture is reseeded per case so the status is the same whichever case runs first.
 */
@MicronautTest(transactional = false)
@DisplayName("Role matrix over the published endpoints")
class EndpointRoleMatrixIT extends KeycloakAndPostgresTestResource {

    /** Participant the realm's {@code PARTICIPANT} token acts as. */
    private static final String CALLER_PARTICIPANT = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    private static final String PARTICIPANT_LEGAL_NAME = "Role Matrix Participant Ltd";

    /**
     * User seeded and linked to the caller participant, so every read route has something to
     * answer.
     */
    private static final String LINKED_USER = "urn:test:user:role-matrix";

    /**
     * The caller participant's own label for its link, present so the {@code PATCH} updates one.
     */
    private static final String LOCAL_IDENTIFIER = "role-matrix-local";

    private static final String PATH_API_STATUS = "/api-status";

    private static final String PATH_USERS = "/users";

    private static final String PATH_ME = PATH_USERS + "/me";

    private static final String PATH_REGISTER = PATH_USERS + "/register";

    private static final String PATH_SEARCH = PATH_USERS + "/search";

    private static final String PATH_PARTICIPANT_USERS = "/participants/me/users";

    private static final String PATH_BULK = PATH_PARTICIPANT_USERS + "/bulk";

    /** Body of a request to an operation that specifies none; the route ignores it. */
    private static final String NO_BODY = "";

    /** Media type every refusal carries, per RFC 7807. */
    private static final String PROBLEM_JSON = "application/problem+json";

    /** Longest a cold Keycloak container may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    @Inject
    @Client("/")
    HttpClient client;

    @Inject IdentityProviderRegistry registry;

    @Inject UserRepository users;

    @Inject ParticipantRepository participants;

    @Inject UserParticipantRepository links;

    /**
     * The {@code USER} caller's global identifier, resolved once because reading it costs a token.
     */
    private String userIdentifier;

    /**
     * The callers in the matrix: one per role the realm grants, plus the two the service refuses.
     *
     * <p>{@code OUTSIDER} holds a realm role the trust list maps to nothing, which is the
     * authenticated-but-unauthorized case; {@code ANONYMOUS} sends no token at all.
     */
    enum Caller {
        USER(RealmPrincipal.USER),
        PARTICIPANT(RealmPrincipal.PARTICIPANT),
        CATALOG(RealmPrincipal.CATALOG),
        OUTSIDER(RealmPrincipal.OUTSIDER),
        ANONYMOUS(null);

        private final RealmPrincipal realmPrincipal;

        Caller(RealmPrincipal realmPrincipal) {
            this.realmPrincipal = realmPrincipal;
        }

        /** A freshly issued token for this caller, or {@code null} when it sends none. */
        String token() {
            return realmPrincipal == null ? null : KeycloakTestResource.accessToken(realmPrincipal);
        }
    }

    /**
     * Every operation of the specification, named as the specification names it and paired with a
     * request that is valid for the role the operation serves.
     *
     * <p>The name is {@code <method> <path>} with the path template intact, which is what {@link
     * #everySpecifiedOperationIsCovered()} matches against the specification. A parameterized test
     * may not take the request factory directly - Micronaut's JUnit extension claims a {@code
     * Supplier} parameter as a bean to inject - so the factory travels inside this enum.
     */
    enum Endpoint {
        API_STATUS("get " + PATH_API_STATUS, () -> HttpRequest.GET(PATH_API_STATUS)),
        REGISTER_USER("post " + PATH_REGISTER, () -> HttpRequest.POST(PATH_REGISTER, NO_BODY)),
        CURRENT_USER("get " + PATH_ME, () -> HttpRequest.GET(PATH_ME)),
        ERASE_USER("delete " + PATH_ME, () -> HttpRequest.DELETE(PATH_ME)),
        SEARCH_USERS(
                "post " + PATH_SEARCH,
                () -> HttpRequest.POST(PATH_SEARCH, Map.of("identifier", LINKED_USER))),
        LOOKUP_USER(
                "get " + PATH_USERS + "/{identifier}",
                () -> HttpRequest.GET(PATH_USERS + "/" + LINKED_USER)),
        REGISTER_PARTICIPANT_USER(
                "post " + PATH_PARTICIPANT_USERS,
                () -> HttpRequest.POST(PATH_PARTICIPANT_USERS, Map.of("identifier", LINKED_USER))),
        BULK_REGISTER_PARTICIPANT_USERS(
                "post " + PATH_BULK,
                () ->
                        HttpRequest.POST(
                                PATH_BULK,
                                Map.of("users", List.of(Map.of("identifier", LINKED_USER))))),
        UPDATE_LINK(
                "patch " + PATH_PARTICIPANT_USERS + "/{identifier}",
                () ->
                        HttpRequest.PATCH(
                                PATH_PARTICIPANT_USERS + "/" + LINKED_USER,
                                Map.of("localIdentifier", LOCAL_IDENTIFIER))),
        UNLINK(
                "delete " + PATH_PARTICIPANT_USERS + "/{identifier}",
                () -> HttpRequest.DELETE(PATH_PARTICIPANT_USERS + "/" + LINKED_USER));

        private final String specification;
        private final Supplier<MutableHttpRequest<?>> factory;

        Endpoint(String specification, Supplier<MutableHttpRequest<?>> factory) {
            this.specification = specification;
            this.factory = factory;
        }

        /** A fresh request, so no case mutates another's. */
        MutableHttpRequest<?> request() {
            return factory.get();
        }

        @Override
        public String toString() {
            return specification;
        }
    }

    /**
     * The matrix itself: one row per operation, one column per caller.
     *
     * <p>A caller holding no role of this service is {@code 403} rather than {@code 401}: its
     * signature was good and its issuer is trusted, so asking it to authenticate again would be a
     * lie. {@code /api-status} stays {@code 200} throughout, including for a caller that names
     * nobody, because a reachability check must not depend on a credential.
     */
    static Stream<Arguments> roleMatrix() {
        HttpStatus ok = HttpStatus.OK;
        HttpStatus created = HttpStatus.CREATED;
        HttpStatus perEntry = HttpStatus.MULTI_STATUS;
        HttpStatus emptied = HttpStatus.NO_CONTENT;
        HttpStatus denied = HttpStatus.FORBIDDEN;
        HttpStatus challenged = HttpStatus.UNAUTHORIZED;
        return Stream.of(
                        row(Endpoint.API_STATUS, ok, ok, ok, ok, ok),
                        row(Endpoint.REGISTER_USER, created, denied, denied, denied, challenged),
                        row(Endpoint.CURRENT_USER, ok, denied, denied, denied, challenged),
                        row(Endpoint.ERASE_USER, ok, denied, denied, denied, challenged),
                        row(Endpoint.SEARCH_USERS, denied, ok, ok, denied, challenged),
                        row(Endpoint.LOOKUP_USER, denied, ok, ok, denied, challenged),
                        row(
                                Endpoint.REGISTER_PARTICIPANT_USER,
                                denied,
                                ok,
                                denied,
                                denied,
                                challenged),
                        row(
                                Endpoint.BULK_REGISTER_PARTICIPANT_USERS,
                                denied,
                                perEntry,
                                denied,
                                denied,
                                challenged),
                        row(Endpoint.UPDATE_LINK, denied, ok, denied, denied, challenged),
                        row(Endpoint.UNLINK, denied, emptied, denied, denied, challenged))
                .flatMap(row -> row);
    }

    /** Expands one operation's row into one case per caller. */
    private static Stream<Arguments> row(
            Endpoint endpoint,
            HttpStatus user,
            HttpStatus participant,
            HttpStatus catalog,
            HttpStatus outsider,
            HttpStatus anonymous) {
        return Stream.of(
                Arguments.of(endpoint, Caller.USER, user),
                Arguments.of(endpoint, Caller.PARTICIPANT, participant),
                Arguments.of(endpoint, Caller.CATALOG, catalog),
                Arguments.of(endpoint, Caller.OUTSIDER, outsider),
                Arguments.of(endpoint, Caller.ANONYMOUS, anonymous));
    }

    /** Re-establishes the fixture per case, since several of the routes under test consume it. */
    @BeforeEach
    void seedTheFixture() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        userIdentifier = KeycloakTestResource.subject(Caller.USER.token());
        removeCommittedRows();
        Participant caller =
                participants
                        .findByIdentifier(CALLER_PARTICIPANT)
                        .orElseGet(
                                () ->
                                        participants.save(
                                                new Participant(
                                                        CALLER_PARTICIPANT,
                                                        PARTICIPANT_LEGAL_NAME,
                                                        null,
                                                        null,
                                                        Map.of(),
                                                        null)));
        User linked = users.save(new User(LINKED_USER, null, null, null));
        links.save(new UserParticipant(linked.getId(), caller.getId(), LOCAL_IDENTIFIER));
    }

    /** Rows committed by these tests outlive the transaction, so they are removed by hand. */
    @AfterAll
    void removeCommittedRows() {
        Stream.of(LINKED_USER, userIdentifier)
                .forEach(
                        identifier ->
                                users.findByIdentifier(identifier)
                                        .ifPresent(
                                                user -> {
                                                    links.findByIdUserId(user.getId())
                                                            .forEach(links::delete);
                                                    users.delete(user);
                                                }));
        participants.findByIdentifier(CALLER_PARTICIPANT).ifPresent(participants::delete);
    }

    @ParameterizedTest(name = "{1} on {0} is {2}")
    @MethodSource("roleMatrix")
    @DisplayName("each caller reaches exactly the published routes it is entitled to")
    void roleMatrixHolds(Endpoint endpoint, Caller caller, HttpStatus expected) {
        HttpResponse<String> response = exchange(endpoint.request(), caller.token());

        assertThat(response.code()).isEqualTo(expected.getCode());
        if (expected == HttpStatus.FORBIDDEN || expected == HttpStatus.UNAUTHORIZED) {
            assertThat(response.getContentType())
                    .as("a refusal is a problem detail, whatever the route would have returned")
                    .hasValueSatisfying(
                            type -> assertThat(type.toString()).startsWith(PROBLEM_JSON));
        }
    }

    @Test
    @DisplayName("every operation the specification declares has a row in the matrix")
    void everySpecifiedOperationIsCovered() {
        List<String> specified =
                SpecSecurityConsistencyTest.specOperations()
                        .map(operation -> operation.httpMethod() + " " + operation.path())
                        .toList();

        assertThat(Stream.of(Endpoint.values()).map(Endpoint::toString).toList())
                .as(
                        "an operation with no row here has no end-to-end authorization assertion at"
                                + " all, which is the failure this suite exists to make impossible")
                .containsExactlyInAnyOrderElementsOf(specified);
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
