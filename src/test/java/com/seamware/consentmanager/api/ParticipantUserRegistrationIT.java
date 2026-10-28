package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

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
import com.seamware.consentmanager.support.Race;
import com.seamware.consentmanager.support.RawSql;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code POST /participants/me/users} end to end: guards the outcome and status per starting state
 * (including a race), an adoption rewriting attributes, and the body naming a foreign participant.
 */
@MicronautTest(transactional = false)
@DisplayName("Participant user registration")
class ParticipantUserRegistrationIT extends KeycloakAndPostgresTestResource {

    private static final String PATH = "/participants/me/users";

    /** Participant the realm's {@code PARTICIPANT} token acts as. */
    private static final String CALLER_PARTICIPANT = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    /** Registered but never the caller; naming it in the body must still link the caller. */
    private static final String FOREIGN_PARTICIPANT = "urn:test:participant:registration-foreign";

    /** Names no participant at all, so the body cannot be read as a valid instruction either. */
    private static final String UNKNOWN_PARTICIPANT = "urn:test:participant:registration-nobody";

    private static final String NEW_USER = "urn:test:user:registration-new";

    private static final String EXISTING_USER = "urn:test:user:registration-existing";

    private static final String ADOPTED_USER = "urn:test:user:registration-adopted";

    private static final String REPEATED_USER = "urn:test:user:registration-repeated";

    private static final String SMUGGLED_USER = "urn:test:user:registration-smuggled";

    private static final String MAPPED_USER = "urn:test:user:registration-mapped";

    private static final String RACED_USER = "urn:test:user:registration-raced";

    /** Parallel registrations of one identifier; enough to make losing the insert routine. */
    private static final int CONCURRENT_CALLERS = 8;

    /** The outcome, and the only one answered {@code 201}, of a registration that made the user. */
    private static final String CREATED_OUTCOME = "CREATED";

    /** Every scenario but the race sends exactly one registration. */
    private static final int SINGLE_CALLER = 1;

    /** Rows {@code users} holds for one identifier, which its unique key bounds at one. */
    private static final String COUNT_USERS = "SELECT count(*) FROM users WHERE identifier = ?";

    /** The caller's links to one identifier, which the composite key bounds at one. */
    private static final String COUNT_CALLER_LINKS =
            "SELECT count(*) FROM user_participants l"
                    + " JOIN users u ON u.id = l.user_id"
                    + " JOIN participants p ON p.id = l.participant_id"
                    + " WHERE u.identifier = ? AND p.identifier = ?";

    /** Attributes an earlier registrant recorded, which a later one must not be able to rewrite. */
    private static final Map<String, Object> ORIGINAL_ATTRIBUTES =
            Map.of("email", "ada@example.org", "firstName", "Ada", "lastName", "Lovelace");

    /** Attributes a second registrant supplies, every one of them different from the above. */
    private static final Map<String, Object> OVERWRITING_ATTRIBUTES =
            Map.of("email", "grace@example.org", "firstName", "Grace", "lastName", "Hopper");

    /** Every optional property with a distinct value, so a transposed mapping shows. */
    private static final Map<String, Object> EVERY_ATTRIBUTE =
            Map.of(
                    "localIdentifier", "local-10427",
                    "email", "alan@example.org",
                    "firstName", "Alan",
                    "lastName", "Turing");

    /** The {@code maxLength} the schema puts on the human-scale properties. */
    private static final int MAX_FIELD_LENGTH = 255;

    /** The looser bound {@code localIdentifier} carries, so that a DID or a URI fits. */
    private static final int MAX_LOCAL_IDENTIFIER_LENGTH = 2048;

    /** Longest a cold Keycloak may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    @Inject
    @Client("/")
    HttpClient client;

    @Inject ObjectMapper json;

    @Inject IdentityProviderRegistry registry;

    @Inject UserRepository users;

    @Inject ParticipantRepository participants;

    @Inject UserParticipantRepository links;

    /** How the directory is primed, and what that makes the registration under test answer. */
    private enum Scenario {

        /** Nobody carries the identifier: the user is created by this very request. */
        UNKNOWN_USER(NEW_USER, HttpStatus.CREATED, CREATED_OUTCOME, SINGLE_CALLER) {
            @Override
            void prime(ParticipantUserRegistrationIT it) {
                // Deliberately empty: the unprimed directory is the starting state.
            }
        },

        /** The user exists but belongs to nobody the caller knows: this request adopts it. */
        EXISTING_UNLINKED_USER(ADOPTED_USER, HttpStatus.OK, "LINKED", SINGLE_CALLER) {
            @Override
            void prime(ParticipantUserRegistrationIT it) {
                it.users.save(new User(ADOPTED_USER, null, null, null));
            }
        },

        /** The caller registered the user already, so a repeat changes nothing published. */
        ALREADY_LINKED_USER(REPEATED_USER, HttpStatus.OK, "ALREADY_LINKED", SINGLE_CALLER) {
            @Override
            void prime(ParticipantUserRegistrationIT it) {
                it.post(Map.of("identifier", REPEATED_USER));
            }
        },

        /** Nobody carries the identifier and many register it at once: one creates, none fails. */
        CONCURRENT(RACED_USER, HttpStatus.CREATED, CREATED_OUTCOME, CONCURRENT_CALLERS) {
            @Override
            void prime(ParticipantUserRegistrationIT it) {
                // Deliberately empty: the race itself is the starting state.
            }
        };

        private final String identifier;

        private final HttpStatus expectedStatus;

        private final String expectedOutcome;

        private final int callers;

        Scenario(
                String identifier, HttpStatus expectedStatus, String expectedOutcome, int callers) {
            this.identifier = identifier;
            this.expectedStatus = expectedStatus;
            this.expectedOutcome = expectedOutcome;
            this.callers = callers;
        }

        abstract void prime(ParticipantUserRegistrationIT it);
    }

    static Stream<Arguments> scenarios() {
        return Stream.of(Scenario.values()).map(Arguments::of);
    }

    @BeforeEach
    void awaitTheRealmAndSeedTheDirectory() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        participant(CALLER_PARTICIPANT, "Registration Caller Ltd");
        participant(FOREIGN_PARTICIPANT, "Registration Foreign Ltd");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    @DisplayName("each starting state reaches its own outcome, and the status agrees with it")
    void eachStartingStateReachesItsOwnOutcome(Scenario scenario) throws IOException {
        scenario.prime(this);

        List<Race.Outcome<HttpResponse<String>>> raced =
                Race.run(scenario.callers, () -> post(Map.of("identifier", scenario.identifier)));

        assertThat(raced).as("no caller's request threw").allMatch(Race.Outcome::succeeded);
        List<HttpResponse<String>> responses = raced.stream().map(Race.Outcome::value).toList();
        List<String> outcomes = new ArrayList<>(responses.size());
        for (HttpResponse<String> response : responses) {
            String outcome = (String) body(response).get("outcome");
            outcomes.add(outcome);
            assertThat(response.code())
                    .as("every caller succeeds, and only the creator is told 201")
                    .isEqualTo(statusOf(outcome).getCode());
            assertThat(userOf(response)).containsEntry("identifier", scenario.identifier);
            assertThat(participantsOf(userOf(response))).containsExactly(CALLER_PARTICIPANT);
        }
        assertThat(outcomes)
                .as("exactly one caller reaches the scenario's outcome")
                .filteredOn(scenario.expectedOutcome::equals)
                .hasSize(1);
        assertThat(statusOf(scenario.expectedOutcome).getCode())
                .as("the scenario's status agrees with the route's rule")
                .isEqualTo(scenario.expectedStatus.getCode());
        assertThat(RawSql.count(COUNT_USERS, scenario.identifier))
                .as("PostgreSQL holds one user however many callers raced")
                .isOne();
        assertThat(RawSql.count(COUNT_CALLER_LINKS, scenario.identifier, CALLER_PARTICIPANT))
                .as("and one link from the caller to it")
                .isOne();
    }

    /** The status the route answers an outcome with: only a creation is {@code 201}. */
    private static HttpStatus statusOf(String outcome) {
        return CREATED_OUTCOME.equals(outcome) ? HttpStatus.CREATED : HttpStatus.OK;
    }

    @Test
    @DisplayName("adopting another participant's user leaves the attributes it recorded alone")
    void attributesOfAnExistingUserAreNotOverwritten() throws IOException {
        User seeded =
                users.save(
                        new User(
                                EXISTING_USER,
                                (String) ORIGINAL_ATTRIBUTES.get("email"),
                                (String) ORIGINAL_ATTRIBUTES.get("firstName"),
                                (String) ORIGINAL_ATTRIBUTES.get("lastName")));
        links.save(new UserParticipant(seeded.getId(), participantId(FOREIGN_PARTICIPANT), null));

        HttpResponse<String> response = post(registration(EXISTING_USER, OVERWRITING_ATTRIBUTES));

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(body(response)).containsEntry("outcome", "LINKED");
        assertThat(userOf(response))
                .as("a later registrant may not rewrite what the record's creator asserted")
                .containsAllEntriesOf(ORIGINAL_ATTRIBUTES);
        assertThat(storedAttributesOf(EXISTING_USER))
                .as("and the representation is not merely hiding a changed row")
                .containsAllEntriesOf(ORIGINAL_ATTRIBUTES);
        assertThat(participantsOf(userOf(response)))
                .as("the caller is told about its own link and no other")
                .containsExactly(CALLER_PARTICIPANT);
    }

    @Test
    @DisplayName("every optional property of the body reaches the column it names")
    void everyOptionalPropertyIsStoredWhereItBelongs() throws IOException {
        HttpResponse<String> response = post(registration(MAPPED_USER, EVERY_ATTRIBUTE));

        assertThat(response.code()).isEqualTo(HttpStatus.CREATED.getCode());
        assertThat(storedAttributesOf(MAPPED_USER))
                .as("the attributes are mapped onto the user row, none transposed")
                .containsOnly(
                        entry("email", EVERY_ATTRIBUTE.get("email")),
                        entry("firstName", EVERY_ATTRIBUTE.get("firstName")),
                        entry("lastName", EVERY_ATTRIBUTE.get("lastName")));
        assertThat(localIdentifierOf(MAPPED_USER))
                .as("localIdentifier belongs on the caller's link, and has no read path yet")
                .isEqualTo(EVERY_ATTRIBUTE.get("localIdentifier"));
    }

    @ParameterizedTest(name = "{0} longer than its maximum of {1}")
    @MethodSource("boundedProperties")
    @DisplayName("a property longer than its maximum is refused before anything is written")
    void anOverlongPropertyIsRefused(String property, int maximum) {
        // Seeded first so an overlong `identifier` can replace the valid one.
        Map<String, Object> body = new LinkedHashMap<>(Map.of("identifier", MAPPED_USER));
        body.put(property, "x".repeat(maximum + 1));

        assertThat(post(body).code()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
        assertThat(users.findByIdentifier(MAPPED_USER)).isEmpty();
    }

    /** Every bounded property with its schema's bound; {@code localIdentifier} is looser. */
    static Stream<Arguments> boundedProperties() {
        return Stream.of(
                Arguments.of("identifier", MAX_FIELD_LENGTH),
                Arguments.of("localIdentifier", MAX_LOCAL_IDENTIFIER_LENGTH),
                Arguments.of("firstName", MAX_FIELD_LENGTH),
                Arguments.of("lastName", MAX_FIELD_LENGTH));
    }

    @ParameterizedTest(name = "participantIdentifier = {0}")
    @ValueSource(strings = {FOREIGN_PARTICIPANT, UNKNOWN_PARTICIPANT, CALLER_PARTICIPANT})
    @DisplayName("a participantIdentifier in the body is ignored rather than rejected")
    void aSmuggledParticipantIdentifierChangesNothing(String smuggled) throws IOException {
        Map<String, Object> body =
                Map.of("identifier", SMUGGLED_USER, "participantIdentifier", smuggled);

        HttpResponse<String> response = post(body);

        assertThat(response.code())
                .as("an unknown property is ignored, so a 400 here is a rejection of it")
                .isEqualTo(HttpStatus.CREATED.getCode());
        assertThat(participantsOf(userOf(response)))
                .as("the link follows the token, never the body")
                .containsExactly(CALLER_PARTICIPANT);
        assertThat(linkedParticipantIdsOf(SMUGGLED_USER))
                .containsExactly(participantId(CALLER_PARTICIPANT));
    }

    @ParameterizedTest(name = "identifier = \"{0}\"")
    @ValueSource(strings = {"", " ", "\t  \n"})
    @DisplayName("a blank identifier is refused before anything is written")
    void aBlankIdentifierIsRefused(String blank) throws IOException {
        HttpResponse<String> response = post(Map.of("identifier", blank));

        assertThat(response.code()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
        assertThat(users.findByIdentifier(blank)).isEmpty();
    }

    @Test
    @DisplayName("a body naming no identifier at all is refused")
    void aMissingIdentifierIsRefused() {
        assertThat(post(Map.of("email", "ada@example.org")).code())
                .isEqualTo(HttpStatus.BAD_REQUEST.getCode());
    }

    @ParameterizedTest(name = "{0} gets {1}")
    @MethodSource("callers")
    @DisplayName("only a participant may register users")
    void onlyAParticipantMayRegister(RealmPrincipal caller, HttpStatus expected) {
        HttpResponse<String> response =
                exchange(
                        HttpRequest.POST(PATH, Map.of("identifier", NEW_USER)),
                        caller == null ? null : KeycloakTestResource.accessToken(caller));

        assertThat(response.code()).isEqualTo(expected.getCode());
    }

    /** Who may reach the route at all, before the registration rule gets a say. */
    static Stream<Arguments> callers() {
        return Stream.of(
                Arguments.of(RealmPrincipal.USER, HttpStatus.FORBIDDEN),
                Arguments.of(RealmPrincipal.CATALOG, HttpStatus.FORBIDDEN),
                Arguments.of(RealmPrincipal.OUTSIDER, HttpStatus.FORBIDDEN),
                Arguments.of(null, HttpStatus.UNAUTHORIZED));
    }

    private static Map<String, Object> registration(
            String identifier, Map<String, Object> attributes) {
        Map<String, Object> body = new LinkedHashMap<>(attributes);
        body.put("identifier", identifier);
        return body;
    }

    private HttpResponse<String> post(Map<String, Object> body) {
        return exchange(
                HttpRequest.POST(PATH, body),
                KeycloakTestResource.accessToken(RealmPrincipal.PARTICIPANT));
    }

    /** The {@code user} member of a registration result. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> userOf(HttpResponse<String> response) throws IOException {
        return (Map<String, Object>) body(response).get("user");
    }

    @SuppressWarnings("unchecked")
    private static List<String> participantsOf(Map<String, Object> representation) {
        return (List<String>) representation.get("participants");
    }

    /** The stored row's attributes, to tell a stale representation from an unchanged record. */
    private Map<String, Object> storedAttributesOf(String identifier) {
        User stored = users.findByIdentifier(identifier).orElseThrow();
        return Map.of(
                "email", stored.getEmail(),
                "firstName", stored.getFirstName(),
                "lastName", stored.getLastName());
    }

    /** The {@code local_identifier} the caller's own link carries, which no route reads yet. */
    private String localIdentifierOf(String identifier) {
        UUID caller = participantId(CALLER_PARTICIPANT);
        return links
                .findByIdUserId(users.findByIdentifier(identifier).orElseThrow().getId())
                .stream()
                .filter(link -> caller.equals(link.getId().getParticipantId()))
                .map(UserParticipant::getLocalIdentifier)
                .findFirst()
                .orElseThrow();
    }

    private List<UUID> linkedParticipantIdsOf(String identifier) {
        return links
                .findByIdUserId(users.findByIdentifier(identifier).orElseThrow().getId())
                .stream()
                .map(link -> link.getId().getParticipantId())
                .toList();
    }

    private UUID participantId(String identifier) {
        return participants.findByIdentifier(identifier).orElseThrow().getId();
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
