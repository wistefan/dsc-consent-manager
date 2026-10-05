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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * Exercises {@code GET /users/{identifier}} and {@code POST /users/search} end to end against a
 * real Keycloak and a real PostgreSQL.
 *
 * <p>What is under test is caller scoping: the same stored population has to look different to a
 * participant and to the catalog, and a participant must not be able to widen its own view through
 * the request body. The fixture is seeded through the repositories because no endpoint creates a
 * link until a later step.
 */
@MicronautTest(transactional = false)
@DisplayName("User lookup and search")
class UserDirectoryIT extends KeycloakAndPostgresTestResource {

    /** Participant the realm's {@code PARTICIPANT} token acts as. */
    private static final String CALLER_PARTICIPANT = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    /** A second participant, so "scoped out" is distinguishable from "absent". */
    private static final String OTHER_PARTICIPANT = "urn:test:participant:directory-other";

    private static final String LINKED_USER = "urn:test:user:linked";

    private static final String OTHER_USER = "urn:test:user:other";

    private static final String SHARED_USER = "urn:test:user:shared";

    /**
     * A user whose global identifier collides with the self route's literal path segment, which
     * {@code GET /users/me} claims; reachable through search alone.
     */
    private static final String ME_USER = "me";

    /** Names nobody, so a lookup for it is the genuinely-absent case. */
    private static final String ABSENT_USER = "urn:test:user:absent";

    /** Shared by every seeded user but {@link #ME_USER}; stored in differing cases on purpose. */
    private static final String SHARED_EMAIL = "ada@example.org";

    private static final List<String> SEEDED_USERS =
            List.of(LINKED_USER, OTHER_USER, SHARED_USER, ME_USER);

    /** Longest a cold Keycloak container may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    /** Problem-detail member naming the requested URL; it necessarily differs per request. */
    private static final String PROBLEM_INSTANCE = "instance";

    private static final String PATH_SEARCH = "/users/search";

    private static final String PATH_ME = "/users/me";

    @Inject
    @Client("/")
    HttpClient client;

    @Inject ObjectMapper json;

    @Inject IdentityProviderRegistry registry;

    @Inject UserRepository users;

    @Inject ParticipantRepository participants;

    @Inject UserParticipantRepository links;

    /** Who may read which user, as the lookup route answers it. */
    static Stream<Arguments> lookups() {
        return Stream.of(
                Arguments.of(RealmPrincipal.PARTICIPANT, LINKED_USER, HttpStatus.OK),
                Arguments.of(RealmPrincipal.PARTICIPANT, SHARED_USER, HttpStatus.OK),
                Arguments.of(RealmPrincipal.PARTICIPANT, OTHER_USER, HttpStatus.NOT_FOUND),
                Arguments.of(RealmPrincipal.PARTICIPANT, ABSENT_USER, HttpStatus.NOT_FOUND),
                Arguments.of(RealmPrincipal.CATALOG, LINKED_USER, HttpStatus.OK),
                Arguments.of(RealmPrincipal.CATALOG, OTHER_USER, HttpStatus.OK),
                Arguments.of(RealmPrincipal.CATALOG, ABSENT_USER, HttpStatus.NOT_FOUND));
    }

    @BeforeEach
    void seedThePopulation() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        removeCommittedRows();
        Participant caller = participant(CALLER_PARTICIPANT, "Directory Caller Ltd");
        Participant other = participant(OTHER_PARTICIPANT, "Directory Other Ltd");
        link(user(LINKED_USER, SHARED_EMAIL.toUpperCase(Locale.ROOT)), caller);
        link(user(OTHER_USER, SHARED_EMAIL), other);
        User shared = user(SHARED_USER, "Ada@Example.Org");
        link(shared, caller);
        link(shared, other);
        link(user(ME_USER, null), caller);
    }

    /** Rows committed by these tests outlive the transaction, so they are removed by hand. */
    @AfterAll
    void removeCommittedRows() {
        SEEDED_USERS.forEach(
                identifier ->
                        users.findByIdentifier(identifier)
                                .ifPresent(
                                        user -> {
                                            links.findByIdUserId(user.getId())
                                                    .forEach(links::delete);
                                            users.delete(user);
                                        }));
        participants.findByIdentifier(OTHER_PARTICIPANT).ifPresent(participants::delete);
        participants.findByIdentifier(CALLER_PARTICIPANT).ifPresent(participants::delete);
    }

    @ParameterizedTest(name = "{0} looking up {1} gets {2}")
    @MethodSource("lookups")
    @DisplayName("a lookup answers only within the caller's scope")
    void lookupIsScopedToTheCaller(RealmPrincipal caller, String identifier, HttpStatus expected)
            throws IOException {
        HttpResponse<String> response = lookup(identifier, token(caller));

        assertThat(response.code()).isEqualTo(expected.getCode());
        if (expected == HttpStatus.OK) {
            assertThat(body(response)).containsEntry("identifier", identifier);
        }
    }

    @Test
    @DisplayName("a user outside the caller's scope is refused exactly like an absent one")
    void anUnscopedUserIsIndistinguishableFromAnAbsentOne() throws IOException {
        HttpResponse<String> unscoped = lookup(OTHER_USER, token(RealmPrincipal.PARTICIPANT));
        HttpResponse<String> absent = lookup(ABSENT_USER, token(RealmPrincipal.PARTICIPANT));

        assertThat(unscoped.code()).isEqualTo(HttpStatus.NOT_FOUND.getCode());
        assertThat(withoutInstance(unscoped))
                .as("a body that differed would confirm the identifier names a registered user")
                .isEqualTo(withoutInstance(absent));
        assertThat(body(unscoped))
                .as("the one differing member only echoes the URL the caller itself requested")
                .containsEntry(PROBLEM_INSTANCE, "/users/" + OTHER_USER);
    }

    @Test
    @DisplayName("an exact identifier search hits the one user it names")
    void searchByIdentifierHitsExactly() throws IOException {
        assertThat(identifiersFound(Map.of("identifier", LINKED_USER), RealmPrincipal.PARTICIPANT))
                .containsExactly(LINKED_USER);
        assertThat(
                        identifiersFound(
                                Map.of("identifier", LINKED_USER.toUpperCase(Locale.ROOT)),
                                RealmPrincipal.PARTICIPANT))
                .as("identifier matching is case-sensitive")
                .isEmpty();
    }

    @Test
    @DisplayName("an email search is case-insensitive and may name several users")
    void searchByEmailIsCaseInsensitiveAndNotUnique() throws IOException {
        Map<String, Object> criteria = Map.of("email", "AdA@ExAmPlE.oRg");

        assertThat(identifiersFound(criteria, RealmPrincipal.CATALOG))
                .containsExactly(LINKED_USER, OTHER_USER, SHARED_USER);
        assertThat(identifiersFound(criteria, RealmPrincipal.PARTICIPANT))
                .as("the same address, seen through the caller's own links")
                .containsExactly(LINKED_USER, SHARED_USER);
    }

    @Test
    @DisplayName(
            "naming another participant narrows a participant's result rather than widening it")
    void aParticipantCannotWidenItsOwnScope() throws IOException {
        Map<String, Object> criteria = Map.of("participantIdentifier", OTHER_PARTICIPANT);

        assertThat(identifiersFound(criteria, RealmPrincipal.PARTICIPANT))
                .as("only the user linked to both, never the one linked to the other alone")
                .containsExactly(SHARED_USER);
        assertThat(identifiersFound(criteria, RealmPrincipal.CATALOG))
                .as("the catalog reads across participants")
                .containsExactly(OTHER_USER, SHARED_USER);
    }

    @Test
    @DisplayName("a search naming no criterion is refused rather than listing everybody")
    void anEmptySearchIsRejected() throws IOException {
        HttpResponse<String> response =
                exchange(
                        HttpRequest.POST(PATH_SEARCH, Map.of()), token(RealmPrincipal.PARTICIPANT));

        assertThat(response.code()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
        assertThat(body(response)).containsEntry("status", HttpStatus.BAD_REQUEST.getCode());
    }

    @Test
    @DisplayName("the self route claims /users/me, leaving that identifier to search alone")
    void theSelfRouteWinsOverTheLookupRoute() throws IOException {
        // The requested URL is literally the lookup path for a user identified as `me`.
        HttpResponse<String> self =
                exchange(HttpRequest.GET(PATH_ME), token(RealmPrincipal.PARTICIPANT));
        assertThat(self.code())
                .as("the USER-only self route matched, so a participant is refused outright")
                .isEqualTo(HttpStatus.FORBIDDEN.getCode());

        assertThat(identifiersFound(Map.of("identifier", ME_USER), RealmPrincipal.PARTICIPANT))
                .as("search is the documented way to reach such a user")
                .containsExactly(ME_USER);
    }

    /**
     * Which <em>other</em> participants a user is affiliated with is not a participant's to learn.
     *
     * <p>Asserted on both read routes and in both directions, so neither a widening of the mapper
     * nor a narrowing of the catalog view can pass unnoticed.
     */
    @ParameterizedTest(name = "{0} reading the shared user is told about {1}")
    @MethodSource("disclosedLinks")
    @DisplayName("the participant links a caller is told about are scoped to that caller")
    void participantLinksAreScopedToTheCaller(RealmPrincipal caller, List<String> expected)
            throws IOException {
        HttpResponse<String> looked = lookup(SHARED_USER, token(caller));
        assertThat(looked.code()).isEqualTo(HttpStatus.OK.getCode());

        assertThat(participantsOf(body(looked))).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(participantsOf(usersFound(Map.of("identifier", SHARED_USER), caller).get(0)))
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    static Stream<Arguments> disclosedLinks() {
        return Stream.of(
                Arguments.of(RealmPrincipal.PARTICIPANT, List.of(CALLER_PARTICIPANT)),
                Arguments.of(
                        RealmPrincipal.CATALOG, List.of(CALLER_PARTICIPANT, OTHER_PARTICIPANT)));
    }

    private HttpResponse<String> lookup(String identifier, String token) {
        return exchange(HttpRequest.GET("/users/" + identifier), token);
    }

    /** The identifiers a search returns, in the order the service published them. */
    private List<String> identifiersFound(Map<String, Object> criteria, RealmPrincipal caller)
            throws IOException {
        return usersFound(criteria, caller).stream()
                .map(found -> (String) found.get("identifier"))
                .toList();
    }

    /** The whole user representations a search returns, for assertions beyond the identifier. */
    private List<Map<String, Object>> usersFound(
            Map<String, Object> criteria, RealmPrincipal caller) throws IOException {
        HttpResponse<String> response =
                exchange(HttpRequest.POST(PATH_SEARCH, criteria), token(caller));
        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        return json.readValue(
                response.body(), Argument.listOf(Argument.mapOf(String.class, Object.class)));
    }

    /** The {@code participants} array of a representation, as the caller received it. */
    @SuppressWarnings("unchecked")
    private static List<String> participantsOf(Map<String, Object> representation) {
        return (List<String>) representation.get("participants");
    }

    private static String token(RealmPrincipal caller) {
        return KeycloakTestResource.accessToken(caller);
    }

    private Participant participant(String identifier, String legalName) {
        return participants
                .findByIdentifier(identifier)
                .orElseGet(
                        () ->
                                participants.save(
                                        new Participant(
                                                identifier,
                                                legalName,
                                                null,
                                                null,
                                                Map.of(),
                                                null)));
    }

    private User user(String identifier, String email) {
        return users.save(new User(identifier, email, null, null));
    }

    private void link(User user, Participant participant) {
        links.save(new UserParticipant(user.getId(), participant.getId(), null));
    }

    /** The problem detail minus the member that merely echoes the requested URL. */
    private Map<String, Object> withoutInstance(HttpResponse<String> response) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>(body(response));
        problem.remove(PROBLEM_INSTANCE);
        return problem;
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
