package com.seamware.consentmanager.notification;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static com.seamware.consentmanager.support.ConsentFixtures.participant;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.repository.ConsentEventRepository;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.PostgresTestResource;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the production path: with delivery enabled, the scheduled {@code poll()} is registered,
 * its {@code poll-interval} binds, and it delivers a queued row without any test calling it.
 */
@MicronautTest(transactional = false)
@Property(name = "consent-manager.participant-notifications.enabled", value = "true")
@Property(
        name = "consent-manager.participant-notifications.poll-interval",
        value = NotificationSchedulingIT.POLL_INTERVAL)
@Property(
        name = "consent-manager.participant-notifications.allowed-hosts",
        value = NotificationSchedulingIT.LOOPBACK_HOST)
@DisplayName("Scheduled notification delivery")
class NotificationSchedulingIT extends PostgresTestResource {

    static final String POLL_INTERVAL = "200ms";

    static final String LOOPBACK_HOST = "localhost";

    private static final String HOOK_PATH = "/hooks/scheduled";

    private static final String CONTRACT_URI = "urn:test:contract:scheduled";

    /** Many poll intervals, so only a tick that never fires times out. */
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(15);

    private static final int OK = 200;

    /** Created at class initialisation, before the context builds the replacing client. */
    private static final WebhookStubTls STUB_TLS = WebhookStubTls.generateUnchecked();

    private static WireMockServer stub;

    @Inject NotificationOutboxRepository outbox;

    @Inject ConsentRepository consents;

    @Inject ConsentEventRepository events;

    @Inject PrivacyNoticeRepository notices;

    @Inject ParticipantRepository participants;

    @Inject UserRepository users;

    @BeforeAll
    static void startStub() {
        stub = new WireMockServer(STUB_TLS.serve(options()));
        stub.start();
    }

    @AfterAll
    static void stopStub() throws IOException {
        stub.stop();
        STUB_TLS.delete();
    }

    /** Adds trust in the stub, so only this suite's client accepts its certificate. */
    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>(super.getProperties());
        properties.putAll(STUB_TLS.clientProperties());
        return properties;
    }

    @Test
    @DisplayName("the scheduled tick delivers a queued row with no direct drain")
    void scheduledTickDeliversAQueuedRow() {
        stub.stubFor(post(urlEqualTo(HOOK_PATH)).willReturn(aResponse().withStatus(OK)));
        UUID id = enqueue();

        Await.until(
                "the scheduled dispatcher delivers the row",
                DELIVERY_TIMEOUT,
                () ->
                        outbox.findById(id).orElseThrow().getStatus()
                                == NotificationOutboxStatus.DELIVERED);

        assertThat(outbox.findById(id).orElseThrow().getAttempts()).isEqualTo(1);
        stub.verify(1, postRequestedFor(urlEqualTo(HOOK_PATH)));
    }

    private UUID enqueue() {
        String identifier = "urn:test:participant:scheduled:" + UUID.randomUUID();
        UUID recipient =
                participants
                        .save(
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
                                                        + HOOK_PATH),
                                        null))
                        .getId();
        UUID party = participant(participants, "urn:test:participant:scheduled-party").getId();
        User subject =
                users.save(
                        new User("urn:test:user:scheduled:" + UUID.randomUUID(), null, null, null));
        PrivacyNotice notice =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI + ":" + UUID.randomUUID(),
                                "Scheduled notice",
                                party,
                                party,
                                new PrivacyNoticePayload(
                                        null, null, null, null, null, null, null, null, null)));
        UUID consentId =
                consents.save(
                                Consent.withStatus(
                                        ConsentStatus.GRANTED,
                                        subject.getId(),
                                        notice.getId(),
                                        party,
                                        party,
                                        null,
                                        notice.getContractUri(),
                                        noTerms()))
                        .getId();
        ConsentEvent event =
                events.save(
                        new ConsentEvent(consentId, ConsentEventState.CONSENT_GIVEN, null, null));
        NotificationPayload payload =
                new NotificationPayload(
                        NotificationEventType.CONSENT_GRANTED,
                        event.getId(),
                        Instant.now().truncatedTo(ChronoUnit.MICROS),
                        consentId,
                        notice.getId(),
                        subject.getIdentifier(),
                        ConsentStatus.GRANTED,
                        true);
        return outbox.save(NotificationOutboxEntry.pending(recipient, payload)).getId();
    }
}
