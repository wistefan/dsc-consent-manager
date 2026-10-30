package com.seamware.consentmanager.api;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static com.seamware.consentmanager.support.ConsentFixtures.participant;
import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.config.ConsentManagerConfiguration.ParticipantNotifications;
import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.notification.NotificationEventType;
import com.seamware.consentmanager.notification.NotificationOutboxEntry;
import com.seamware.consentmanager.notification.NotificationOutboxRepository;
import com.seamware.consentmanager.notification.NotificationPayload;
import com.seamware.consentmanager.repository.ConsentEventRepository;
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
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.uri.UriBuilder;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
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

/**
 * {@code GET /participants/me/notifications} end to end: only the caller's rows, newest first,
 * paged and clamped, rendered from columns only, and still readable after deregistration.
 */
@MicronautTest(transactional = false)
@DisplayName("Participant notification history")
class ParticipantNotificationHistoryIT extends KeycloakAndPostgresTestResource {

    private static final String PATH = "/participants/me/notifications";

    /** Participant the realm's {@code PARTICIPANT} token acts as. */
    private static final String CALLER_PARTICIPANT = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    /** The other side of the seeded consent; its rows must never reach the caller. */
    private static final String OTHER_PARTICIPANT = "urn:test:participant:history-other";

    private static final String SUBJECT = "urn:test:user:history-subject";

    private static final String CONTRACT_URI = "urn:test:contract:history";

    /** Rows queued for the caller. */
    private static final int CALLER_ROWS = 5;

    /** Rows queued for the other participant, interleaved with the caller's. */
    private static final int OTHER_ROWS = 3;

    private static final int SMALL_PAGE = 2;

    private static final Duration LEASE = Duration.ofMinutes(1);

    private static final long RETRY_DELAY_MILLIS = Duration.ofMinutes(5).toMillis();

    private static final String FAILURE = "HTTP 503";

    /** Exactly what a delivered entry publishes: no payload, no subject, no due time. */
    private static final List<String> DELIVERED_PROPERTIES =
            List.of(
                    "id",
                    "eventId",
                    "eventType",
                    "consentId",
                    "status",
                    "attempts",
                    "createdAt",
                    "deliveredAt");

    /** Longest a cold Keycloak may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    @Inject
    @Client("/")
    HttpClient client;

    @Inject ObjectMapper json;

    @Inject IdentityProviderRegistry registry;

    @Inject ParticipantRepository participants;

    @Inject UserRepository users;

    @Inject PrivacyNoticeRepository notices;

    @Inject ConsentRepository consents;

    @Inject ConsentEventRepository events;

    @Inject NotificationOutboxRepository outbox;

    @Inject ParticipantNotifications settings;

    private Participant caller;

    private UUID consentId;

    /** The caller's row ids in the order they were queued, oldest first. */
    private final List<UUID> callerRows = new ArrayList<>();

    @BeforeEach
    void seedBothParticipantsHistories() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        caller = participant(participants, CALLER_PARTICIPANT);
        UUID other = participant(participants, OTHER_PARTICIPANT).getId();
        User subject = users.save(new User(SUBJECT, null, null, null));
        PrivacyNotice notice =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI,
                                "History notice",
                                caller.getId(),
                                other,
                                new PrivacyNoticePayload(
                                        null, null, null, null, null, null, null, null, null)));
        consentId =
                consents.save(
                                Consent.withStatus(
                                        ConsentStatus.GRANTED,
                                        subject.getId(),
                                        notice.getId(),
                                        caller.getId(),
                                        other,
                                        null,
                                        CONTRACT_URI,
                                        noTerms()))
                        .getId();
        callerRows.clear();
        for (int i = 0; i < CALLER_ROWS; i++) {
            callerRows.add(enqueue(caller.getId()));
            if (i < OTHER_ROWS) {
                enqueue(other);
            }
        }
    }

    @Test
    @DisplayName("returns only the caller's rows, newest first")
    void returnsOnlyTheCallersRowsNewestFirst() throws IOException {
        Map<String, Object> page = page(get(PATH));

        assertThat(ids(page)).containsExactlyElementsOf(callerRows.reversed());
        assertThat(page)
                .containsEntry("page", 0)
                .containsEntry("size", settings.getPageDefaultSize())
                .containsEntry("totalElements", CALLER_ROWS)
                .containsEntry("totalPages", 1);
    }

    @Test
    @DisplayName("pages through the history at the requested size")
    void pagesThroughTheHistory() throws IOException {
        List<UUID> seen = new ArrayList<>();
        int pages = (CALLER_ROWS + SMALL_PAGE - 1) / SMALL_PAGE;
        for (int number = 0; number < pages; number++) {
            Map<String, Object> page = page(get(uri(number, SMALL_PAGE)));

            assertThat(page)
                    .containsEntry("page", number)
                    .containsEntry("size", SMALL_PAGE)
                    .containsEntry("totalElements", CALLER_ROWS)
                    .containsEntry("totalPages", pages);
            seen.addAll(ids(page));
        }

        assertThat(seen).containsExactlyElementsOf(callerRows.reversed());
        assertThat(ids(page(get(uri(pages, SMALL_PAGE))))).isEmpty();
    }

    @Test
    @DisplayName("clamps a size above the configured ceiling instead of refusing it")
    void clampsAnOversizedPage() throws IOException {
        HttpResponse<String> response = get(uri(0, settings.getPageMaxSize() + 1));

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(page(response)).containsEntry("size", settings.getPageMaxSize());
    }

    static Stream<Arguments> outOfBoundsPaging() {
        return Stream.of(Arguments.of(-1, SMALL_PAGE), Arguments.of(0, 0));
    }

    @ParameterizedTest(name = "page {0}, size {1}")
    @MethodSource("outOfBoundsPaging")
    @DisplayName("refuses paging below the specified bounds")
    void refusesPagingBelowTheBounds(int number, int size) {
        assertThat(get(uri(number, size)).code()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
    }

    @Test
    @DisplayName("renders outcomes from the columns, never the payload")
    void rendersOutcomesFromColumns() throws IOException {
        UUID delivered = callerRows.get(0);
        UUID failed = callerRows.get(1);
        UUID token = UUID.randomUUID();
        outbox.lease(List.of(delivered, failed), token, LEASE);
        assertThat(outbox.markDelivered(delivered, token)).isPresent();
        assertThat(outbox.markFailed(failed, token, RETRY_DELAY_MILLIS, FAILURE)).isOne();

        Map<UUID, Map<String, Object>> entries = byId(page(get(PATH)));

        assertThat(entries.get(delivered))
                .containsOnlyKeys(DELIVERED_PROPERTIES)
                .containsEntry("status", "DELIVERED")
                .containsEntry("attempts", 1)
                .containsEntry("eventType", NotificationEventType.CONSENT_REVOKED.name())
                .containsEntry("consentId", consentId.toString());
        assertThat(entries.get(failed))
                .containsEntry("status", "FAILED")
                .containsEntry("attempts", 1)
                .containsEntry("lastError", FAILURE)
                .containsKey("nextAttemptAt")
                .doesNotContainKey("deliveredAt");
        assertThat(entries.get(callerRows.get(2)))
                .containsEntry("status", "PENDING")
                .containsEntry("attempts", 0)
                .containsKey("nextAttemptAt")
                .doesNotContainKeys("deliveredAt", "lastError");
    }

    @Test
    @DisplayName("stays readable by a participant that has deregistered")
    void staysReadableAfterDeregistration() throws IOException {
        caller.setDeregisteredAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        participants.update(caller);

        HttpResponse<String> response = get(PATH);

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(ids(page(response))).containsExactlyElementsOf(callerRows.reversed());
    }

    private UUID enqueue(UUID participantId) {
        ConsentEvent event =
                events.save(
                        new ConsentEvent(consentId, ConsentEventState.CONSENT_REVOKED, null, null));
        NotificationPayload payload =
                new NotificationPayload(
                        NotificationEventType.CONSENT_REVOKED,
                        event.getId(),
                        Instant.now().truncatedTo(ChronoUnit.MICROS),
                        consentId,
                        UUID.randomUUID(),
                        SUBJECT,
                        ConsentStatus.REVOKED,
                        false);
        return outbox.save(NotificationOutboxEntry.pending(participantId, payload)).getId();
    }

    private static String uri(int page, int size) {
        return UriBuilder.of(PATH)
                .queryParam("page", page)
                .queryParam("size", size)
                .build()
                .toString();
    }

    /** Reads as the realm's participant, returning a refusal rather than throwing. */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> get(String uri) {
        try {
            return client.toBlocking()
                    .exchange(
                            HttpRequest.GET(uri)
                                    .bearerAuth(
                                            KeycloakTestResource.accessToken(
                                                    RealmPrincipal.PARTICIPANT)),
                            String.class);
        } catch (HttpClientResponseException e) {
            return (HttpResponse<String>) e.getResponse();
        }
    }

    private Map<String, Object> page(HttpResponse<String> response) throws IOException {
        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        return json.readValue(response.body(), Argument.mapOf(String.class, Object.class));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> content(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("content");
    }

    private static List<UUID> ids(Map<String, Object> page) {
        return content(page).stream()
                .map(entry -> UUID.fromString((String) entry.get("id")))
                .toList();
    }

    private static Map<UUID, Map<String, Object>> byId(Map<String, Object> page) {
        Map<UUID, Map<String, Object>> entries = new HashMap<>();
        content(page)
                .forEach(entry -> entries.put(UUID.fromString((String) entry.get("id")), entry));
        return entries;
    }
}
