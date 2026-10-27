package com.seamware.consentmanager.api;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.domain.UserParticipant;
import com.seamware.consentmanager.repository.ConsentEventRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code POST /consents/requests} end to end: guards an ask conferring consent, a caller asking
 * outside its notices or links, and refusals that would let the route enumerate users.
 */
@MicronautTest(transactional = false)
@DisplayName("Consent request endpoint")
class ConsentRequestSurfaceIT extends KeycloakAndPostgresTestResource {

    private static final String PATH_CONSENT_REQUESTS = "/consents/requests";

    /** Longest a cold Keycloak may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    /** The caller: the identifier the {@code PARTICIPANT} token carries, registered per case. */
    private static final String CALLER = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    /** The notice's other party, and the participant the caller may not ask in the name of. */
    private static final String COUNTERPARTY = "urn:test:participant:request-consumer";

    /** A party to a notice the caller is named by on neither side. */
    private static final String STRANGER = "urn:test:participant:request-stranger";

    private static final String CONTRACT_URI =
            "https://contract.example.org/v1/contracts/request-1";

    private static final String OTHER_CONTRACT_URI =
            "https://contract.example.org/v1/contracts/request-2";

    private static final String NOTICE_TITLE = "Privacy notice for the mobility contract";

    /** The notice's purposes, which a request elects nothing out of. */
    private static final List<String> NOTICE_PURPOSES =
            List.of("Produce aggregated mobility insights.", "Plan road maintenance.");

    /** The notice's categories of personal data, under the same rule. */
    private static final List<String> NOTICE_CATEGORIES = List.of("Trip records", "Home address");

    /** The subject the caller may ask, because the fixture links the two. */
    private static final String LINKED_USER = "urn:test:user:request-subject";

    /** A user the fixture affiliates with {@link #COUNTERPARTY}, so the caller may not ask it. */
    private static final String FOREIGN_USER = "urn:test:user:request-foreign";

    /** An identifier the fixture never stores, which is the other half of the 404 pair. */
    private static final String ABSENT_USER = "urn:test:user:request-absent";

    private static final String LOCAL_IDENTIFIER = "request-local";

    private static final String PROPERTY_NOTICE = "privacyNoticeId";

    private static final String PROPERTY_SUBJECT = "userIdentifier";

    private static final String PROPERTY_ID = "id";

    private static final String PROPERTY_PARENT = "parentConsentId";

    /** Stands in for the subject identifier when two refusals are compared for sameness. */
    private static final String SUBJECT_PLACEHOLDER = "<subject>";

    /** Problem member naming the request it answers, unique to every response. */
    private static final String PROBLEM_CORRELATION_ID = "correlationId";

    @Inject
    @Client("/")
    HttpClient client;

    @Inject ObjectMapper json;

    @Inject IdentityProviderRegistry registry;

    @Inject UserRepository users;

    @Inject ParticipantRepository participants;

    @Inject UserParticipantRepository links;

    @Inject PrivacyNoticeRepository notices;

    @Inject ConsentRepository consents;

    @Inject ConsentEventRepository events;

    /** The notice the caller is a party to, re-seeded per case so none inherits a request. */
    private PrivacyNotice notice;

    @BeforeEach
    void seedTheNoticeAndTheAffiliation() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        removeCommittedRows();
        Participant caller = register(CALLER);
        notice = seedNotice(CONTRACT_URI, caller, register(COUNTERPARTY), null);
        link(users.save(new User(LINKED_USER, null, null, null)), caller);
        link(users.save(new User(FOREIGN_USER, null, null, null)), register(COUNTERPARTY));
    }

    /** Removes committed rows: consents first, then affiliations, then the users they name. */
    @AfterEach
    void removeCommittedRows() {
        consents.deleteAll();
        notices.deleteAll();
        Stream.of(LINKED_USER, FOREIGN_USER)
                .forEach(
                        identifier ->
                                users.findByIdentifier(identifier)
                                        .ifPresent(
                                                user -> {
                                                    links.findByIdUserId(user.getId())
                                                            .forEach(links::delete);
                                                    users.delete(user);
                                                }));
        Stream.of(CALLER, COUNTERPARTY, STRANGER)
                .forEach(
                        identifier ->
                                participants
                                        .findByIdentifier(identifier)
                                        .ifPresent(participants::delete));
    }

    @Test
    @DisplayName("opens a PENDING request with 201, which consents to nothing")
    void opensAPendingRequest() {
        HttpResponse<String> response = request(LINKED_USER, noticeId());

        assertThat(response.code()).isEqualTo(HttpStatus.CREATED.getCode());
        assertThat(body(response))
                .containsEntry(PROPERTY_NOTICE, noticeId())
                .containsEntry("status", ConsentStatus.PENDING.name())
                .as("a question put is not an answer given")
                .containsEntry("consented", false)
                .containsEntry("provider", CALLER)
                .containsEntry("consumer", COUNTERPARTY)
                .containsEntry("contractUri", CONTRACT_URI)
                .as("the ask elects nothing, so the record stands over the notice's full terms")
                .containsEntry("purposes", NOTICE_PURPOSES)
                .containsEntry("data", NOTICE_CATEGORIES)
                .as("the first link of a chain supersedes nothing")
                .doesNotContainKey(PROPERTY_PARENT);

        assertThat(consents.findAll())
                .singleElement()
                .satisfies(stored -> assertThat(stored.getUserId()).isEqualTo(subjectId()));
    }

    @Test
    @DisplayName("records the asking participant as the actor on the CONSENT_REQUESTED event")
    void recordsTheCallerAsTheActor() {
        UUID opened =
                UUID.fromString((String) body(request(LINKED_USER, noticeId())).get(PROPERTY_ID));

        assertThat(events.findByConsentIdOrderByOccurredAtAsc(opened))
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.getEventState())
                                    .isEqualTo(ConsentEventState.CONSENT_REQUESTED);
                            assertThat(event.getActor())
                                    .as("the trail names who asked, not who was asked")
                                    .isEqualTo(CALLER);
                        });
    }

    @Test
    @DisplayName("refuses a notice naming the caller on neither side with 403")
    void refusesANoticeTheCallerIsNoPartyTo() {
        PrivacyNotice foreign =
                seedNotice(OTHER_CONTRACT_URI, register(COUNTERPARTY), register(STRANGER), null);

        HttpResponse<String> response = request(LINKED_USER, foreign.getId().toString());

        assertThat(response.code()).isEqualTo(HttpStatus.FORBIDDEN.getCode());
        assertThat(consents.findAll()).isEmpty();
    }

    @Test
    @DisplayName("refuses an unknown notice with 404")
    void refusesAnUnknownNotice() {
        assertThat(request(LINKED_USER, UUID.randomUUID().toString()).code())
                .isEqualTo(HttpStatus.NOT_FOUND.getCode());
        assertThat(consents.findAll()).isEmpty();
    }

    @Test
    @DisplayName("refuses an archived notice with 400")
    void refusesAnArchivedNotice() {
        PrivacyNotice archived =
                seedNotice(
                        OTHER_CONTRACT_URI,
                        register(CALLER),
                        register(COUNTERPARTY),
                        Instant.now());

        assertThat(request(LINKED_USER, archived.getId().toString()).code())
                .isEqualTo(HttpStatus.BAD_REQUEST.getCode());
        assertThat(consents.findAll()).isEmpty();
    }

    @Test
    @DisplayName("answers an absent subject and somebody else's subject alike, so neither is told")
    void doesNotDistinguishAnAbsentSubjectFromSomebodyElses() {
        HttpResponse<String> absent = request(ABSENT_USER, noticeId());
        HttpResponse<String> foreign = request(FOREIGN_USER, noticeId());

        assertThat(absent.code()).isEqualTo(HttpStatus.NOT_FOUND.getCode());
        assertThat(foreign.code()).isEqualTo(absent.code());
        assertThat(anonymised(foreign, FOREIGN_USER))
                .as("a difference in the refusal would say which users the service holds")
                .isEqualTo(anonymised(absent, ABSENT_USER));
        assertThat(consents.findAll()).isEmpty();
    }

    /** Statuses that are still in force, each of which an ask must not duplicate. */
    static Stream<ConsentStatus> recordsInForce() {
        return Stream.of(ConsentStatus.PENDING, ConsentStatus.GRANTED);
    }

    @ParameterizedTest(name = "over an existing {0}")
    @MethodSource("recordsInForce")
    @DisplayName("refuses a second ask against a record still in force with 409")
    void refusesASecondAsk(ConsentStatus held) {
        UUID existing = seedConsent(held);

        assertThat(request(LINKED_USER, noticeId()).code())
                .isEqualTo(HttpStatus.CONFLICT.getCode());
        assertThat(consents.findAll())
                .as("the record already held is the one to read")
                .singleElement()
                .satisfies(stored -> assertThat(stored.getId()).isEqualTo(existing));
    }

    @Test
    @DisplayName("refuses a deregistered participant with 403")
    void refusesADeregisteredParticipant() {
        Participant caller = participants.findByIdentifier(CALLER).orElseThrow();
        caller.setDeregisteredAt(Instant.now());
        participants.update(caller);

        assertThat(request(LINKED_USER, noticeId()).code())
                .isEqualTo(HttpStatus.FORBIDDEN.getCode());
        assertThat(consents.findAll()).isEmpty();
    }

    @ParameterizedTest(name = "as {0}")
    @EnumSource(
            value = RealmPrincipal.class,
            names = {"USER", "CATALOG"})
    @DisplayName("refuses every role but PARTICIPANT, so nobody can ask in a participant's name")
    void refusesEveryRoleButParticipant(RealmPrincipal role) {
        assertThat(exchange(post(LINKED_USER, noticeId()), KeycloakTestResource.accessToken(role)))
                .extracting(HttpResponse::code)
                .isEqualTo(HttpStatus.FORBIDDEN.getCode());
        assertThat(consents.findAll()).isEmpty();
    }

    @Test
    @DisplayName("refuses an unauthenticated request with 401")
    void refusesAnUnauthenticatedRequest() {
        assertThat(exchange(post(LINKED_USER, noticeId()), null).code())
                .isEqualTo(HttpStatus.UNAUTHORIZED.getCode());
    }

    /** A consent the pair already holds, as an earlier ask or an earlier decision would have. */
    private UUID seedConsent(ConsentStatus status) {
        return consents.save(
                        Consent.withStatus(
                                status,
                                subjectId(),
                                notice.getId(),
                                notice.getProviderId(),
                                notice.getConsumerId(),
                                null,
                                CONTRACT_URI,
                                noTerms()))
                .getId();
    }

    /** Writes a notice between the two given parties, archived when {@code archivedAt} is given. */
    private PrivacyNotice seedNotice(
            String contractUri, Participant provider, Participant consumer, Instant archivedAt) {
        PrivacyNotice seeded =
                new PrivacyNotice(
                        contractUri,
                        NOTICE_TITLE,
                        provider.getId(),
                        consumer.getId(),
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

    private Participant register(String identifier) {
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

    private void link(User user, Participant participant) {
        links.save(new UserParticipant(user.getId(), participant.getId(), LOCAL_IDENTIFIER));
    }

    private String noticeId() {
        return notice.getId().toString();
    }

    private UUID subjectId() {
        return users.findByIdentifier(LINKED_USER).orElseThrow().getId();
    }

    private HttpResponse<String> request(String subject, String privacyNoticeId) {
        return exchange(
                post(subject, privacyNoticeId),
                KeycloakTestResource.accessToken(RealmPrincipal.PARTICIPANT));
    }

    private static MutableHttpRequest<?> post(String subject, String privacyNoticeId) {
        return HttpRequest.POST(
                PATH_CONSENT_REQUESTS,
                Map.of(PROPERTY_NOTICE, privacyNoticeId, PROPERTY_SUBJECT, subject));
    }

    /** The refusal with its subject masked and correlation ID dropped, to compare shapes. */
    private Map<String, Object> anonymised(HttpResponse<String> response, String subject) {
        try {
            Map<String, Object> problem =
                    new HashMap<>(
                            json.readValue(
                                    response.body().replace(subject, SUBJECT_PLACEHOLDER),
                                    Argument.mapOf(String.class, Object.class)));
            problem.remove(PROBLEM_CORRELATION_ID);
            return problem;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the response body", e);
        }
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
