package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentSnapshot;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.domain.UserParticipant;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserParticipantRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.security.IdentityProviderRegistry;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.KeycloakAndPostgresTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource.RealmPrincipal;
import io.micronaut.core.type.Argument;
import io.micronaut.data.model.Pageable;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exercises {@code PATCH} and {@code DELETE /participants/me/users/{identifier}} end to end against
 * a real Keycloak and a real PostgreSQL.
 *
 * <p>What is under test is the blast radius of both operations: that the update reaches the
 * caller's own link and nothing else, that the unlink ends an affiliation without erasing the
 * person or any consent, that a granted consent the caller is party to blocks it while a revoked
 * one and a stranger's granted one do not, and that a caller holding no link is told {@code 404}
 * rather than {@code 403}.
 */
@MicronautTest(transactional = false)
@DisplayName("Participant user link maintenance")
class ParticipantUserLinkIT extends KeycloakAndPostgresTestResource {

    /** Participant the realm's {@code PARTICIPANT} token acts as. */
    private static final String CALLER_PARTICIPANT = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    /** Registered and linked, but never the caller: its link must survive the caller's unlink. */
    private static final String FOREIGN_PARTICIPANT = "urn:test:participant:link-foreign";

    /** A third party, so a consent between two others can be told from one naming the caller. */
    private static final String THIRD_PARTICIPANT = "urn:test:participant:link-third";

    private static final String LINKED_USER = "urn:test:user:link-linked";

    private static final String UNLINKED_USER = "urn:test:user:link-unlinked";

    private static final String CONSENTING_USER = "urn:test:user:link-consenting";

    private static final String UNKNOWN_USER = "urn:test:user:link-nobody";

    private static final List<String> SEEDED_USERS =
            List.of(LINKED_USER, UNLINKED_USER, CONSENTING_USER);

    private static final List<String> SEEDED_PARTICIPANTS =
            List.of(CALLER_PARTICIPANT, FOREIGN_PARTICIPANT, THIRD_PARTICIPANT);

    /** The local identifier the caller's link is seeded with. */
    private static final String ORIGINAL_LOCAL_IDENTIFIER = "patient-10427";

    /** What the update writes in its place, deliberately unlike the seeded one. */
    private static final String NEW_LOCAL_IDENTIFIER = "patient-77012";

    /** Attributes seeded on the user row, which no link operation may rewrite. */
    private static final String SEEDED_EMAIL = "ada@example.org";

    private static final String SEEDED_FIRST_NAME = "Ada";

    private static final String SEEDED_LAST_NAME = "Lovelace";

    /** Contract URI of the privacy notice every seeded consent is frozen from. */
    private static final String NOTICE_CONTRACT_URI = "urn:test:contract:link";

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

    @Inject ConsentRepository consents;

    @Inject PrivacyNoticeRepository notices;

    @BeforeEach
    void awaitTheRealmAndSeedTheDirectory() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        removeCommittedRows();
        SEEDED_PARTICIPANTS.forEach(identifier -> participant(identifier));
        seedUser(LINKED_USER, CALLER_PARTICIPANT, FOREIGN_PARTICIPANT);
        seedUser(UNLINKED_USER, FOREIGN_PARTICIPANT);
        seedUser(CONSENTING_USER, CALLER_PARTICIPANT, FOREIGN_PARTICIPANT);
    }

    /** Rows committed by these tests outlive the transaction, so they are removed by hand. */
    @AfterAll
    void removeCommittedRows() {
        SEEDED_USERS.forEach(
                identifier ->
                        users.findByIdentifier(identifier)
                                .ifPresent(
                                        user -> {
                                            consents.findByUserId(user.getId(), Pageable.UNPAGED)
                                                    .forEach(consents::delete);
                                            links.findByIdUserId(user.getId())
                                                    .forEach(links::delete);
                                            users.delete(user);
                                        }));
        List<Participant> seeded =
                SEEDED_PARTICIPANTS.stream()
                        .map(participants::findByIdentifier)
                        .flatMap(Optional::stream)
                        .toList();
        // A notice names a participant on both sides, so every notice has to go before the first
        // participant does - otherwise the consumer side of a notice still standing blocks it.
        seeded.forEach(
                stored ->
                        notices.findByProviderIdAndArchivedAtIsNull(stored.getId())
                                .forEach(notices::delete));
        seeded.forEach(participants::delete);
    }

    @Test
    @DisplayName("the update answers with the new local identifier and stores it")
    void theUpdateReportsAndStoresTheNewLocalIdentifier() throws IOException {
        HttpResponse<String> response = patch(LINKED_USER, NEW_LOCAL_IDENTIFIER);

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(body(response))
                .containsEntry("identifier", LINKED_USER)
                .containsEntry("localIdentifier", NEW_LOCAL_IDENTIFIER)
                .containsKey("linkedAt");
        assertThat(localIdentifierOf(LINKED_USER, CALLER_PARTICIPANT))
                .as("the response is not merely echoing the request back")
                .isEqualTo(NEW_LOCAL_IDENTIFIER);
    }

    @Test
    @DisplayName("an omitted local identifier clears the stored one")
    void anOmittedLocalIdentifierClearsTheStoredOne() throws IOException {
        HttpResponse<String> response = patch(LINKED_USER, null);

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(body(response))
                .as("an absent value is published as an absent property, never as null")
                .doesNotContainKey("localIdentifier");
        assertThat(localIdentifierOf(LINKED_USER, CALLER_PARTICIPANT)).isNull();
    }

    @Test
    @DisplayName("the update reaches the caller's link alone")
    void theUpdateLeavesTheUserAndOtherLinksAlone() {
        patch(LINKED_USER, NEW_LOCAL_IDENTIFIER);

        assertThat(localIdentifierOf(LINKED_USER, FOREIGN_PARTICIPANT))
                .as("another participant's local identifier is none of the caller's business")
                .isEqualTo(ORIGINAL_LOCAL_IDENTIFIER);
        User stored = users.findByIdentifier(LINKED_USER).orElseThrow();
        assertThat(stored.getEmail()).isEqualTo(SEEDED_EMAIL);
        assertThat(stored.getFirstName()).isEqualTo(SEEDED_FIRST_NAME);
        assertThat(stored.getLastName()).isEqualTo(SEEDED_LAST_NAME);
    }

    @ParameterizedTest(name = "{1} {0}")
    @MethodSource("unheldLinks")
    @DisplayName("a link the caller does not hold is a 404, whichever operation asks for it")
    void anUnheldLinkIsNotFound(String identifier, String method) {
        HttpResponse<String> response =
                "PATCH".equals(method)
                        ? patch(identifier, NEW_LOCAL_IDENTIFIER)
                        : delete(identifier);

        assertThat(response.code()).isEqualTo(HttpStatus.NOT_FOUND.getCode());
    }

    /**
     * A user linked only to somebody else and an identifier naming nobody at all: both answer 404,
     * which is what keeps the first from confirming that the identifier is registered.
     */
    static Stream<Arguments> unheldLinks() {
        return Stream.of(UNLINKED_USER, UNKNOWN_USER)
                .flatMap(
                        identifier ->
                                Stream.of(
                                        Arguments.of(identifier, "PATCH"),
                                        Arguments.of(identifier, "DELETE")));
    }

    @Test
    @DisplayName("the unlink removes the caller's link and nothing else")
    void theUnlinkEndsTheAffiliationOnly() {
        UUID userId = users.findByIdentifier(LINKED_USER).orElseThrow().getId();

        assertThat(delete(LINKED_USER).code()).isEqualTo(HttpStatus.NO_CONTENT.getCode());

        assertThat(users.findByIdentifier(LINKED_USER))
                .as("an unlink ends an affiliation; it does not erase a person")
                .isPresent();
        assertThat(linkedParticipantsOf(userId)).containsExactly(FOREIGN_PARTICIPANT);
    }

    @Test
    @DisplayName("the unlink leaves every consent of the user standing")
    void theUnlinkLeavesConsentsStanding() {
        UUID userId = users.findByIdentifier(CONSENTING_USER).orElseThrow().getId();
        grant(CONSENTING_USER, ConsentStatus.REVOKED, CALLER_PARTICIPANT, THIRD_PARTICIPANT);

        assertThat(delete(CONSENTING_USER).code()).isEqualTo(HttpStatus.NO_CONTENT.getCode());

        assertThat(consents.findByUserId(userId, Pageable.UNPAGED)).hasSize(1);
    }

    @Test
    @DisplayName("a second unlink finds no link and answers 404")
    void aSecondUnlinkIsNotFound() {
        assertThat(delete(LINKED_USER).code()).isEqualTo(HttpStatus.NO_CONTENT.getCode());

        assertThat(delete(LINKED_USER).code()).isEqualTo(HttpStatus.NOT_FOUND.getCode());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("consentScenarios")
    @DisplayName("only a granted consent the caller is party to blocks the unlink")
    void onlyAGrantedConsentNamingTheCallerBlocksTheUnlink(
            String name,
            ConsentStatus status,
            String provider,
            String consumer,
            HttpStatus expected) {
        grant(CONSENTING_USER, status, provider, consumer);

        HttpResponse<String> response = delete(CONSENTING_USER);

        assertThat(response.code()).isEqualTo(expected.getCode());
        assertThat(links.existsByIdUserIdAndIdParticipantId(userId(CONSENTING_USER), callerId()))
                .as("a refused unlink must not have deleted the link on its way out")
                .isEqualTo(expected == HttpStatus.CONFLICT);
    }

    /** Each consent a user may be party to, and whether it stands in the way of the unlink. */
    static Stream<Arguments> consentScenarios() {
        return Stream.of(
                Arguments.of(
                        "granted, caller is the provider",
                        ConsentStatus.GRANTED,
                        CALLER_PARTICIPANT,
                        THIRD_PARTICIPANT,
                        HttpStatus.CONFLICT),
                Arguments.of(
                        "granted, caller is the consumer",
                        ConsentStatus.GRANTED,
                        THIRD_PARTICIPANT,
                        CALLER_PARTICIPANT,
                        HttpStatus.CONFLICT),
                Arguments.of(
                        "revoked, caller is the provider",
                        ConsentStatus.REVOKED,
                        CALLER_PARTICIPANT,
                        THIRD_PARTICIPANT,
                        HttpStatus.NO_CONTENT),
                Arguments.of(
                        "granted between two others",
                        ConsentStatus.GRANTED,
                        FOREIGN_PARTICIPANT,
                        THIRD_PARTICIPANT,
                        HttpStatus.NO_CONTENT));
    }

    @ParameterizedTest(name = "{0} gets {2} on {1}")
    @MethodSource("callers")
    @DisplayName("only a participant may maintain a link")
    void onlyAParticipantMayMaintainALink(
            RealmPrincipal caller, String method, HttpStatus expected) {
        String token = caller == null ? null : KeycloakTestResource.accessToken(caller);
        MutableHttpRequest<?> request =
                "PATCH".equals(method)
                        ? HttpRequest.PATCH(path(LINKED_USER), update(NEW_LOCAL_IDENTIFIER))
                        : HttpRequest.DELETE(path(LINKED_USER));

        assertThat(exchange(request, token).code()).isEqualTo(expected.getCode());
    }

    /** Who may reach either route at all, before the link rules get a say. */
    static Stream<Arguments> callers() {
        Map<RealmPrincipal, HttpStatus> refused =
                new LinkedHashMap<>(
                        Map.of(
                                RealmPrincipal.USER, HttpStatus.FORBIDDEN,
                                RealmPrincipal.CATALOG, HttpStatus.FORBIDDEN,
                                RealmPrincipal.OUTSIDER, HttpStatus.FORBIDDEN));
        refused.put(null, HttpStatus.UNAUTHORIZED);
        return refused.entrySet().stream()
                .flatMap(
                        caller ->
                                Stream.of("PATCH", "DELETE")
                                        .map(
                                                method ->
                                                        Arguments.of(
                                                                caller.getKey(),
                                                                method,
                                                                caller.getValue())));
    }

    private HttpResponse<String> patch(String identifier, String localIdentifier) {
        return exchange(
                HttpRequest.PATCH(path(identifier), update(localIdentifier)),
                KeycloakTestResource.accessToken(RealmPrincipal.PARTICIPANT));
    }

    private HttpResponse<String> delete(String identifier) {
        return exchange(
                HttpRequest.DELETE(path(identifier)),
                KeycloakTestResource.accessToken(RealmPrincipal.PARTICIPANT));
    }

    /** The update body; a {@code null} local identifier is sent as an omitted property. */
    private static Map<String, Object> update(String localIdentifier) {
        return localIdentifier == null ? Map.of() : Map.of("localIdentifier", localIdentifier);
    }

    private static String path(String identifier) {
        return "/participants/me/users/" + URLEncoder.encode(identifier, StandardCharsets.UTF_8);
    }

    private Map<String, Object> body(HttpResponse<String> response) throws IOException {
        return json.readValue(response.body(), Argument.mapOf(String.class, Object.class));
    }

    /** Seeds the user with its attributes and one link per named participant. */
    private void seedUser(String identifier, String... linkedParticipants) {
        User stored =
                users.save(new User(identifier, SEEDED_EMAIL, SEEDED_FIRST_NAME, SEEDED_LAST_NAME));
        for (String participant : linkedParticipants) {
            links.save(
                    new UserParticipant(
                            stored.getId(), participantId(participant), ORIGINAL_LOCAL_IDENTIFIER));
        }
    }

    /** Seeds one consent for the user in the given status between the two named participants. */
    private void grant(String user, ConsentStatus status, String provider, String consumer) {
        PrivacyNotice notice =
                notices.save(
                        new PrivacyNotice(
                                NOTICE_CONTRACT_URI,
                                null,
                                participantId(provider),
                                participantId(consumer),
                                new PrivacyNoticePayload(
                                        null, null, null, null, null, null, null, null, null)));
        consents.save(
                Consent.withStatus(
                        status,
                        userId(user),
                        notice.getId(),
                        participantId(provider),
                        participantId(consumer),
                        null,
                        NOTICE_CONTRACT_URI,
                        new ConsentSnapshot(null, null, null, null, null)));
    }

    private String localIdentifierOf(String user, String participant) {
        UUID participantId = participantId(participant);
        return links.findByIdUserId(userId(user)).stream()
                .filter(link -> participantId.equals(link.getParticipantId()))
                .findFirst()
                .orElseThrow()
                .getLocalIdentifier();
    }

    private List<String> linkedParticipantsOf(UUID userId) {
        return links.findByIdUserId(userId).stream()
                .map(link -> participants.findById(link.getParticipantId()).orElseThrow())
                .map(Participant::getIdentifier)
                .sorted()
                .toList();
    }

    private UUID userId(String identifier) {
        return users.findByIdentifier(identifier).orElseThrow().getId();
    }

    private UUID callerId() {
        return participantId(CALLER_PARTICIPANT);
    }

    private UUID participantId(String identifier) {
        return participant(identifier).getId();
    }

    private Participant participant(String identifier) {
        return participants
                .findByIdentifier(identifier)
                .orElseGet(
                        () ->
                                participants.save(
                                        new Participant(
                                                identifier,
                                                identifier,
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
