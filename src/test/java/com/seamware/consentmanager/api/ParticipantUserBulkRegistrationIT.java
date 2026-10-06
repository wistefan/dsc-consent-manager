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
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises {@code POST /participants/me/users/bulk} end to end against a real Keycloak and a real
 * PostgreSQL.
 *
 * <p>What is under test is partial success: that a batch is answered {@code 207} whatever mixture
 * of outcomes it reaches, that results come back one per entry in request order, that the summary
 * agrees with them, and above all that a rejected entry in the middle of the batch stops neither
 * the entries after it nor the writes of the entries before it. The two request-level refusals - a
 * batch above the configured maximum and a batch violating the published property bounds - are
 * asserted to write nothing at all.
 */
@MicronautTest(transactional = false)
@DisplayName("Participant bulk user registration")
class ParticipantUserBulkRegistrationIT extends KeycloakAndPostgresTestResource {

    private static final String PATH = "/participants/me/users/bulk";

    /** Participant the realm's {@code PARTICIPANT} token acts as. */
    private static final String CALLER_PARTICIPANT = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    /** Registered, but never the caller: it owns the user the batch adopts. */
    private static final String FOREIGN_PARTICIPANT = "urn:test:participant:bulk-foreign";

    private static final String NEW_USER = "urn:test:user:bulk-new";

    private static final String ADOPTED_USER = "urn:test:user:bulk-adopted";

    private static final String REPEATED_USER = "urn:test:user:bulk-repeated";

    /** Identifiers an over-long or malformed batch names, none of which may survive it. */
    private static final String REFUSED_USER_PREFIX = "urn:test:user:bulk-refused-";

    private static final List<String> SEEDED_USERS = List.of(NEW_USER, ADOPTED_USER, REPEATED_USER);

    /**
     * The batch maximum this test's context runs with, small enough that an over-long batch is a
     * handful of entries rather than the deployment default.
     */
    private static final int BULK_MAX_SIZE = 4;

    private static final String BULK_MAX_SIZE_PROPERTY = "consent-manager.users.bulk-max-size";

    /** The {@code maxLength} the entry schema puts on {@code identifier}. */
    private static final int MAX_FIELD_LENGTH = 255;

    /** Longest a cold Keycloak container may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    @Inject
    @Client("/")
    HttpClient client;

    @Inject ObjectMapper json;

    @Inject IdentityProviderRegistry registry;

    @Inject UserRepository users;

    @Inject ParticipantRepository participants;

    @Inject UserParticipantRepository links;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new LinkedHashMap<>(super.getProperties());
        properties.put(BULK_MAX_SIZE_PROPERTY, String.valueOf(BULK_MAX_SIZE));
        return properties;
    }

    /**
     * One entry of the mixed batch: where it sits, what it asks for, and what it must come back
     * with. The rejected entry sits in the middle on purpose - an entry that writes is sent after
     * it, so an entry wrapped in a transaction that the rejection aborts fails here rather than
     * passing by accident.
     */
    private enum Entry {

        /** The user exists and belongs to a foreign participant: this entry adopts it. */
        EXISTING_UNLINKED(0, ADOPTED_USER, "LINKED"),

        /** Names no identifier, so it is unregisterable while the rest of the batch is not. */
        BLANK_IDENTIFIER(1, "   ", "REJECTED"),

        /** Nobody carries the identifier, and it is sent after the rejection on purpose. */
        UNKNOWN_USER(2, NEW_USER, "CREATED"),

        /** The caller registered it already, so the batch changes nothing published about it. */
        ALREADY_LINKED(3, REPEATED_USER, "ALREADY_LINKED");

        private final int position;

        private final String identifier;

        private final String expectedOutcome;

        Entry(int position, String identifier, String expectedOutcome) {
            this.position = position;
            this.identifier = identifier;
            this.expectedOutcome = expectedOutcome;
        }
    }

    static Stream<Arguments> entries() {
        return Stream.of(Entry.values()).map(Arguments::of);
    }

    @BeforeEach
    void awaitTheRealmAndEmptyTheDirectory() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        removeCommittedRows();
        participant(CALLER_PARTICIPANT, "Bulk Caller Ltd");
        participant(FOREIGN_PARTICIPANT, "Bulk Foreign Ltd");
    }

    /** Rows committed by these tests outlive the transaction, so they are removed by hand. */
    @AfterAll
    void removeCommittedRows() {
        Stream.concat(
                        SEEDED_USERS.stream(),
                        IntStream.rangeClosed(0, BULK_MAX_SIZE).mapToObj(this::refusedUser))
                .forEach(
                        identifier ->
                                users.findByIdentifier(identifier)
                                        .ifPresent(
                                                user -> {
                                                    links.findByIdUserId(user.getId())
                                                            .forEach(links::delete);
                                                    users.delete(user);
                                                }));
        participants.findByIdentifier(FOREIGN_PARTICIPANT).ifPresent(participants::delete);
        participants.findByIdentifier(CALLER_PARTICIPANT).ifPresent(participants::delete);
    }

    @ParameterizedTest(name = "{0} is answered {2}")
    @MethodSource("entries")
    @DisplayName("every entry reaches its own outcome, in the position it was sent")
    void everyEntryReachesItsOwnOutcome(Entry entry) throws IOException {
        primeTheMixedBatch();

        Map<String, Object> result = resultsOf(postMixedBatch()).get(entry.position);

        assertThat(result).containsEntry("outcome", entry.expectedOutcome);
        assertThat(result)
                .as("the identifier is echoed so a caller can cross-check the position")
                .containsEntry("identifier", entry.identifier);
        if ("REJECTED".equals(entry.expectedOutcome)) {
            assertThat(result.get("reason")).asString().isNotBlank();
        } else {
            assertThat(result.get("reason"))
                    .as("a reason is published for rejections alone")
                    .isNull();
        }
    }

    @Test
    @DisplayName("a rejected entry stops neither the entries before it nor those after it")
    void aRejectedEntryIsolatesNothingButItself() throws IOException {
        primeTheMixedBatch();

        HttpResponse<String> response = postMixedBatch();

        assertThat(response.code()).isEqualTo(HttpStatus.MULTI_STATUS.getCode());
        assertThat(outcomesOf(response))
                .as("one result per entry, in the order the entries were sent")
                .containsExactly("LINKED", "REJECTED", "CREATED", "ALREADY_LINKED");
        assertThat(linkedParticipantIdsOf(NEW_USER))
                .as("the entry sent after the rejection was committed, not rolled back with it")
                .containsExactly(participantId(CALLER_PARTICIPANT));
        assertThat(linkedParticipantIdsOf(ADOPTED_USER))
                .as("and the entry sent before it kept its write as well")
                .contains(participantId(CALLER_PARTICIPANT));
    }

    @Test
    @DisplayName("the summary counts what the results say, outcome by outcome")
    void theSummaryAgreesWithTheResults() throws IOException {
        primeTheMixedBatch();

        Map<String, Object> summary = summaryOf(postMixedBatch());

        assertThat(summary)
                .containsOnly(
                        entry("total", Entry.values().length),
                        entry("created", 1),
                        entry("linked", 1),
                        entry("alreadyLinked", 1),
                        entry("rejected", 1));
    }

    @Test
    @DisplayName("an empty batch is a success that does nothing")
    void anEmptyBatchIsAccepted() throws IOException {
        HttpResponse<String> response = post(List.of());

        assertThat(response.code()).isEqualTo(HttpStatus.MULTI_STATUS.getCode());
        assertThat(resultsOf(response)).isEmpty();
        assertThat(summaryOf(response))
                .containsOnly(
                        entry("total", 0),
                        entry("created", 0),
                        entry("linked", 0),
                        entry("alreadyLinked", 0),
                        entry("rejected", 0));
    }

    @Test
    @DisplayName("a batch above the maximum is refused whole, before anything is written")
    void anOversizedBatchWritesNothing() {
        List<Map<String, Object>> oversized =
                IntStream.rangeClosed(0, BULK_MAX_SIZE)
                        .mapToObj(index -> Map.<String, Object>of("identifier", refusedUser(index)))
                        .toList();

        assertThat(post(oversized).code()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
        assertThat(oversized)
                .as("not one entry of a refused batch may have landed")
                .allSatisfy(
                        entry ->
                                assertThat(users.findByIdentifier((String) entry.get("identifier")))
                                        .isEmpty());
    }

    @Test
    @DisplayName("an entry breaking a published bound refuses the batch rather than itself")
    void anOverlongPropertyRefusesTheWholeBatch() {
        List<Map<String, Object>> batch =
                List.of(
                        Map.of("identifier", refusedUser(0)),
                        Map.of("identifier", "x".repeat(MAX_FIELD_LENGTH + 1)));

        assertThat(post(batch).code()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
        assertThat(users.findByIdentifier(refusedUser(0)))
                .as("the well-formed entry of a malformed batch is not written either")
                .isEmpty();
    }

    @ParameterizedTest(name = "{0} gets {1}")
    @MethodSource("callers")
    @DisplayName("only a participant may register users in bulk")
    void onlyAParticipantMayRegisterInBulk(RealmPrincipal caller, HttpStatus expected) {
        HttpResponse<String> response =
                exchange(
                        HttpRequest.POST(PATH, batch(List.of(Map.of("identifier", NEW_USER)))),
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

    /** Seeds the starting states the mixed batch's entries expect to find. */
    private void primeTheMixedBatch() {
        User adopted = users.save(new User(ADOPTED_USER, null, null, null));
        links.save(new UserParticipant(adopted.getId(), participantId(FOREIGN_PARTICIPANT), null));
        User repeated = users.save(new User(REPEATED_USER, null, null, null));
        links.save(new UserParticipant(repeated.getId(), participantId(CALLER_PARTICIPANT), null));
    }

    private HttpResponse<String> postMixedBatch() {
        return post(
                Stream.of(Entry.values())
                        .map(entry -> Map.<String, Object>of("identifier", entry.identifier))
                        .toList());
    }

    private HttpResponse<String> post(List<Map<String, Object>> entries) {
        return exchange(
                HttpRequest.POST(PATH, batch(entries)),
                KeycloakTestResource.accessToken(RealmPrincipal.PARTICIPANT));
    }

    private static Map<String, Object> batch(List<Map<String, Object>> entries) {
        return Map.of("users", entries);
    }

    private String refusedUser(int index) {
        return REFUSED_USER_PREFIX + index;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> resultsOf(HttpResponse<String> response) throws IOException {
        return (List<Map<String, Object>>) body(response).get("results");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summaryOf(HttpResponse<String> response) throws IOException {
        return (Map<String, Object>) body(response).get("summary");
    }

    private List<String> outcomesOf(HttpResponse<String> response) throws IOException {
        return resultsOf(response).stream().map(result -> (String) result.get("outcome")).toList();
    }

    private Map<String, Object> body(HttpResponse<String> response) throws IOException {
        return json.readValue(response.body(), Argument.mapOf(String.class, Object.class));
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
