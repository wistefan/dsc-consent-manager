package com.seamware.consentmanager.notification;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.repository.ConsentEventRepository;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.support.PostgresTestResource;
import com.seamware.consentmanager.support.PrivacyNoticeFixtures;
import com.seamware.consentmanager.support.Race;
import com.seamware.consentmanager.support.RawSql;
import com.seamware.consentmanager.support.TestJson;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.core.type.Argument;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two replicas, each its own {@link ApplicationContext} on the one database, drain the same outbox
 * concurrently; every {@code (eventId, participant)} must reach the recipient exactly once.
 */
@MicronautTest(transactional = false)
@DisplayName("Notification delivery across replicas")
class NotificationReplicaConcurrencyIT extends PostgresTestResource {

    private static final String LOOPBACK_HOST = "localhost";

    private static final String HOOK_PATH_PREFIX = "/hooks/replica/";

    private static final int REPLICAS = 2;

    private static final int PARTICIPANTS = 4;

    /** Each event fans out to two participants, as a provider and a consumer would. */
    private static final int EVENTS = 24;

    private static final int RECIPIENTS_PER_EVENT = 2;

    /** Small batches with a low per-participant cap, so the replicas claim many times each. */
    private static final int BATCH_SIZE = 8;

    private static final int CONCURRENCY = 4;

    private static final int PER_PARTICIPANT_BATCH_LIMIT = 3;

    /** A short, varying answer delay, so claims and outcomes of the two replicas interleave. */
    private static final int MIN_ANSWER_DELAY_MILLIS = 5;

    private static final int MAX_ANSWER_DELAY_MILLIS = 30;

    /** Enough Jetty threads that both replicas' whole pools are accepted at once. */
    private static final int STUB_THREADS = 32;

    private static final int OK = 200;

    private static final String COUNT_UNFINISHED =
            "SELECT count(*) FROM notification_outbox WHERE status IN ('PENDING', 'FAILED')";

    /** Created at class initialisation, before any replica builds its replacing client. */
    private static final WebhookStubTls STUB_TLS = WebhookStubTls.generateUnchecked();

    private static WireMockServer stub;

    @Inject NotificationOutboxRepository outbox;

    @Inject ParticipantRepository participants;

    @Inject UserRepository users;

    @Inject PrivacyNoticeRepository notices;

    @Inject ConsentRepository consents;

    @Inject ConsentEventRepository events;

    private final List<ApplicationContext> replicas = new ArrayList<>();

    @BeforeAll
    static void startStub() {
        stub = new WireMockServer(STUB_TLS.serve(options().containerThreads(STUB_THREADS)));
        stub.start();
        stub.stubFor(
                post(urlPathMatching(HOOK_PATH_PREFIX + ".*"))
                        .willReturn(
                                aResponse()
                                        .withStatus(OK)
                                        .withUniformRandomDelay(
                                                MIN_ANSWER_DELAY_MILLIS, MAX_ANSWER_DELAY_MILLIS)));
    }

    @AfterAll
    static void stopStub() throws IOException {
        stub.stop();
        STUB_TLS.delete();
    }

    @AfterEach
    void stopReplicas() {
        replicas.forEach(ApplicationContext::close);
        replicas.clear();
    }

    @Test
    @DisplayName("two replicas draining concurrently deliver each event to each party exactly once")
    void deliversEachEventToEachPartyExactlyOnce() {
        List<Participant> recipients =
                IntStream.range(0, PARTICIPANTS).mapToObj(this::recipient).toList();
        Map<String, UUID> participantByPath =
                recipients.stream()
                        .collect(
                                Collectors.toMap(
                                        participant -> hookPath(participant.getIdentifier()),
                                        Participant::getId));
        List<String> expected = enqueue(recipients);
        List<NotificationDispatcher> dispatchers =
                IntStream.range(0, REPLICAS).mapToObj(i -> startReplica()).toList();

        AtomicInteger nextReplica = new AtomicInteger();
        List<Race.Outcome<Integer>> outcomes =
                Race.run(
                        REPLICAS,
                        () -> drainUntilFinished(dispatchers.get(nextReplica.getAndIncrement())));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.succeeded()).isTrue());
        List<String> received =
                stub.getAllServeEvents().stream()
                        .map(event -> delivery(event.getRequest(), participantByPath))
                        .toList();
        assertThat(received)
                .as("each (eventId, participant) exactly once")
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(StreamSupport.stream(outbox.findAll().spliterator(), false))
                .allSatisfy(
                        row -> {
                            assertThat(row.getStatus())
                                    .isEqualTo(NotificationOutboxStatus.DELIVERED);
                            assertThat(row.getAttempts()).isOne();
                        });
    }

    /** Drains until no row is left to deliver, returning how many drains it ran. */
    private static int drainUntilFinished(NotificationDispatcher dispatcher) {
        int drains = 0;
        while (RawSql.count(COUNT_UNFINISHED) > 0 && !Thread.currentThread().isInterrupted()) {
            dispatcher.drainOnce();
            drains++;
        }
        return drains;
    }

    /** A replica on the same database, trusting the stub and delivering only when drained. */
    private NotificationDispatcher startReplica() {
        Map<String, Object> properties = new LinkedHashMap<>(getProperties());
        properties.putAll(STUB_TLS.clientProperties());
        properties.put("consent-manager.participant-notifications.enabled", false);
        properties.put("consent-manager.participant-notifications.allowed-hosts", LOOPBACK_HOST);
        properties.put("consent-manager.participant-notifications.batch-size", BATCH_SIZE);
        properties.put("consent-manager.participant-notifications.concurrency", CONCURRENCY);
        properties.put(
                "consent-manager.participant-notifications.per-participant-batch-limit",
                PER_PARTICIPANT_BATCH_LIMIT);
        ApplicationContext replica =
                ApplicationContext.builder(Environment.TEST)
                        .deduceEnvironment(false)
                        .environmentPropertySource(false)
                        .properties(properties)
                        .start();
        replicas.add(replica);
        return replica.getBean(NotificationDispatcher.class);
    }

    /** Queues {@link #EVENTS} events, each for two neighbouring participants; returns the pairs. */
    private List<String> enqueue(List<Participant> recipients) {
        UUID consentId = seedConsent(recipients.get(0), recipients.get(1));
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < EVENTS; i++) {
            ConsentEvent event =
                    events.save(
                            new ConsentEvent(
                                    consentId, ConsentEventState.CONSENT_GIVEN, null, null));
            NotificationPayload payload =
                    new NotificationPayload(
                            NotificationEventType.CONSENT_GRANTED,
                            event.getId(),
                            Instant.now().truncatedTo(ChronoUnit.MICROS),
                            consentId,
                            UUID.randomUUID(),
                            "urn:test:user:replica",
                            ConsentStatus.GRANTED,
                            true);
            for (int r = 0; r < RECIPIENTS_PER_EVENT; r++) {
                UUID participantId = recipients.get((i + r) % PARTICIPANTS).getId();
                outbox.save(NotificationOutboxEntry.pending(participantId, payload));
                expected.add(pair(event.getId(), participantId));
            }
        }
        return expected;
    }

    private UUID seedConsent(Participant provider, Participant consumer) {
        User subject = users.save(new User("urn:test:user:replica", null, null, null));
        PrivacyNotice notice =
                notices.save(
                        new PrivacyNotice(
                                "urn:test:contract:replica",
                                "Replica notice",
                                provider.getId(),
                                consumer.getId(),
                                PrivacyNoticeFixtures.emptyPayload()));
        return consents.save(
                        Consent.withStatus(
                                ConsentStatus.GRANTED,
                                subject.getId(),
                                notice.getId(),
                                provider.getId(),
                                consumer.getId(),
                                null,
                                notice.getContractUri(),
                                noTerms()))
                .getId();
    }

    private Participant recipient(int index) {
        String identifier = "urn:test:participant:replica-" + index;
        return participants.save(
                new Participant(
                        identifier,
                        identifier,
                        null,
                        null,
                        Map.of(
                                Participant.CONSENT_NOTIFICATION_ENDPOINT,
                                "https://"
                                        + LOOPBACK_HOST
                                        + ":"
                                        + stub.httpsPort()
                                        + hookPath(identifier)),
                        null));
    }

    /** The {@code (eventId, participant)} a received request delivered. */
    private static String delivery(LoggedRequest request, Map<String, UUID> participantByPath) {
        try {
            Map<String, Object> body =
                    TestJson.mapper()
                            .readValue(
                                    request.getBodyAsString(),
                                    Argument.mapOf(String.class, Object.class));
            return pair(
                    UUID.fromString((String) body.get("eventId")),
                    participantByPath.get(request.getUrl()));
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static String pair(UUID eventId, UUID participantId) {
        return eventId + "@" + participantId;
    }

    private static String hookPath(String identifier) {
        return HOOK_PATH_PREFIX + identifier.substring(identifier.lastIndexOf(':') + 1);
    }
}
