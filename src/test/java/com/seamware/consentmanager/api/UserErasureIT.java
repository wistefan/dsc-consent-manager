package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentSnapshot;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Exercises {@code DELETE /users/me} end to end against a real Keycloak and a real PostgreSQL.
 *
 * <p>What is under test is the shape of the erasure rather than the bare status code: that the
 * person is gone from the record while the consents and their events stay, with their original ids,
 * as the evidence of what was once authorised; and that the subject is a stranger to this service
 * afterwards - a later token provisions a new row instead of resurrecting the erased one.
 */
@MicronautTest(transactional = false)
@DisplayName("User erasure")
class UserErasureIT extends KeycloakAndPostgresTestResource {

    private static final String PATH_ME = "/users/me";

    /** The reserved prefix every erased identifier carries; mirrors the service's own constant. */
    private static final String ERASED_PREFIX = "urn:consent-manager:erased:";

    private static final String PROVIDER_PARTICIPANT = "urn:test:participant:erasure-provider";

    private static final String CONSUMER_PARTICIPANT = "urn:test:participant:erasure-consumer";

    private static final List<String> SEEDED_PARTICIPANTS =
            List.of(PROVIDER_PARTICIPANT, CONSUMER_PARTICIPANT);

    /** Contract URI of the privacy notice every seeded consent is frozen from. */
    private static final String NOTICE_CONTRACT_URI = "urn:test:contract:erasure";

    /** The local identifier the seeded links carry, so their disappearance is visible. */
    private static final String LOCAL_IDENTIFIER = "patient-55018";

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

    @Inject ConsentEventRepository events;

    @Inject PrivacyNoticeRepository notices;

    /** The caller's global identifier, resolved once because reading it costs a token request. */
    private String identifier;

    @BeforeEach
    void awaitTheRealmAndStartFromAnUnregisteredUser() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        identifier = KeycloakTestResource.subject(userToken());
        // The realm holds a single dataspace-user, so every case has to re-establish "not erased
        // yet" rather than assume it: rows committed by an earlier case outlive its transaction.
        removeCommittedRows();
        SEEDED_PARTICIPANTS.forEach(this::participant);
    }

    /** Rows committed by these tests outlive the transaction, so they are removed by hand. */
    @AfterAll
    void removeCommittedRows() {
        users.findAll().forEach(user -> removeIfSeeded(user));
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
    @DisplayName("revokes, unlinks and pseudonymises while keeping the consent trail intact")
    void erasesTheCallerAndKeepsTheConsentTrail() throws IOException {
        User caller = registeredCaller();
        UUID callerId = caller.getId();
        link(caller, PROVIDER_PARTICIPANT);
        link(caller, CONSUMER_PARTICIPANT);
        UUID outbound =
                grant(caller, ConsentStatus.GRANTED, PROVIDER_PARTICIPANT, CONSUMER_PARTICIPANT);
        UUID inbound =
                grant(caller, ConsentStatus.GRANTED, CONSUMER_PARTICIPANT, PROVIDER_PARTICIPANT);
        UUID alreadyRevoked =
                grant(caller, ConsentStatus.REVOKED, PROVIDER_PARTICIPANT, CONSUMER_PARTICIPANT);

        HttpResponse<String> response = erase();

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        Map<String, Object> summary = body(response);
        assertThat(count(summary, "consentsRevoked"))
                .as("only the granted consents are revoked")
                .isEqualTo(2);
        assertThat(count(summary, "linksRemoved")).isEqualTo(2);
        String pseudonym = (String) summary.get("pseudonym");
        assertThat(pseudonym)
                .startsWith(ERASED_PREFIX)
                .isNotEqualTo(identifier)
                .doesNotContain(identifier);

        User erased = users.findById(callerId).orElseThrow();
        assertThat(erased.getIdentifier()).isEqualTo(pseudonym);
        assertThat(erased.getEmail()).isNull();
        assertThat(erased.getFirstName()).isNull();
        assertThat(erased.getLastName()).isNull();
        assertThat(links.findByIdUserId(callerId))
                .as("every affiliation ends with the person")
                .isEmpty();

        assertThat(consents.findByUserId(callerId, Pageable.UNPAGED).getContent())
                .as("the consent records survive under their original ids")
                .extracting(Consent::getId)
                .containsExactlyInAnyOrder(outbound, inbound, alreadyRevoked);
        assertThat(consents.findByUserIdAndStatus(callerId, ConsentStatus.GRANTED)).isEmpty();
        assertThat(revocationsOf(outbound)).hasSize(1);
        assertThat(revocationsOf(inbound)).hasSize(1);
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(alreadyRevoked))
                .as("a consent that was not granted is not re-revoked")
                .isEmpty();
    }

    @Test
    @DisplayName("a later token for the erased subject provisions a new record")
    void aLaterTokenProvisionsANewRecord() {
        UUID erasedId = registeredCaller().getId();

        erase();

        User provisioned = registeredCaller();
        assertThat(provisioned.getId())
                .as("the erased row is not resurrected by the subject coming back")
                .isNotEqualTo(erasedId);
        assertThat(provisioned.getIdentifier()).isEqualTo(identifier);
        assertThat(users.findById(erasedId).orElseThrow().getIdentifier())
                .startsWith(ERASED_PREFIX);
    }

    @Test
    @DisplayName("each erasure mints its own pseudonym")
    void successiveErasuresDoNotCollide() throws IOException {
        registeredCaller();
        String first = pseudonymOf(erase());

        registeredCaller();
        String second = pseudonymOf(erase());

        assertThat(second)
                .as("two erased records that shared an identifier would be linkable")
                .isNotEqualTo(first);
    }

    @Test
    @DisplayName("erases a caller this service has not seen before, with nothing to report")
    void erasesACallerWithNothingToErase() throws IOException {
        HttpResponse<String> response = erase();

        assertThat(response.code())
                .as("the route's own authentication registers the caller, so there is no 404")
                .isEqualTo(HttpStatus.OK.getCode());
        assertThat(count(body(response), "consentsRevoked")).isZero();
        assertThat(count(body(response), "linksRemoved")).isZero();
        assertThat(users.findByIdentifier(identifier)).isEmpty();
    }

    @ParameterizedTest(name = "as {0}")
    @EnumSource(
            value = RealmPrincipal.class,
            names = {"PARTICIPANT", "CATALOG"})
    @DisplayName("refuses a token that authenticates but holds no USER role")
    void refusesANonUserRole(RealmPrincipal role) {
        assertThat(
                        exchange(
                                        HttpRequest.DELETE(PATH_ME),
                                        KeycloakTestResource.accessToken(role))
                                .code())
                .isEqualTo(HttpStatus.FORBIDDEN.getCode());
        assertThat(erasedUsers()).as("a refused request erases nothing").isEmpty();
    }

    @Test
    @DisplayName("refuses an unauthenticated request")
    void refusesAnUnauthenticatedRequest() {
        assertThat(exchange(HttpRequest.DELETE(PATH_ME), null).code())
                .isEqualTo(HttpStatus.UNAUTHORIZED.getCode());
        assertThat(erasedUsers()).isEmpty();
    }

    private HttpResponse<String> erase() {
        return exchange(HttpRequest.DELETE(PATH_ME), userToken());
    }

    private String pseudonymOf(HttpResponse<String> response) throws IOException {
        return (String) body(response).get("pseudonym");
    }

    /** Reads the caller's own record, which provisions it, and answers with the stored row. */
    private User registeredCaller() {
        exchange(HttpRequest.GET(PATH_ME), userToken());
        return users.findByIdentifier(identifier).orElseThrow();
    }

    /** The {@code CONSENT_REVOKED} events standing against one consent. */
    private List<ConsentEvent> revocationsOf(UUID consentId) {
        return events.findByConsentIdOrderByOccurredAtAsc(consentId).stream()
                .filter(event -> event.getEventState() == ConsentEventState.CONSENT_REVOKED)
                .toList();
    }

    private List<User> erasedUsers() {
        return users.findAll().stream()
                .filter(user -> user.getIdentifier().startsWith(ERASED_PREFIX))
                .toList();
    }

    private void link(User user, String participant) {
        links.save(new UserParticipant(user.getId(), participantId(participant), LOCAL_IDENTIFIER));
    }

    /** Seeds one consent for the user in the given status between the two named participants. */
    private UUID grant(User user, ConsentStatus status, String provider, String consumer) {
        PrivacyNotice notice = notice(provider, consumer);
        return consents.save(
                        Consent.withStatus(
                                status,
                                user.getId(),
                                notice.getId(),
                                participantId(provider),
                                participantId(consumer),
                                null,
                                NOTICE_CONTRACT_URI,
                                new ConsentSnapshot(null, null, null, null, null)))
                .getId();
    }

    /**
     * The active notice between the two participants, seeded on first use.
     *
     * <p>Reused rather than re-created: {@code uq_privacy_notices_active_contract} admits one
     * unarchived notice per (contract, provider), so two consents from the same provider have to
     * hang off the same notice.
     */
    private PrivacyNotice notice(String provider, String consumer) {
        UUID providerId = participantId(provider);
        UUID consumerId = participantId(consumer);
        return notices.findByProviderIdAndArchivedAtIsNull(providerId).stream()
                .filter(stored -> consumerId.equals(stored.getConsumerId()))
                .findFirst()
                .orElseGet(
                        () ->
                                notices.save(
                                        new PrivacyNotice(
                                                NOTICE_CONTRACT_URI,
                                                null,
                                                providerId,
                                                consumerId,
                                                new PrivacyNoticePayload(
                                                        null, null, null, null, null, null, null,
                                                        null, null))));
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

    private UUID participantId(String identifier) {
        return participants.findByIdentifier(identifier).orElseThrow().getId();
    }

    /** Removes a user this test created, consents first because the row they name is retained. */
    private void removeIfSeeded(User user) {
        if (!identifier.equals(user.getIdentifier())
                && !user.getIdentifier().startsWith(ERASED_PREFIX)) {
            return;
        }
        consents.findByUserId(user.getId(), Pageable.UNPAGED).forEach(consents::delete);
        links.findByIdUserId(user.getId()).forEach(links::delete);
        users.delete(user);
    }

    private static String userToken() {
        return KeycloakTestResource.accessToken(RealmPrincipal.USER);
    }

    private int count(Map<String, Object> summary, String property) {
        return ((Number) summary.get(property)).intValue();
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
