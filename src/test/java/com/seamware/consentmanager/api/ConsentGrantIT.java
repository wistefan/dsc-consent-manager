package com.seamware.consentmanager.api;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
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
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code POST /consents} end to end: guards the recorded status, a body redirecting the decision to
 * someone other than the token's subject, and any role but {@code USER} reaching the route.
 */
@MicronautTest(transactional = false)
@DisplayName("Consent grant endpoint")
class ConsentGrantIT extends KeycloakAndPostgresTestResource {

    private static final String PATH_CONSENTS = "/consents";

    /** Longest a cold Keycloak may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    private static final String PROVIDER = "urn:test:participant:consent-provider";

    private static final String CONSUMER = "urn:test:participant:consent-consumer";

    private static final String CONTRACT_URI = "https://contract.example.org/v1/contracts/grant-1";

    private static final String NOTICE_TITLE = "Privacy notice for the mobility contract";

    /** The notice's purposes, which an election may narrow but never exceed. */
    private static final List<String> NOTICE_PURPOSES =
            List.of("Produce aggregated mobility insights.", "Plan road maintenance.");

    /** The notice's categories of personal data, under the same rule. */
    private static final List<String> NOTICE_CATEGORIES = List.of("Trip records", "Home address");

    /** A value no heading of the seeded notice declares. */
    private static final String OUTSIDE_SCOPE = "Resell to third parties.";

    /** Identifier of a second user, named in a body to prove the body cannot redirect a grant. */
    private static final String OTHER_USER = "urn:test:user:not-the-caller";

    private static final String PROPERTY_NOTICE = "privacyNoticeId";

    private static final String PROPERTY_DECISION = "decision";

    private static final String PROPERTY_PURPOSES = "purposes";

    private static final String PROPERTY_DATA = "data";

    private static final String PROPERTY_ID = "id";

    private static final String PROPERTY_PARENT = "parentConsentId";

    private static final String GRANTED = "GRANTED";

    private static final String REFUSED = "REFUSED";

    @Inject
    @Client("/")
    HttpClient client;

    @Inject ObjectMapper json;

    @Inject IdentityProviderRegistry registry;

    @Inject UserRepository users;

    @Inject ParticipantRepository participants;

    @Inject PrivacyNoticeRepository notices;

    @Inject ConsentRepository consents;

    /** The caller's identifier, resolved once because reading it costs a token request. */
    private String identifier;

    /** The notice every case decides against, re-seeded per case so none inherits a decision. */
    private PrivacyNotice notice;

    @BeforeEach
    void seedTheNoticeTheCallerDecidesAbout() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        identifier = KeycloakTestResource.subject(userToken());
        removeCommittedRows();
        notice = seedNotice(null);
    }

    /** Removes committed rows; consents go first because they restrict their user and parties. */
    @AfterEach
    void removeCommittedRows() {
        consents.deleteAll();
        notices.deleteAll();
        users.findByIdentifier(identifier).ifPresent(users::delete);
        users.findByIdentifier(OTHER_USER).ifPresent(users::delete);
        List.of(PROVIDER, CONSUMER)
                .forEach(
                        party ->
                                participants
                                        .findByIdentifier(party)
                                        .ifPresent(participants::delete));
    }

    static Stream<Arguments> decisions() {
        return Stream.of(Arguments.of(GRANTED, true), Arguments.of(REFUSED, false));
    }

    @ParameterizedTest(name = "{0} is recorded with consented={1}")
    @MethodSource("decisions")
    @DisplayName("records the caller's decision with 201 and derives `consented` from it")
    void recordsADecisionForTheTokensSubject(String decision, boolean consented) {
        HttpResponse<String> response =
                decide(Map.of(PROPERTY_NOTICE, noticeId(), PROPERTY_DECISION, decision));

        assertThat(response.code()).isEqualTo(HttpStatus.CREATED.getCode());
        Map<String, Object> created = body(response);
        assertThat(created)
                .containsEntry(PROPERTY_NOTICE, noticeId())
                .containsEntry("status", decision)
                .containsEntry("consented", consented)
                .containsEntry("provider", PROVIDER)
                .containsEntry("consumer", CONSUMER)
                .containsEntry("contractUri", CONTRACT_URI)
                .as("an omitted election consents to the notice's own scope")
                .containsEntry(PROPERTY_PURPOSES, NOTICE_PURPOSES)
                .containsEntry(PROPERTY_DATA, NOTICE_CATEGORIES)
                .as("the first link of a chain supersedes nothing")
                .doesNotContainKey(PROPERTY_PARENT);

        assertThat(storedConsents())
                .singleElement()
                .satisfies(stored -> assertThat(stored.getUserId()).isEqualTo(callerId()));
    }

    @Test
    @DisplayName("renders the record a decision superseded as the new one's parent")
    void rendersTheSupersededRecordAsTheParent() {
        Map<String, Object> first =
                body(decide(Map.of(PROPERTY_NOTICE, noticeId(), PROPERTY_DECISION, GRANTED)));

        Map<String, Object> second =
                body(decide(Map.of(PROPERTY_NOTICE, noticeId(), PROPERTY_DECISION, REFUSED)));

        assertThat(second)
                .as("a chain is readable backwards over HTTP, not only in the table")
                .containsEntry(PROPERTY_PARENT, first.get(PROPERTY_ID));
        assertThat(second.get(PROPERTY_ID)).isNotEqualTo(first.get(PROPERTY_ID));
        assertThat(storedConsents())
                .extracting(Consent::getStatus)
                .containsExactlyInAnyOrder(ConsentStatus.REVOKED, ConsentStatus.REFUSED);
    }

    @Test
    @DisplayName("answers a request that is still open with 200, because it creates nothing")
    void answersAnOpenRequestInPlace() {
        UUID requested = seedOpenRequest();

        HttpResponse<String> response =
                decide(Map.of(PROPERTY_NOTICE, noticeId(), PROPERTY_DECISION, GRANTED));

        assertThat(response.code())
                .as("a client answering a request is told the record was there already")
                .isEqualTo(HttpStatus.OK.getCode());
        assertThat(body(response))
                .as("the body is the record the request opened, under the id it was given")
                .containsEntry(PROPERTY_ID, requested.toString())
                .containsEntry("status", GRANTED)
                .containsEntry("consented", true);
        assertThat(storedConsents())
                .as("no second consent stands beside the one that was answered")
                .singleElement()
                .satisfies(stored -> assertThat(stored.getId()).isEqualTo(requested));
    }

    @Test
    @DisplayName("freezes only the elected part of the notice's terms")
    void freezesTheElectedScope() {
        Map<String, Object> created =
                body(
                        decide(
                                Map.of(
                                        PROPERTY_NOTICE, noticeId(),
                                        PROPERTY_DECISION, GRANTED,
                                        PROPERTY_PURPOSES, List.of(NOTICE_PURPOSES.getFirst()),
                                        PROPERTY_DATA, List.of(NOTICE_CATEGORIES.getFirst()))));

        assertThat(created)
                .containsEntry(PROPERTY_PURPOSES, List.of(NOTICE_PURPOSES.getFirst()))
                .containsEntry(PROPERTY_DATA, List.of(NOTICE_CATEGORIES.getFirst()));
    }

    @Test
    @DisplayName(
            "ignores a user named in the body and records the decision for the token's subject")
    void ignoresAUserNamedInTheBody() {
        User other = users.save(new User(OTHER_USER, "other@example.org", "Other", "Person"));

        HttpResponse<String> response =
                decide(
                        Map.of(
                                PROPERTY_NOTICE,
                                noticeId(),
                                PROPERTY_DECISION,
                                GRANTED,
                                "userIdentifier",
                                OTHER_USER,
                                "email",
                                "other@example.org",
                                "userKey",
                                other.getId().toString()));

        assertThat(response.code())
                .as("unknown properties are ignored rather than refused")
                .isEqualTo(HttpStatus.CREATED.getCode());
        assertThat(storedConsents())
                .singleElement()
                .satisfies(stored -> assertThat(stored.getUserId()).isEqualTo(callerId()));
        assertThat(consents.existsByUserId(other.getId()))
                .as("the user the body named holds no consent")
                .isFalse();
    }

    @Test
    @DisplayName("refuses a notice nothing stores with 404")
    void refusesAnUnknownNotice() {
        HttpResponse<String> response =
                decide(
                        Map.of(
                                PROPERTY_NOTICE,
                                UUID.randomUUID().toString(),
                                PROPERTY_DECISION,
                                GRANTED));

        assertThat(response.code()).isEqualTo(HttpStatus.NOT_FOUND.getCode());
        assertThat(storedConsents()).isEmpty();
    }

    @Test
    @DisplayName("refuses an archived notice with 400")
    void refusesAnArchivedNotice() {
        PrivacyNotice archived = seedNotice(Instant.now());

        HttpResponse<String> response =
                decide(
                        Map.of(
                                PROPERTY_NOTICE,
                                archived.getId().toString(),
                                PROPERTY_DECISION,
                                GRANTED));

        assertThat(response.code()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
        assertThat(storedConsents()).isEmpty();
    }

    static Stream<Arguments> electionsOutsideTheNotice() {
        return Stream.of(
                Arguments.of(PROPERTY_PURPOSES, List.of(OUTSIDE_SCOPE)),
                Arguments.of(PROPERTY_DATA, List.of(OUTSIDE_SCOPE)),
                Arguments.of(PROPERTY_PURPOSES, List.of()),
                Arguments.of(PROPERTY_DATA, List.of()));
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("electionsOutsideTheNotice")
    @DisplayName("refuses an election the notice cannot honour with 400")
    void refusesAnElectionOutsideTheNotice(String heading, List<String> elected) {
        HttpResponse<String> response =
                decide(
                        Map.of(
                                PROPERTY_NOTICE, noticeId(),
                                PROPERTY_DECISION, GRANTED,
                                heading, elected));

        assertThat(response.code()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
        assertThat(storedConsents()).isEmpty();
    }

    @ParameterizedTest(name = "as {0}")
    @MethodSource("nonSubjectRoles")
    @DisplayName("refuses every role but USER, so no participant can grant in a subject's name")
    void refusesEveryRoleButUser(RealmPrincipal role) {
        MutableHttpRequest<?> request =
                HttpRequest.POST(
                        PATH_CONSENTS,
                        Map.of(PROPERTY_NOTICE, noticeId(), PROPERTY_DECISION, GRANTED));

        assertThat(exchange(request, KeycloakTestResource.accessToken(role)).code())
                .isEqualTo(HttpStatus.FORBIDDEN.getCode());
        assertThat(storedConsents()).isEmpty();
    }

    static Stream<RealmPrincipal> nonSubjectRoles() {
        return Stream.of(RealmPrincipal.PARTICIPANT, RealmPrincipal.CATALOG);
    }

    @Test
    @DisplayName("refuses an unauthenticated request with 401")
    void refusesAnUnauthenticatedRequest() {
        MutableHttpRequest<?> request =
                HttpRequest.POST(
                        PATH_CONSENTS,
                        Map.of(PROPERTY_NOTICE, noticeId(), PROPERTY_DECISION, GRANTED));

        assertThat(exchange(request, null).code()).isEqualTo(HttpStatus.UNAUTHORIZED.getCode());
    }

    /**
     * A {@code PENDING} request for the caller and the seeded notice, written to the table after an
     * authenticated call provisions the subject's row.
     */
    private UUID seedOpenRequest() {
        decide(Map.of(PROPERTY_NOTICE, UUID.randomUUID().toString(), PROPERTY_DECISION, GRANTED));
        return consents.save(
                        Consent.withStatus(
                                ConsentStatus.PENDING,
                                callerId(),
                                notice.getId(),
                                notice.getProviderId(),
                                notice.getConsumerId(),
                                null,
                                CONTRACT_URI,
                                noTerms()))
                .getId();
    }

    /** Writes a notice with both parties registered, archived when {@code archivedAt} is given. */
    private PrivacyNotice seedNotice(Instant archivedAt) {
        PrivacyNotice seeded =
                new PrivacyNotice(
                        CONTRACT_URI,
                        NOTICE_TITLE,
                        register(PROVIDER).getId(),
                        register(CONSUMER).getId(),
                        new PrivacyNoticePayload(
                                NOTICE_PURPOSES,
                                NOTICE_CATEGORIES,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));
        seeded.setArchivedAt(archivedAt);
        return notices.save(seeded);
    }

    private Participant register(String participantIdentifier) {
        return participants
                .findByIdentifier(participantIdentifier)
                .orElseGet(
                        () ->
                                participants.save(
                                        new Participant(
                                                participantIdentifier,
                                                participantIdentifier,
                                                null,
                                                null,
                                                Map.of(),
                                                null)));
    }

    private String noticeId() {
        return notice.getId().toString();
    }

    private UUID callerId() {
        return users.findByIdentifier(identifier).orElseThrow().getId();
    }

    /** Every consent stored right now, so a case can assert that none was written at all. */
    private Iterable<Consent> storedConsents() {
        return consents.findAll();
    }

    private HttpResponse<String> decide(Map<String, Object> payload) {
        return exchange(HttpRequest.POST(PATH_CONSENTS, payload), userToken());
    }

    private static String userToken() {
        return KeycloakTestResource.accessToken(RealmPrincipal.USER);
    }

    private Map<String, Object> body(HttpResponse<String> response) {
        try {
            return json.readValue(response.body(), Argument.mapOf(String.class, Object.class));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the response body", e);
        }
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
