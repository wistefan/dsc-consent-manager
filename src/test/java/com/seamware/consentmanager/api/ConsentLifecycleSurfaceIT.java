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
 * Exercises {@code POST /consents/{id}/revoke} and {@code POST /consents/{id}/refuse} end to end
 * against a real Keycloak and a real PostgreSQL.
 *
 * <p>What is under test is who reaches a move rather than what the state table permits, which
 * {@code ConsentLifecycleIT} owns: the subject is read off the token, a record belonging to anyone
 * else is indistinguishable from one that does not exist, and no role but {@code USER} reaches the
 * routes at all.
 */
@MicronautTest(transactional = false)
@DisplayName("Consent lifecycle endpoints")
class ConsentLifecycleSurfaceIT extends KeycloakAndPostgresTestResource {

    private static final String PATH_CONSENTS = "/consents";

    private static final String REVOKE = "revoke";

    private static final String REFUSE = "refuse";

    /** Longest a cold Keycloak container may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    private static final String PROVIDER = "urn:test:participant:lifecycle-provider";

    private static final String CONSUMER = "urn:test:participant:lifecycle-consumer";

    private static final String CONTRACT_URI =
            "https://contract.example.org/v1/contracts/lifecycle-1";

    private static final String NOTICE_TITLE = "Privacy notice the lifecycle moves act on";

    /** Identifier of a second subject, whose consent the caller must not be able to move. */
    private static final String OTHER_USER = "urn:test:user:lifecycle-other";

    private static final String PROPERTY_ID = "id";

    private static final String PROPERTY_STATUS = "status";

    private static final String PROPERTY_CONSENTED = "consented";

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
        List.of(PROVIDER, CONSUMER)
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
