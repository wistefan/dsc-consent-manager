package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.domain.Participant;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Directory read surface end to end: guards page boundaries, size clamping, and a listing that
 * hides deregistered rows a lookup still resolves. Needs the {@code participants} table to itself.
 */
@MicronautTest(transactional = false)
@DisplayName("Participant directory")
class ParticipantDirectoryIT extends KeycloakAndPostgresTestResource {

    private static final String PATH = "/participants";

    /** Seeded participants in directory order, by identifier ascending. */
    private static final String URI_SHAPED =
            "https://directory.example.org/participants/uri-shaped";

    private static final String FIRST_DID = "urn:test:participant:directory-a";

    private static final String SECOND_DID = "urn:test:participant:directory-b";

    private static final String THIRD_DID = "urn:test:participant:directory-c";

    private static final String FOURTH_DID = "urn:test:participant:directory-e";

    /** Five active participants in directory order, so no page size below divides them evenly. */
    private static final List<String> ACTIVE =
            List.of(URI_SHAPED, FIRST_DID, SECOND_DID, THIRD_DID, FOURTH_DID);

    /** Deregistered, so the listing hides it while a lookup still resolves it. */
    private static final String DEREGISTERED = "urn:test:participant:directory-d";

    /** Named by nobody, so a lookup for it is the 404 case. */
    private static final String UNKNOWN = "urn:test:participant:directory-absent";

    /** Every identifier this suite seeds, and the only rows it may remove. */
    private static final List<String> SEEDED =
            Stream.concat(ACTIVE.stream(), Stream.of(DEREGISTERED)).toList();

    private static final String LEGAL_NAME_PREFIX = "Directory Clinic ";

    private static final String SELF_DESCRIPTION_URI_PREFIX = "https://directory.example.org/sd/";

    private static final String EMAIL_SUFFIX = "@directory.example.org";

    /** Page size that fits the whole seeded directory, so totals are read in one request. */
    private static final int WHOLE_DIRECTORY_SIZE = 10;

    /** Page size that leaves the last of the five active participants on a page of its own. */
    private static final int SPLIT_SIZE = 2;

    /** A second size, dividing the directory differently, so no boundary is a coincidence. */
    private static final int WIDER_SPLIT_SIZE = 3;

    private static final String PROBLEM_JSON = "application/problem+json";

    /** Longest a cold Keycloak may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    @Inject
    @Client("/")
    HttpClient client;

    @Inject ObjectMapper json;

    @Inject IdentityProviderRegistry registry;

    @Inject ParticipantRepository participants;

    @Inject UserRepository users;

    @Inject UserParticipantRepository links;

    @Inject ConsentManagerConfiguration.Participants paging;

    /** The identifier of the realm's user, resolved once because reading it costs a token. */
    private String userIdentifier;

    @BeforeEach
    void awaitTheRealmAndSeedTheDirectory() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        userIdentifier = KeycloakTestResource.subject(token(RealmPrincipal.USER));
        removeCommittedRows();
        assertThat(participants.count())
                .as(
                        "this suite asserts page boundaries and totals over the whole directory, so"
                                + " it needs the table to itself; a row another suite left behind has to"
                                + " be removed there, since consents and privacy notices restrict the"
                                + " deletion of a participant they name")
                .isZero();
        ACTIVE.forEach(identifier -> participants.save(participant(identifier)));
        Participant deregistered = participant(DEREGISTERED);
        deregistered.setDeregisteredAt(Instant.now());
        participants.save(deregistered);
    }

    /** Removes only the rows seeded here; another suite's may be held by restricting FKs. */
    @AfterAll
    void removeCommittedRows() {
        users.findByIdentifier(userIdentifier)
                .ifPresent(
                        user -> {
                            links.findByIdUserId(user.getId()).forEach(links::delete);
                            users.delete(user);
                        });
        SEEDED.forEach(
                identifier ->
                        participants.findByIdentifier(identifier).ifPresent(participants::delete));
    }

    @Test
    @DisplayName("a page publishes each active participant's self-description, in identifier order")
    void aPagePublishesEachActiveParticipant() throws IOException {
        Map<String, Object> page = body(get(PATH + "?size=" + WHOLE_DIRECTORY_SIZE));

        assertThat(number(page, "page")).isZero();
        assertThat(number(page, "size")).isEqualTo(WHOLE_DIRECTORY_SIZE);
        assertThat(number(page, "totalElements")).isEqualTo(ACTIVE.size());
        assertThat(number(page, "totalPages")).isEqualTo(1);
        assertThat(identifiers(page)).containsExactlyElementsOf(ACTIVE);
        assertThat(records(page).getFirst())
                .containsEntry("identifier", URI_SHAPED)
                .containsEntry("legalName", legalName(URI_SHAPED))
                .containsEntry("selfDescriptionUri", selfDescriptionUri(URI_SHAPED))
                .containsEntry("email", email(URI_SHAPED));
    }

    @ParameterizedTest(name = "page {0} of size {1} carries {2}")
    @MethodSource("pageBoundaries")
    @DisplayName("page boundaries divide the directory without repeating or skipping a record")
    void pageBoundariesDivideTheDirectory(int number, int size, List<String> expected)
            throws IOException {
        Map<String, Object> page = body(get(PATH + "?page=" + number + "&size=" + size));

        assertThat(number(page, "page")).isEqualTo(number);
        assertThat(number(page, "size")).isEqualTo(size);
        assertThat(number(page, "totalElements")).isEqualTo(ACTIVE.size());
        assertThat(number(page, "totalPages")).isEqualTo(Math.ceilDiv(ACTIVE.size(), size));
        assertThat(identifiers(page)).containsExactlyElementsOf(expected);
    }

    static Stream<Arguments> pageBoundaries() {
        return Stream.of(
                Arguments.of(0, SPLIT_SIZE, List.of(URI_SHAPED, FIRST_DID)),
                Arguments.of(1, SPLIT_SIZE, List.of(SECOND_DID, THIRD_DID)),
                // A partial last page, which truncating division would miss.
                Arguments.of(2, SPLIT_SIZE, List.of(FOURTH_DID)),
                Arguments.of(0, WIDER_SPLIT_SIZE, List.of(URI_SHAPED, FIRST_DID, SECOND_DID)),
                Arguments.of(1, WIDER_SPLIT_SIZE, List.of(THIRD_DID, FOURTH_DID)),
                // Past the end is an empty page, not a 404.
                Arguments.of(3, SPLIT_SIZE, List.of()));
    }

    @Test
    @DisplayName("a size above the configured ceiling is clamped to it and answered 200")
    void anOversizedPageIsClampedRatherThanRefused() throws IOException {
        int oversized = paging.getPageMaxSize() + 1;

        HttpResponse<String> response = get(PATH + "?size=" + oversized);

        assertThat(response.code())
                .as("the specification declares no maximum, so the ceiling clamps, never refuses")
                .isEqualTo(HttpStatus.OK.getCode());
        assertThat(number(body(response), "size"))
                .as("the response reports the size actually applied")
                .isEqualTo(paging.getPageMaxSize());
    }

    @Test
    @DisplayName("an omitted size applies the configured default")
    void anOmittedSizeAppliesTheConfiguredDefault() throws IOException {
        assertThat(number(body(get(PATH)), "size")).isEqualTo(paging.getPageDefaultSize());
    }

    @ParameterizedTest(name = "{0} is refused")
    @MethodSource("invalidPaging")
    @DisplayName("a page or size outside the declared minima is refused")
    void invalidPagingIsRefused(String description, String query) {
        HttpResponse<String> response = get(PATH + query);

        assertThat(response.code()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
        assertThat(response.getContentType().orElseThrow().toString()).startsWith(PROBLEM_JSON);
    }

    static Stream<Arguments> invalidPaging() {
        return Stream.of(
                Arguments.of("a size of zero", "?size=0"),
                Arguments.of("a negative page", "?page=-1"));
    }

    @Test
    @DisplayName("a lookup resolves a DID-shaped identifier and 404s on an unknown one")
    void aLookupResolvesAnIdentifier() throws IOException {
        HttpResponse<String> found = get(PATH + "/" + encoded(FIRST_DID));
        assertThat(found.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(body(found))
                .containsEntry("identifier", FIRST_DID)
                .containsEntry("legalName", legalName(FIRST_DID));

        HttpResponse<String> absent = get(PATH + "/" + encoded(UNKNOWN));
        assertThat(absent.code()).isEqualTo(HttpStatus.NOT_FOUND.getCode());
        assertThat(absent.getContentType().orElseThrow().toString()).startsWith(PROBLEM_JSON);
    }

    /** The filter answers about exactly the record named, in any state, and never refuses. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("filteredLookups")
    @DisplayName("the identifier filter answers about exactly the record it names")
    void theFilterAnswersAboutTheRecordItNames(
            String description, String identifier, List<String> expected) throws IOException {
        HttpResponse<String> response = get(PATH + "?identifier=" + encoded(identifier));

        assertThat(response.code())
                .as("the filter is a view of the directory, so a miss is an empty page, not a 404")
                .isEqualTo(HttpStatus.OK.getCode());

        Map<String, Object> page = body(response);
        assertThat(identifiers(page)).containsExactlyElementsOf(expected);
        assertThat(number(page, "totalElements")).isEqualTo(expected.size());
        assertThat(number(page, "totalPages")).isEqualTo(expected.isEmpty() ? 0 : 1);
    }

    static Stream<Arguments> filteredLookups() {
        return Stream.of(
                Arguments.of(
                        "an identifier containing a slash is matched whole",
                        URI_SHAPED,
                        List.of(URI_SHAPED)),
                Arguments.of(
                        "a deregistered participant the listing hides still resolves",
                        DEREGISTERED,
                        List.of(DEREGISTERED)),
                Arguments.of("an identifier naming nobody matches nothing", UNKNOWN, List.of()));
    }

    @Test
    @DisplayName("a deregistered participant is hidden from the listing but still resolvable")
    void aDeregisteredParticipantIsHiddenButResolvable() throws IOException {
        assertThat(identifiers(body(get(PATH + "?size=" + WHOLE_DIRECTORY_SIZE))))
                .doesNotContain(DEREGISTERED);

        HttpResponse<String> looked = get(PATH + "/" + encoded(DEREGISTERED));
        assertThat(looked.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(body(looked))
                .as("a consent retained past deregistration still names this participant")
                .containsEntry("identifier", DEREGISTERED)
                .hasEntrySatisfying("deregisteredAt", at -> assertThat(at).isNotNull());
    }

    @ParameterizedTest(name = "{0} reads the directory")
    @EnumSource(
            value = RealmPrincipal.class,
            names = {"USER", "PARTICIPANT", "CATALOG"})
    @DisplayName("every role of this service may read the directory")
    void everyRoleMayRead(RealmPrincipal caller) {
        assertThat(exchange(HttpRequest.GET(PATH), token(caller)).code())
                .isEqualTo(HttpStatus.OK.getCode());
    }

    /** The participant-token cases here all run unregistered; this pins that property. */
    @Test
    @DisplayName("a participant that has not registered yet may still read the directory")
    void anUnregisteredParticipantMayRead() {
        assertThat(participants.existsByIdentifier(KeycloakTestResource.PARTICIPANT_IDENTIFIER))
                .isFalse();

        assertThat(get(PATH).code()).isEqualTo(HttpStatus.OK.getCode());
    }

    /** Checked via the repository, since reading the user over HTTP would provision it too. */
    @Test
    @DisplayName("a directory read by an unseen user provisions that user just in time")
    void aDirectoryReadProvisionsTheCallingUser() {
        assertThat(users.findByIdentifier(userIdentifier)).isEmpty();

        assertThat(exchange(HttpRequest.GET(PATH), token(RealmPrincipal.USER)).code())
                .isEqualTo(HttpStatus.OK.getCode());

        assertThat(users.findByIdentifier(userIdentifier)).isPresent();
    }

    private Participant participant(String identifier) {
        return new Participant(
                identifier,
                legalName(identifier),
                selfDescriptionUri(identifier),
                email(identifier),
                Map.of("consentNotification", SELF_DESCRIPTION_URI_PREFIX + "notify"),
                Map.of("registrationNumber", "DE123456789"));
    }

    private static String legalName(String identifier) {
        return LEGAL_NAME_PREFIX + identifier;
    }

    private static String selfDescriptionUri(String identifier) {
        return SELF_DESCRIPTION_URI_PREFIX + encoded(identifier);
    }

    private static String email(String identifier) {
        return encoded(identifier) + EMAIL_SUFFIX;
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> records(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("content");
    }

    /** A page's numeric field, read width-agnostically: {@code totalElements} is a 64-bit one. */
    private static int number(Map<String, Object> page, String field) {
        return ((Number) page.get(field)).intValue();
    }

    private static List<Object> identifiers(Map<String, Object> page) {
        return records(page).stream().map(record -> record.get("identifier")).toList();
    }

    private Map<String, Object> body(HttpResponse<String> response) throws IOException {
        return json.readValue(response.body(), Argument.mapOf(String.class, Object.class));
    }

    private static String token(RealmPrincipal caller) {
        return KeycloakTestResource.accessToken(caller);
    }

    /** Issues the request as the realm's participant, which no test here registers. */
    private HttpResponse<String> get(String uri) {
        return exchange(HttpRequest.GET(uri), token(RealmPrincipal.PARTICIPANT));
    }

    /** Issues the request, returning a refusal rather than throwing. */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> exchange(MutableHttpRequest<?> request, String token) {
        try {
            return client.toBlocking().exchange(request.bearerAuth(token), String.class);
        } catch (HttpClientResponseException e) {
            return (HttpResponse<String>) e.getResponse();
        }
    }
}
