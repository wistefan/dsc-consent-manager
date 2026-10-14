package com.seamware.consentmanager.api;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
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
import com.seamware.consentmanager.support.PrivacyNoticeFixtures;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpMethod;
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
 * Exercises {@code POST /consents/{id}/revoke}, {@code /refuse} and {@code /terminate} end to end
 * against a real Keycloak and a real PostgreSQL.
 *
 * <p>What is under test is who reaches a move rather than what the state table permits, which
 * {@code ConsentLifecycleIT} owns: the subject is read off the token, a record belonging to anyone
 * else is indistinguishable from one that does not exist, and the subject's routes admit no role
 * but {@code USER}. Termination is the participant's counterpart, so it is checked against the
 * consent's two parties - including the case the role matrix cannot sweep, a caller whose record is
 * deregistered rather than absent. Resuming is the one route two roles reach, so both are driven
 * over the wire - including the shape the role matrix cannot send, a resume carrying no request
 * body at all, which the subject's call is answered by and the relay's refused for.
 */
@MicronautTest(transactional = false)
@DisplayName("Consent lifecycle endpoints")
class ConsentLifecycleSurfaceIT extends KeycloakAndPostgresTestResource {

    private static final String PATH_CONSENTS = "/consents";

    private static final String REVOKE = "revoke";

    private static final String REFUSE = "refuse";

    private static final String TERMINATE = "terminate";

    private static final String RESUME = "resume";

    /** Where a participant reads its own record back, which deregistration leaves open. */
    private static final String PATH_PARTICIPANTS_ME = "/participants/me";

    /** Longest a cold Keycloak container may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    private static final String PROVIDER = "urn:test:participant:lifecycle-provider";

    private static final String CONSUMER = "urn:test:participant:lifecycle-consumer";

    private static final String CONTRACT_URI =
            "https://contract.example.org/v1/contracts/lifecycle-1";

    /**
     * The identifier the {@code PARTICIPANT} token carries, registered by the cases that use it.
     */
    private static final String CALLER_PARTICIPANT = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    /**
     * The contract the termination cases record their notice against, distinct because the active
     * notice index is unique per (contract, provider) and one of them names the same provider.
     */
    private static final String TERMINATION_CONTRACT_URI =
            "https://contract.example.org/v1/contracts/lifecycle-2";

    private static final String NOTICE_TITLE = "Privacy notice the lifecycle moves act on";

    /** Identifier of a second subject, whose consent the caller must not be able to move. */
    private static final String OTHER_USER = "urn:test:user:lifecycle-other";

    private static final String PROPERTY_ID = "id";

    private static final String PROPERTY_STATUS = "status";

    private static final String PROPERTY_CONSENTED = "consented";

    /** What a relaying catalog names the subject with; ignored when the subject calls itself. */
    private static final String PROPERTY_USER_IDENTIFIER = "userIdentifier";

    /** The media type every refusal is rendered as. */
    private static final String PROBLEM_JSON = "application/problem+json";

    /** Routes carrying no request body still need one to post. */
    private static final Map<String, Object> NO_BODY = Map.of();

    @Inject
    @Client("/")
    HttpClient client;

    @Inject ObjectMapper json;

    @Inject IdentityProviderRegistry registry;

    @Inject UserRepository users;

    @Inject ParticipantRepository participants;

    @Inject PrivacyNoticeRepository notices;

    @Inject ConsentRepository consents;

    /** The caller's global identifier, resolved once because reading it costs a token request. */
    private String identifier;

    /** The notice every seeded consent is recorded against, re-seeded per case. */
    private PrivacyNotice notice;

    /** The two moves this step serves, with the status each needs and the one it leaves. */
    static Stream<Arguments> moves() {
        return Stream.of(
                Arguments.of(REVOKE, ConsentStatus.GRANTED, "REVOKED"),
                Arguments.of(REFUSE, ConsentStatus.PENDING, "REFUSED"));
    }

    /** The pairings each move refuses, so the route answers the state table over HTTP too. */
    static Stream<Arguments> movesFromTheWrongStatus() {
        return Stream.of(
                Arguments.of(REVOKE, ConsentStatus.PENDING),
                Arguments.of(REFUSE, ConsentStatus.GRANTED));
    }

    static Stream<RealmPrincipal> nonSubjectRoles() {
        return Stream.of(RealmPrincipal.PARTICIPANT, RealmPrincipal.CATALOG);
    }

    static Stream<RealmPrincipal> nonPartyRoles() {
        return Stream.of(RealmPrincipal.USER, RealmPrincipal.CATALOG);
    }

    /** Each side of a consent the calling participant may be named on; both may end it. */
    static Stream<String> partySides() {
        return Stream.of(PROVIDER, CONSUMER);
    }

    @BeforeEach
    void seedTheNoticeTheConsentsNameAndProvisionTheCaller() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        identifier = KeycloakTestResource.subject(userToken());
        removeCommittedRows();
        notice = seedNotice();
        provisionTheCaller();
    }

    /**
     * Rows committed by these cases outlive the transaction, so they are removed by hand.
     *
     * <p>Consents hold their user and both participants against {@code ON DELETE RESTRICT}, so they
     * go first; their events cascade with them.
     */
    @AfterEach
    void removeCommittedRows() {
        consents.deleteAll();
        notices.deleteAll();
        users.findByIdentifier(identifier).ifPresent(users::delete);
        users.findByIdentifier(OTHER_USER).ifPresent(users::delete);
        List.of(PROVIDER, CONSUMER, CALLER_PARTICIPANT)
                .forEach(
                        party ->
                                participants
                                        .findByIdentifier(party)
                                        .ifPresent(participants::delete));
    }

    @ParameterizedTest(name = "{0} moves a {1} consent to {2}")
    @MethodSource("moves")
    @DisplayName("moves the caller's own consent and answers 200 with the record in its new status")
    void movesTheCallersOwnConsent(String verb, ConsentStatus seeded, String expected) {
        UUID consentId = seedConsent(callerId(), seeded);

        HttpResponse<String> response = move(verb, consentId, userToken());

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(body(response))
                .as("the body is the record that was there, under the id it already had")
                .containsEntry(PROPERTY_ID, consentId.toString())
                .containsEntry(PROPERTY_STATUS, expected)
                .containsEntry(PROPERTY_CONSENTED, false)
                .containsEntry("provider", PROVIDER)
                .containsEntry("consumer", CONSUMER);
        assertThat(consents.findById(consentId).orElseThrow().getStatus().name())
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} from {1}")
    @MethodSource("movesFromTheWrongStatus")
    @DisplayName("refuses a move the consent's status does not permit with 400")
    void refusesAMoveTheStatusDoesNotPermit(String verb, ConsentStatus seeded) {
        UUID consentId = seedConsent(callerId(), seeded);

        assertThat(move(verb, consentId, userToken()).code())
                .isEqualTo(HttpStatus.BAD_REQUEST.getCode());
        assertThat(consents.findById(consentId).orElseThrow().getStatus()).isEqualTo(seeded);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("verbs")
    @DisplayName("answers a consent belonging to another subject with the 404 an unknown one gets")
    void refusesAConsentBelongingToAnotherSubject(String verb) {
        User other = users.save(new User(OTHER_USER, null, null, null));
        UUID theirs = seedConsent(other.getId(), ConsentStatus.GRANTED);

        assertThat(move(verb, theirs, userToken()).code())
                .isEqualTo(HttpStatus.NOT_FOUND.getCode());
        assertThat(consents.findById(theirs).orElseThrow().getStatus())
                .as("the other subject's record is untouched by the probe")
                .isEqualTo(ConsentStatus.GRANTED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("verbs")
    @DisplayName("answers a consent nothing stores with 404")
    void refusesAnUnknownConsent(String verb) {
        assertThat(move(verb, UUID.randomUUID(), userToken()).code())
                .isEqualTo(HttpStatus.NOT_FOUND.getCode());
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("verbsPerNonSubjectRole")
    @DisplayName(
            "refuses every role but USER, so no participant ends a consent in a subject's name")
    void refusesEveryRoleButUser(String verb, RealmPrincipal role) {
        UUID consentId = seedConsent(callerId(), ConsentStatus.GRANTED);

        assertThat(move(verb, consentId, KeycloakTestResource.accessToken(role)).code())
                .isEqualTo(HttpStatus.FORBIDDEN.getCode());
        assertThat(consents.findById(consentId).orElseThrow().getStatus())
                .isEqualTo(ConsentStatus.GRANTED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("verbs")
    @DisplayName("refuses an unauthenticated request with 401")
    void refusesAnUnauthenticatedRequest(String verb) {
        UUID consentId = seedConsent(callerId(), ConsentStatus.GRANTED);

        assertThat(move(verb, consentId, null).code()).isEqualTo(HttpStatus.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("refuses a second revocation, because the first left nothing to revoke")
    void refusesASecondRevocation() {
        UUID consentId = seedConsent(callerId(), ConsentStatus.GRANTED);

        assertThat(move(REVOKE, consentId, userToken()).code()).isEqualTo(HttpStatus.OK.getCode());

        assertThat(move(REVOKE, consentId, userToken()).code())
                .as("a revoked consent is not revocable again")
                .isEqualTo(HttpStatus.BAD_REQUEST.getCode());
    }

    @ParameterizedTest(name = "named as the {0}")
    @MethodSource("partySides")
    @DisplayName("terminates a consent the calling participant is a party to, on either side")
    void terminatesAsEitherParty(String side) {
        UUID consentId = seedConsentNaming(side, ConsentStatus.GRANTED);

        HttpResponse<String> response = move(TERMINATE, consentId, participantToken());

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(body(response))
                .containsEntry(PROPERTY_ID, consentId.toString())
                .containsEntry(PROPERTY_STATUS, ConsentStatus.TERMINATED.name())
                .containsEntry(PROPERTY_CONSENTED, false);
        assertThat(consents.findById(consentId).orElseThrow().getStatus())
                .isEqualTo(ConsentStatus.TERMINATED);
    }

    @Test
    @DisplayName("refuses a participant the consent names on neither side with 403")
    void refusesAParticipantThatIsNoParty() {
        register(CALLER_PARTICIPANT);
        UUID consentId = seedConsent(callerId(), ConsentStatus.GRANTED);

        assertThat(move(TERMINATE, consentId, participantToken()).code())
                .isEqualTo(HttpStatus.FORBIDDEN.getCode());
        assertThat(consents.findById(consentId).orElseThrow().getStatus())
                .isEqualTo(ConsentStatus.GRANTED);
    }

    /**
     * The window the role matrix cannot sweep: its own case deletes the participants row, so it
     * only ever exercises an unregistered caller. A retained record is registered and departed, and
     * a consent can still arise against its notices after it leaves, so this is what keeps a
     * departed participant from reaching into the dataspace to end one.
     */
    @Test
    @DisplayName("refuses a deregistered participant with 403 while it still reads its own record")
    void refusesADeregisteredParticipant() {
        UUID consentId = seedConsentNaming(PROVIDER, ConsentStatus.GRANTED);
        Participant caller = participants.findByIdentifier(CALLER_PARTICIPANT).orElseThrow();
        caller.setDeregisteredAt(Instant.now());
        participants.update(caller);

        assertThat(move(TERMINATE, consentId, participantToken()).code())
                .isEqualTo(HttpStatus.FORBIDDEN.getCode());
        assertThat(consents.findById(consentId).orElseThrow().getStatus())
                .as("the arrangement stands; only the subject can still end it")
                .isEqualTo(ConsentStatus.GRANTED);
        assertThat(exchange(HttpRequest.GET(PATH_PARTICIPANTS_ME), participantToken()).code())
                .as("reading its own record back stays open to a departed participant")
                .isEqualTo(HttpStatus.OK.getCode());
    }

    @ParameterizedTest(name = "as {0}")
    @MethodSource("nonPartyRoles")
    @DisplayName("refuses every role but PARTICIPANT on terminate, whoever the consent names")
    void refusesEveryRoleButParticipantOnTerminate(RealmPrincipal role) {
        UUID consentId = seedConsentNaming(PROVIDER, ConsentStatus.GRANTED);

        assertThat(move(TERMINATE, consentId, KeycloakTestResource.accessToken(role)).code())
                .isEqualTo(HttpStatus.FORBIDDEN.getCode());
        assertThat(consents.findById(consentId).orElseThrow().getStatus())
                .isEqualTo(ConsentStatus.GRANTED);
    }

    /**
     * The form the role matrix cannot send: it always posts a body, so a body-less request is the
     * only end-to-end proof that the generated parameter is nullable. Were it not, Micronaut would
     * refuse the request with a {@code 400} before the handler ran, and a subject resuming their
     * own consent - who has nothing to state - could never reach the route.
     */
    @Test
    @DisplayName("resumes the caller's own consent sent with no request body at all")
    void resumesTheCallersOwnConsentWithoutABody() {
        UUID consentId = seedConsent(callerId(), ConsentStatus.PENDING);

        HttpResponse<String> response =
                exchange(HttpRequest.create(HttpMethod.POST, resumePath(consentId)), userToken());

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(body(response))
                .containsEntry(PROPERTY_ID, consentId.toString())
                .containsEntry(PROPERTY_STATUS, ConsentStatus.GRANTED.name())
                .containsEntry(PROPERTY_CONSENTED, true);
        assertThat(consents.findById(consentId).orElseThrow().getStatus())
                .isEqualTo(ConsentStatus.GRANTED);
    }

    @Test
    @DisplayName("resumes as a CATALOG caller relaying the decision of the subject it names")
    void resumesAsACatalogRelayingTheSubject() {
        UUID consentId = seedConsent(callerId(), ConsentStatus.PENDING);

        HttpResponse<String> response =
                exchange(
                        HttpRequest.POST(
                                resumePath(consentId),
                                Map.of(PROPERTY_USER_IDENTIFIER, identifier)),
                        catalogToken());

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(body(response))
                .containsEntry(PROPERTY_ID, consentId.toString())
                .containsEntry(PROPERTY_STATUS, ConsentStatus.GRANTED.name())
                .containsEntry(PROPERTY_CONSENTED, true);
    }

    /**
     * The other half of the body-less case: nothing about the absent body decides the answer, the
     * caller's role does. The shape that is ordinary for a subject is a refusal for a relay, which
     * has no other way to name the subject whose decision it carries.
     */
    @Test
    @DisplayName("refuses a CATALOG resume sent with no request body at all as a 400 problem")
    void refusesACatalogResumeWithoutABody() {
        UUID consentId = seedConsent(callerId(), ConsentStatus.PENDING);

        HttpResponse<String> response =
                exchange(
                        HttpRequest.create(HttpMethod.POST, resumePath(consentId)), catalogToken());

        assertThat(response.code()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
        assertThat(response.getContentType().orElseThrow().toString()).startsWith(PROBLEM_JSON);
        assertThat(consents.findById(consentId).orElseThrow().getStatus())
                .as("a relay naming nobody writes nothing")
                .isEqualTo(ConsentStatus.PENDING);
    }

    @Test
    @DisplayName("refuses a PARTICIPANT resuming with 403, whatever the consent names it as")
    void refusesAParticipantResuming() {
        UUID consentId = seedConsentNaming(PROVIDER, ConsentStatus.PENDING);

        assertThat(move(RESUME, consentId, participantToken()).code())
                .isEqualTo(HttpStatus.FORBIDDEN.getCode());
        assertThat(consents.findById(consentId).orElseThrow().getStatus())
                .isEqualTo(ConsentStatus.PENDING);
    }

    /**
     * Re-points the notice at one naming the calling participant on {@code side}, and records a
     * consent of the caller's own against it.
     */
    private UUID seedConsentNaming(String side, ConsentStatus status) {
        Participant caller = register(CALLER_PARTICIPANT);
        Participant counterparty = register(PROVIDER.equals(side) ? CONSUMER : PROVIDER);
        notice =
                notices.save(
                        new PrivacyNotice(
                                TERMINATION_CONTRACT_URI,
                                NOTICE_TITLE,
                                PROVIDER.equals(side) ? caller.getId() : counterparty.getId(),
                                PROVIDER.equals(side) ? counterparty.getId() : caller.getId(),
                                PrivacyNoticeFixtures.emptyPayload()));
        return seedConsent(callerId(), status);
    }

    private static String participantToken() {
        return KeycloakTestResource.accessToken(RealmPrincipal.PARTICIPANT);
    }

    private static String catalogToken() {
        return KeycloakTestResource.accessToken(RealmPrincipal.CATALOG);
    }

    private static String resumePath(UUID consentId) {
        return PATH_CONSENTS + "/" + consentId + "/" + RESUME;
    }

    static Stream<String> verbs() {
        return Stream.of(REVOKE, REFUSE);
    }

    static Stream<Arguments> verbsPerNonSubjectRole() {
        return verbs().flatMap(verb -> nonSubjectRoles().map(role -> Arguments.of(verb, role)));
    }

    /**
     * Writes a consent for the given subject, as the grant flow would have left it.
     *
     * <p>Written straight to the table because what is under test is who may move a consent, not
     * how it came to be.
     */
    private UUID seedConsent(UUID userId, ConsentStatus status) {
        return consents.save(
                        Consent.withStatus(
                                status,
                                userId,
                                notice.getId(),
                                notice.getProviderId(),
                                notice.getConsumerId(),
                                null,
                                CONTRACT_URI,
                                noTerms()))
                .getId();
    }

    /**
     * Gives the caller its {@code users} row, which a seeded consent needs as a foreign key.
     *
     * <p>The row is provisioned from the token on the first authenticated call, so any call does -
     * one against an identifier nothing stores costs the least and asserts the 404 besides.
     */
    private void provisionTheCaller() {
        assertThat(move(REVOKE, UUID.randomUUID(), userToken()).code())
                .isEqualTo(HttpStatus.NOT_FOUND.getCode());
    }

    private PrivacyNotice seedNotice() {
        return notices.save(
                new PrivacyNotice(
                        CONTRACT_URI,
                        NOTICE_TITLE,
                        register(PROVIDER).getId(),
                        register(CONSUMER).getId(),
                        PrivacyNoticeFixtures.emptyPayload()));
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

    private UUID callerId() {
        return users.findByIdentifier(identifier).orElseThrow().getId();
    }

    private HttpResponse<String> move(String verb, UUID consentId, String token) {
        return exchange(
                HttpRequest.POST(PATH_CONSENTS + "/" + consentId + "/" + verb, NO_BODY), token);
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
