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
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Exercises {@code DELETE /participants/me} end to end: the summary the route actually returns, and
 * what the caller's token may still do once its organization has left.
 *
 * <p>{@code ParticipantDeregistrationIT} proves the cascade itself against the service. What is
 * untested there is the controller's own contribution - four same-typed counts handed to one
 * constructor, where a transposition compiles - and the refusal of the write routes afterwards,
 * which lives in {@link com.seamware.consentmanager.security.ParticipantPrincipal#requireActive()}
 * and is only reachable through HTTP.
 */
@MicronautTest(transactional = false)
@DisplayName("Participant deregistration over HTTP")
class ParticipantDeregistrationSurfaceIT extends KeycloakAndPostgresTestResource {

    private static final String PATH = "/participants/me";

    private static final String PATH_PARTICIPANT_USERS = PATH + "/users";

    private static final String PATH_BULK = PATH_PARTICIPANT_USERS + "/bulk";

    /** Participant the realm's {@code PARTICIPANT} token acts as. */
    private static final String CALLER_PARTICIPANT = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    private static final String COUNTERPARTY_PREFIX =
            "urn:test:participant:deregistration-surface:";

    private static final String AFFILIATED_USER = "urn:test:user:deregistration-surface";

    private static final String LOCAL_IDENTIFIER = "deregistration-surface-local";

    /**
     * Stem of each seeded notice's contract URI; a per-notice suffix keeps {@code
     * uq_privacy_notices_active_contract} satisfied, since one provider may hold only one active
     * notice per contract.
     */
    private static final String CONTRACT_URI_PREFIX = "urn:test:contract:deregistration-surface:";

    private static final String LEGAL_NAME = "Departing Surface Clinic Ltd";

    private static final String SELF_DESCRIPTION_URI = "https://departing.example.org/sd.json";

    private static final String CONTACT_EMAIL = "privacy-officer@departing.example.org";

    /** Unanswered consents in the fixture, all of which the cascade terminates. */
    private static final int UNANSWERED_CONSENTS = 2;

    /** Already-answered consents in the fixture, which the cascade leaves alone. */
    private static final int ANSWERED_CONSENTS = 1;

    /** Live notices in the fixture beyond the one each seeded consent brings with it. */
    private static final int STANDALONE_NOTICES = 1;

    /** Affiliations in the fixture, all of which the cascade removes. */
    private static final int AFFILIATIONS = 1;

    private static final int EXPECTED_CONSENTS_TERMINATED = UNANSWERED_CONSENTS;

    private static final int EXPECTED_CONSENTS_RETAINED = UNANSWERED_CONSENTS + ANSWERED_CONSENTS;

    private static final int EXPECTED_NOTICES_ARCHIVED =
            EXPECTED_CONSENTS_RETAINED + STANDALONE_NOTICES;

    private static final int EXPECTED_LINKS_REMOVED = AFFILIATIONS;

    /** An event already on the trail before the cascade runs. */
    private static final ConsentEventState PRIOR_EVENT_STATE = ConsentEventState.CONSENT_REQUESTED;

    /** Longest a cold Keycloak container may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    @Inject
    @Client("/")
    HttpClient client;

    @Inject ObjectMapper json;

    @Inject IdentityProviderRegistry registry;

    @Inject ParticipantRepository participants;

    @Inject ConsentRepository consents;

    @Inject ConsentEventRepository events;

    @Inject PrivacyNoticeRepository notices;

    @Inject UserParticipantRepository links;

    @Inject UserRepository users;

    private User user;

    /** Distinguishes the contract URI of each seeded notice. */
    private int seededNotices;

    @BeforeEach
    void awaitTheRealmAndSeedTheCaller() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        removeCommittedRows();
        seededNotices = 0;
        participants.save(participant(CALLER_PARTICIPANT));
        user = users.save(new User(AFFILIATED_USER, null, null, null));
    }

    /** Rows committed by these tests outlive the transaction, so they are removed by hand. */
    @AfterEach
    void removeCommittedRows() {
        Set<UUID> ids = new HashSet<>();
        participants
                .findAll()
                .forEach(
                        stored -> {
                            if (seededByThisTest(stored.getIdentifier())) {
                                ids.add(stored.getId());
                            }
                        });
        consents.findAll()
                .forEach(
                        consent -> {
                            if (names(ids, consent.getProviderId(), consent.getConsumerId())) {
                                events.findByConsentIdOrderByOccurredAtAsc(consent.getId())
                                        .forEach(events::delete);
                                consents.delete(consent);
                            }
                        });
        notices.findAll()
                .forEach(
                        notice -> {
                            if (names(ids, notice.getProviderId(), notice.getConsumerId())) {
                                notices.delete(notice);
                            }
                        });
        ids.forEach(links::deleteByIdParticipantId);
        ids.forEach(id -> participants.findById(id).ifPresent(participants::delete));
        users.findByIdentifier(AFFILIATED_USER).ifPresent(users::delete);
        user = null;
    }

    @Test
    @DisplayName("reports each count against its own field and the retained record's departure")
    void reportsEachCountAgainstItsOwnField() throws IOException {
        seedBusiness();

        HttpResponse<String> response = exchange(HttpRequest.DELETE(PATH));

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        Map<String, Object> body = body(response);
        assertThat(body)
                .as("the counts are pairwise distinct, so a transposed argument shows up here")
                .containsEntry("consentsTerminated", EXPECTED_CONSENTS_TERMINATED)
                .containsEntry("consentsRetained", EXPECTED_CONSENTS_RETAINED)
                .containsEntry("noticesArchived", EXPECTED_NOTICES_ARCHIVED)
                .containsEntry("linksRemoved", EXPECTED_LINKS_REMOVED);
        Participant retained = participants.findByIdentifier(CALLER_PARTICIPANT).orElseThrow();
        assertThat(retained.getDeregisteredAt()).isNotNull();
        assertThat(OffsetDateTime.parse((String) body.get("deregisteredAt")).toInstant())
                .as("the reported departure is the one the row carries")
                .isEqualTo(retained.getDeregisteredAt());
    }

    @Test
    @DisplayName("omits the departure time when the record is deleted outright")
    void omitsTheDepartureTimeWhenTheRecordIsDeleted() throws IOException {
        links.save(new UserParticipant(user.getId(), caller().getId(), LOCAL_IDENTIFIER));

        HttpResponse<String> response = exchange(HttpRequest.DELETE(PATH));

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(body(response))
                .doesNotContainKey("deregisteredAt")
                .containsEntry("consentsTerminated", 0)
                .containsEntry("consentsRetained", 0)
                .containsEntry("noticesArchived", 0)
                .containsEntry("linksRemoved", EXPECTED_LINKS_REMOVED);
        assertThat(participants.findByIdentifier(CALLER_PARTICIPANT))
                .as("nothing referred to the record, so it is gone rather than marked")
                .isEmpty();
    }

    @ParameterizedTest(name = "{0} is refused")
    @EnumSource(WriteRoute.class)
    @DisplayName("a departed participant can no longer act in the dataspace")
    void aDepartedParticipantCanNoLongerAct(WriteRoute route) {
        seedBusiness();
        assertThat(exchange(HttpRequest.DELETE(PATH)).code()).isEqualTo(HttpStatus.OK.getCode());

        HttpResponse<String> response = exchange(route.request());

        assertThat(response.code()).isEqualTo(HttpStatus.FORBIDDEN.getCode());
        assertThat(links.findByIdParticipantId(caller().getId()))
                .as("the affiliations the cascade removed must stay removed")
                .isEmpty();
    }

    @Test
    @DisplayName("a departed participant still reads its own record back")
    void aDepartedParticipantStillReadsItsOwnRecord() throws IOException {
        seedBusiness();
        exchange(HttpRequest.DELETE(PATH));

        HttpResponse<String> response = exchange(HttpRequest.GET(PATH));

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(body(response)).containsEntry("identifier", CALLER_PARTICIPANT);
    }

    @Test
    @DisplayName("a repeated deregistration is a conflict, not a second departure")
    void aRepeatedDeregistrationIsAConflict() {
        seedBusiness();
        exchange(HttpRequest.DELETE(PATH));
        Participant departed = participants.findByIdentifier(CALLER_PARTICIPANT).orElseThrow();

        HttpResponse<String> response = exchange(HttpRequest.DELETE(PATH));

        assertThat(response.code()).isEqualTo(HttpStatus.CONFLICT.getCode());
        assertThat(participants.findByIdentifier(CALLER_PARTICIPANT).orElseThrow())
                .extracting(Participant::getDeregisteredAt)
                .as("the recorded departure time may not move")
                .isEqualTo(departed.getDeregisteredAt());
    }

    /**
     * The operations that act in the dataspace in the participant's name, as opposed to reading or
     * amending its own record. Each carries its own request, because a parameterized test may not
     * take a {@code Supplier} parameter - Micronaut's JUnit extension claims it as a bean.
     */
    enum WriteRoute {
        REGISTER_USER(
                () ->
                        HttpRequest.POST(
                                PATH_PARTICIPANT_USERS, Map.of("identifier", AFFILIATED_USER))),
        BULK_REGISTER_USERS(
                () ->
                        HttpRequest.POST(
                                PATH_BULK,
                                Map.of("users", List.of(Map.of("identifier", AFFILIATED_USER))))),
        UPDATE_LINK(
                () ->
                        HttpRequest.PATCH(
                                PATH_PARTICIPANT_USERS + "/" + AFFILIATED_USER,
                                Map.of("localIdentifier", LOCAL_IDENTIFIER))),
        UNLINK(() -> HttpRequest.DELETE(PATH_PARTICIPANT_USERS + "/" + AFFILIATED_USER));

        private final Supplier<MutableHttpRequest<?>> factory;

        WriteRoute(Supplier<MutableHttpRequest<?>> factory) {
            this.factory = factory;
        }

        MutableHttpRequest<?> request() {
            return factory.get();
        }
    }

    /**
     * Gives the caller an affiliation, three consents and four live notices, so every count in the
     * summary is non-zero and no two of them are equal.
     */
    private void seedBusiness() {
        Participant caller = caller();
        Participant counterparty = participants.save(participant(COUNTERPARTY_PREFIX + "other"));
        links.save(new UserParticipant(user.getId(), caller.getId(), LOCAL_IDENTIFIER));
        for (int i = 0; i < UNANSWERED_CONSENTS; i++) {
            seedConsent(caller, counterparty, ConsentStatus.PENDING);
        }
        for (int i = 0; i < ANSWERED_CONSENTS; i++) {
            seedConsent(caller, counterparty, ConsentStatus.REVOKED);
        }
        for (int i = 0; i < STANDALONE_NOTICES; i++) {
            seedNotice(caller, counterparty);
        }
    }

    private Participant caller() {
        return participants.findByIdentifier(CALLER_PARTICIPANT).orElseThrow();
    }

    private static Participant participant(String identifier) {
        return new Participant(
                identifier, LEGAL_NAME, SELF_DESCRIPTION_URI, CONTACT_EMAIL, Map.of(), Map.of());
    }

    private PrivacyNotice seedNotice(Participant provider, Participant consumer) {
        return notices.save(
                new PrivacyNotice(
                        CONTRACT_URI_PREFIX + seededNotices++,
                        null,
                        provider.getId(),
                        consumer.getId(),
                        new PrivacyNoticePayload(
                                null, null, null, null, null, null, null, null, null)));
    }

    /** A consent in the given status, with its notice and one event already on its trail. */
    private void seedConsent(Participant provider, Participant consumer, ConsentStatus status) {
        PrivacyNotice notice = seedNotice(provider, consumer);
        Consent consent =
                consents.save(
                        Consent.withStatus(
                                status,
                                user.getId(),
                                notice.getId(),
                                notice.getProviderId(),
                                notice.getConsumerId(),
                                null,
                                notice.getContractUri(),
                                new ConsentSnapshot(null, null, null, null, null)));
        events.save(new ConsentEvent(consent.getId(), PRIOR_EVENT_STATE, null, null));
    }

    private boolean seededByThisTest(String identifier) {
        return identifier.equals(CALLER_PARTICIPANT) || identifier.startsWith(COUNTERPARTY_PREFIX);
    }

    private static boolean names(Set<UUID> ids, UUID providerId, UUID consumerId) {
        return ids.contains(providerId) || ids.contains(consumerId);
    }

    private Map<String, Object> body(HttpResponse<String> response) throws IOException {
        return json.readValue(response.body(), Argument.mapOf(String.class, Object.class));
    }

    /** Issues the request as the realm's participant, returning a refusal rather than throwing. */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> exchange(MutableHttpRequest<?> request) {
        try {
            return client.toBlocking()
                    .exchange(
                            request.bearerAuth(
                                    KeycloakTestResource.accessToken(RealmPrincipal.PARTICIPANT)),
                            String.class);
        } catch (HttpClientResponseException e) {
            return (HttpResponse<String>) e.getResponse();
        }
    }
}
