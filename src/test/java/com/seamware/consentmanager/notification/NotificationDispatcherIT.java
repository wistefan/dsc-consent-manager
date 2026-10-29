package com.seamware.consentmanager.notification;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static com.seamware.consentmanager.support.ConsentFixtures.participant;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.seamware.consentmanager.config.ConsentManagerConfiguration.ParticipantNotifications;
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
import com.seamware.consentmanager.signing.ConsentSigningService;
import com.seamware.consentmanager.signing.SignatureType;
import com.seamware.consentmanager.support.PostgresTestResource;
import io.micronaut.http.client.DefaultHttpClientConfiguration;
import io.micronaut.http.client.RawHttpClient;
import io.micronaut.jdbc.DataSourceResolver;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.transaction.TransactionOperations;
import jakarta.inject.Inject;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests delivery against a WireMock webhook and the real outbox: retry with backoff, abandonment,
 * the lease fence, the send deadline, the address fence and per-participant isolation.
 */
@MicronautTest(transactional = false)
@DisplayName("Notification dispatcher")
class NotificationDispatcherIT extends PostgresTestResource {

    private static final String LOOPBACK_HOST = "localhost";

    private static final String HOOK_PATH = "/hooks/consent";

    private static final String STALLED_PATH = "/hooks/stalled";

    private static final String CONTRACT_URI = "urn:test:contract:dispatch";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);

    private static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(1);

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    /** How long a stalled endpoint holds its response: well past the read timeout. */
    private static final Duration STALL = READ_TIMEOUT.multipliedBy(2);

    /** Enough Jetty threads that every concurrent delivery is accepted at once. */
    private static final int STUB_THREADS = 64;

    private static final int OK = 200;

    private static final int BAD_REQUEST = 400;

    private static final int TOO_MANY_REQUESTS = 429;

    private static final int SERVICE_UNAVAILABLE = 503;

    private static final String SERVER_ERROR = "HTTP 503";

    /** Slack for {@code markFailed} storing the delay in whole milliseconds. */
    private static final Duration DELAY_TRUNCATION = Duration.ofMillis(1);

    private static WebhookStubTls stubTls;

    private static WireMockServer stub;

    @Inject NotificationOutboxRepository outbox;

    @Inject ConsentRepository consents;

    @Inject ConsentEventRepository events;

    @Inject PrivacyNoticeRepository notices;

    @Inject ParticipantRepository participants;

    @Inject UserRepository users;

    @Inject TransactionOperations<Connection> transactions;

    @Inject ConsentSigningService signing;

    @Inject DataSource dataSource;

    @Inject DataSourceResolver dataSources;

    private final List<AutoCloseable> opened = new ArrayList<>();

    private UUID consentId;

    @BeforeAll
    static void startStub() throws IOException, InterruptedException {
        stubTls = WebhookStubTls.generate();
        stub = new WireMockServer(stubTls.serve(options().containerThreads(STUB_THREADS)));
        stub.start();
    }

    @AfterAll
    static void stopStub() throws IOException {
        stub.stop();
        stubTls.delete();
    }

    @BeforeEach
    void seed() {
        stub.resetAll();
        // Rows other suites left due would otherwise join this suite's batches.
        execute("DELETE FROM notification_outbox");
        UUID party = participant(participants, "urn:test:participant:dispatch-party").getId();
        User subject =
                users.save(
                        new User("urn:test:user:dispatch:" + UUID.randomUUID(), null, null, null));
        PrivacyNotice notice =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI + ":" + UUID.randomUUID(),
                                "Dispatch notice",
                                party,
                                party,
                                new PrivacyNoticePayload(
                                        null, null, null, null, null, null, null, null, null)));
        consentId =
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
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable resource : opened.reversed()) {
            resource.close();
        }
        opened.clear();
    }

    @Test
    @DisplayName("a 5xx schedules a retry inside the jitter window, which resends the same body")
    void serverErrorRetriesWithTheSameSignedBody() {
        stub.stubFor(
                post(urlEqualTo(HOOK_PATH))
                        .willReturn(aResponse().withStatus(SERVICE_UNAVAILABLE)));
        UUID id = enqueue(recipient(hookUrl()));
        NotificationDispatcher dispatcher = dispatcher();

        Instant before = databaseNow();
        dispatcher.drainOnce();
        Instant after = databaseNow();

        NotificationOutboxEntry failed = outbox.findById(id).orElseThrow();
        Duration firstDelay = ParticipantNotifications.DEFAULT_BACKOFF.getFirst();
        double jitter = ParticipantNotifications.DEFAULT_JITTER;
        assertThat(failed.getStatus()).isEqualTo(NotificationOutboxStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getLastError()).isEqualTo(SERVER_ERROR);
        assertThat(failed.getNextAttemptAt())
                .isBetween(
                        before.plus(scaled(firstDelay, 1 - jitter)).minus(DELAY_TRUNCATION),
                        after.plus(scaled(firstDelay, 1 + jitter)));

        stub.stubFor(post(urlEqualTo(HOOK_PATH)).willReturn(aResponse().withStatus(OK)));
        makeDue(id);
        dispatcher.drainOnce();

        NotificationOutboxEntry delivered = outbox.findById(id).orElseThrow();
        assertThat(delivered.getStatus()).isEqualTo(NotificationOutboxStatus.DELIVERED);
        assertThat(delivered.getAttempts()).isEqualTo(2);
        assertThat(delivered.getPayload()).isNull();
        assertThat(delivered.getDeliveredAt()).isNotNull();
        List<LoggedRequest> received = stub.findAll(postRequestedFor(urlEqualTo(HOOK_PATH)));
        assertThat(received).hasSize(2);
        assertThat(received.get(1).getBodyAsString())
                .isEqualTo(received.get(0).getBodyAsString())
                .contains(failed.getEventId().toString());
        assertThat(received)
                .allSatisfy(
                        request ->
                                assertThat(
                                                signing.verifyCanonical(
                                                        request.getBodyAsString(),
                                                        request.getHeader(
                                                                ParticipantWebhookClient
                                                                        .SIGNATURE_HEADER),
                                                        SignatureType.NOTIFICATION))
                                        .isTrue());
    }

    @Test
    @DisplayName("a failure past the last backoff entry abandons the row and drops its payload")
    void walksTheScheduleToAbandonment() {
        stub.stubFor(
                post(urlEqualTo(HOOK_PATH))
                        .willReturn(aResponse().withStatus(SERVICE_UNAVAILABLE)));
        UUID id = enqueue(recipient(hookUrl()));
        NotificationDispatcher dispatcher = dispatcher();
        int attempts = ParticipantNotifications.DEFAULT_BACKOFF.size() + 1;

        for (int attempt = 1; attempt < attempts; attempt++) {
            dispatcher.drainOnce();
            NotificationOutboxEntry row = outbox.findById(id).orElseThrow();
            assertThat(row.getStatus())
                    .as("after attempt %d", attempt)
                    .isEqualTo(NotificationOutboxStatus.FAILED);
            assertThat(row.getAttempts()).isEqualTo(attempt);
            makeDue(id);
        }
        dispatcher.drainOnce();

        NotificationOutboxEntry abandoned = outbox.findById(id).orElseThrow();
        assertThat(abandoned.getStatus()).isEqualTo(NotificationOutboxStatus.ABANDONED);
        assertThat(abandoned.getAttempts()).isEqualTo(attempts);
        assertThat(abandoned.getLastError()).isEqualTo(SERVER_ERROR);
        assertThat(abandoned.getPayload()).isNull();
        stub.verify(attempts, postRequestedFor(urlEqualTo(HOOK_PATH)));
    }

    static Stream<Arguments> responses() {
        return Stream.of(
                Arguments.of(OK, NotificationOutboxStatus.DELIVERED, null),
                Arguments.of(BAD_REQUEST, NotificationOutboxStatus.ABANDONED, "HTTP 400"),
                Arguments.of(TOO_MANY_REQUESTS, NotificationOutboxStatus.FAILED, "HTTP 429"));
    }

    @ParameterizedTest(name = "HTTP {0} leaves the row {1} after one attempt")
    @MethodSource("responses")
    void classifiesTheResponse(int code, NotificationOutboxStatus expected, String lastError) {
        stub.stubFor(post(urlEqualTo(HOOK_PATH)).willReturn(aResponse().withStatus(code)));
        UUID id = enqueue(recipient(hookUrl()));

        dispatcher().drainOnce();

        NotificationOutboxEntry row = outbox.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(expected);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).isEqualTo(lastError);
        assertThat(row.getPayload() == null)
                .as("only a terminal row drops its payload")
                .isEqualTo(expected != NotificationOutboxStatus.FAILED);
        assertThat(row.getLeaseToken()).isNull();
    }

    static Stream<Arguments> unreachableRecipients() {
        return Stream.of(
                Arguments.of(
                        "no consentNotification endpoint",
                        (Consumer<Participant>) p -> p.setEndpoints(Map.of()),
                        NotificationDispatcher.NO_ENDPOINT),
                Arguments.of(
                        "a deregistered participant",
                        (Consumer<Participant>) p -> p.setDeregisteredAt(Instant.now()),
                        NotificationOutboxRepository.AbandonReason.PARTICIPANT_DEREGISTERED));
    }

    @ParameterizedTest(name = "{0} abandons without an HTTP call")
    @MethodSource("unreachableRecipients")
    void abandonsAnUnreachableRecipient(
            String description, Consumer<Participant> change, String reason) {
        stub.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(OK)));
        Participant recipient = participants.findById(recipient(hookUrl())).orElseThrow();
        change.accept(recipient);
        participants.update(recipient);
        UUID id = enqueue(recipient.getId());

        dispatcher().drainOnce();

        NotificationOutboxEntry row = outbox.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(NotificationOutboxStatus.ABANDONED);
        assertThat(row.getLastError()).isEqualTo(reason);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getPayload()).isNull();
        stub.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("an expired lease is re-claimed and delivered; the stale holder's outcome is void")
    void reclaimsAnExpiredLease() {
        stub.stubFor(post(urlEqualTo(HOOK_PATH)).willReturn(aResponse().withStatus(OK)));
        UUID id = enqueue(recipient(hookUrl()));
        UUID staleToken = UUID.randomUUID();
        outbox.lease(List.of(id), staleToken, ParticipantNotifications.DEFAULT_LEASE);
        makeDue(id);

        dispatcher().drainOnce();

        assertThat(outbox.findById(id).orElseThrow().getStatus())
                .isEqualTo(NotificationOutboxStatus.DELIVERED);
        assertThat(outbox.markFailed(id, staleToken, 0, SERVER_ERROR))
                .as("the stale holder's late outcome")
                .isZero();
        assertThat(outbox.markAbandoned(id, staleToken, SERVER_ERROR)).isZero();
        NotificationOutboxEntry row = outbox.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(NotificationOutboxStatus.DELIVERED);
        assertThat(row.getAttempts()).isEqualTo(1);
        stub.verify(1, postRequestedFor(urlEqualTo(HOOK_PATH)));
    }

    @Test
    @DisplayName("a row whose lease is nearly spent is left leased and never sent")
    void neverStartsASendTheLeaseCannotCover() {
        stub.stubFor(post(urlEqualTo(HOOK_PATH)).willReturn(aResponse().withStatus(OK)));
        UUID id = enqueue(recipient(hookUrl()));
        ParticipantNotifications settings = settings(List.of(LOOPBACK_HOST));
        // The claim reads 0; every later reading is one send short of the lease's end.
        long nearlySpent = settings.getLease().minus(settings.sendBound()).toNanos();
        AtomicBoolean claimed = new AtomicBoolean();
        LongSupplier clock = () -> claimed.getAndSet(true) ? nearlySpent : 0L;

        dispatcher(settings, clock).drainOnce();

        NotificationOutboxEntry row = outbox.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getLeaseToken()).as("still leased, so re-claimed after expiry").isNotNull();
        assertThat(row.getNextAttemptAt()).isAfter(databaseNow());
        stub.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName(
            "a loopback endpoint without an allowed-hosts entry is abandoned before connecting")
    void refusesAnInternalEndpoint() {
        stub.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(OK)));
        UUID id = enqueue(recipient(hookUrl()));

        dispatcher(settings(List.of()), System::nanoTime).drainOnce();

        NotificationOutboxEntry row = outbox.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(NotificationOutboxStatus.ABANDONED);
        assertThat(row.getLastError()).isEqualTo(ParticipantWebhookClient.ENDPOINT_NOT_PERMITTED);
        stub.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("stalled endpoints up to the isolation bound never delay a healthy one")
    void isolatesAHealthyParticipantFromStalledOnes() {
        ParticipantNotifications settings = settings(List.of(LOOPBACK_HOST));
        int cap = settings.getPerParticipantBatchLimit();
        int stalledParticipants = (settings.getConcurrency() - 1) / cap;
        stub.stubFor(
                post(urlEqualTo(STALLED_PATH))
                        .willReturn(
                                aResponse().withStatus(OK).withFixedDelay((int) STALL.toMillis())));
        stub.stubFor(post(urlEqualTo(HOOK_PATH)).willReturn(aResponse().withStatus(OK)));
        IntStream.range(0, stalledParticipants)
                .forEach(
                        i -> {
                            UUID stalled = recipient(urlOf(STALLED_PATH));
                            IntStream.rangeClosed(0, cap).forEach(row -> enqueue(stalled));
                        });
        UUID healthy = enqueue(recipient(hookUrl()));

        Instant started = Instant.now();
        dispatcher(settings, System::nanoTime).drainOnce();

        assertThat(outbox.findById(healthy).orElseThrow().getStatus())
                .isEqualTo(NotificationOutboxStatus.DELIVERED);
        Instant received =
                stub.getAllServeEvents().stream()
                        .map(ServeEvent::getRequest)
                        .filter(request -> request.getUrl().equals(HOOK_PATH))
                        .map(request -> request.getLoggedDate().toInstant())
                        .findFirst()
                        .orElseThrow();
        assertThat(received)
                .as("the healthy delivery must not wait out a stalled wave")
                .isBefore(started.plus(READ_TIMEOUT));
    }

    private NotificationDispatcher dispatcher() {
        return dispatcher(settings(List.of(LOOPBACK_HOST)), System::nanoTime);
    }

    /** A dispatcher over a client that trusts only the stub's certificate. */
    private NotificationDispatcher dispatcher(
            ParticipantNotifications settings, LongSupplier clock) {
        DefaultHttpClientConfiguration configuration =
                ParticipantWebhookHttpClientFactory.configuration(settings);
        configuration.getSslConfiguration().setTrustStore(stubTls.trustStore());
        RawHttpClient http = RawHttpClient.create(null, configuration);
        opened.add(http);
        ParticipantWebhookClient client =
                new ParticipantWebhookClient(
                        http, signing, new HostResolver.SystemHostResolver(), settings);
        opened.add(client::shutdown);
        NotificationDispatcher dispatcher =
                new NotificationDispatcher(
                        outbox,
                        participants,
                        client,
                        transactions,
                        settings,
                        clock,
                        RandomGenerator.getDefault());
        opened.add(dispatcher::shutdown);
        return dispatcher;
    }

    private static ParticipantNotifications settings(List<String> allowedHosts) {
        ParticipantNotifications settings = new ParticipantNotifications();
        settings.setAllowedHosts(allowedHosts);
        settings.setConnectTimeout(CONNECT_TIMEOUT);
        settings.setResolveTimeout(RESOLVE_TIMEOUT);
        settings.setReadTimeout(READ_TIMEOUT);
        NotificationConfigurationValidator.check(settings);
        return settings;
    }

    private static String hookUrl() {
        return urlOf(HOOK_PATH);
    }

    private static String urlOf(String path) {
        return "https://" + LOOPBACK_HOST + ":" + stub.httpsPort() + path;
    }

    /** A fresh registered participant whose webhook is {@code endpoint}. */
    private UUID recipient(String endpoint) {
        String identifier = "urn:test:participant:dispatch:" + UUID.randomUUID();
        return participants
                .save(
                        new Participant(
                                identifier,
                                identifier,
                                null,
                                null,
                                Map.of(Participant.CONSENT_NOTIFICATION_ENDPOINT, endpoint),
                                null))
                .getId();
    }

    private UUID enqueue(UUID participantId) {
        ConsentEvent event =
                events.save(
                        new ConsentEvent(consentId, ConsentEventState.CONSENT_GIVEN, null, null));
        NotificationPayload payload =
                new NotificationPayload(
                        NotificationEventType.CONSENT_GRANTED,
                        event.getId(),
                        Instant.now().truncatedTo(ChronoUnit.MICROS),
                        consentId,
                        UUID.randomUUID(),
                        "urn:test:user:dispatch",
                        ConsentStatus.GRANTED,
                        true);
        return outbox.save(NotificationOutboxEntry.pending(participantId, payload)).getId();
    }

    private void makeDue(UUID id) {
        execute(
                "UPDATE notification_outbox SET next_attempt_at = now() - interval '1 second'"
                        + " WHERE id = ?",
                id);
    }

    private static Duration scaled(Duration base, double factor) {
        return Duration.ofNanos(Math.round(base.toNanos() * factor));
    }

    /** The database clock, which every stored outbox time is computed from. */
    private Instant databaseNow() {
        try (Connection connection = dataSources.resolve(dataSource).getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT clock_timestamp()");
                ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getObject(1, Timestamp.class).toInstant();
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private void execute(String sql, Object... parameters) {
        try (Connection connection = dataSources.resolve(dataSource).getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
