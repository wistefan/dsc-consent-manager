package com.seamware.consentmanager.notification;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.seamware.consentmanager.support.ConsentFixtures.participant;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.jwk.JWKSet;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.repository.ConsentEventRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.security.IdentityProviderRegistry;
import com.seamware.consentmanager.signing.SignatureType;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.KeycloakAndPostgresTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource;
import com.seamware.consentmanager.support.KeycloakTestResource.RealmPrincipal;
import com.seamware.consentmanager.support.PrivacyNoticeFixtures;
import com.seamware.consentmanager.support.ReceiptSignatures;
import com.seamware.consentmanager.support.TestJson;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.jdbc.DataSourceResolver;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.ParseException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The whole path end to end: a grant and a revoke through the HTTP API reach the webhook the
 * participant registered through {@code PUT /participants/me}, signed verifiably against the JWKS.
 */
@MicronautTest(transactional = false)
@DisplayName("Participant notification delivery, end to end")
class ParticipantNotificationDeliveryIT extends KeycloakAndPostgresTestResource {

    private static final String LOOPBACK_HOST = "localhost";

    private static final String CALLER_PATH = "/hooks/caller";

    private static final String COUNTERPARTY_PATH = "/hooks/counterparty";

    private static final String COUNTERPARTY = "urn:test:participant:delivery-counterparty";

    private static final String CONTRACT_URI = "urn:test:contract:delivery";

    private static final String PATH_CONSENTS = "/consents";

    private static final String PATH_PARTICIPANTS_ME = "/participants/me";

    private static final String PATH_JWKS = "/.well-known/jwks.json";

    private static final Duration RESOLVE_TIMEOUT = Duration.ofSeconds(1);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(1);

    /** How long a stalled recipient holds its answer: well past the read timeout. */
    private static final int STALL_MILLIS = (int) READ_TIMEOUT.multipliedBy(2).toMillis();

    /** Longest a cold Keycloak may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    private static final int OK = 200;

    private static final int NOT_FOUND = 404;

    private static final int SERVICE_UNAVAILABLE = 503;

    /** The two moves each test makes, grant then revoke, each notified once per party. */
    private static final Set<NotificationEventType> MOVES =
            Set.of(NotificationEventType.CONSENT_GRANTED, NotificationEventType.CONSENT_REVOKED);

    /** Created at class initialisation, before the context builds the replacing client. */
    private static final WebhookStubTls STUB_TLS = WebhookStubTls.generateUnchecked();

    private static WireMockServer stub;

    @Inject
    @Client("/")
    HttpClient client;

    @Inject IdentityProviderRegistry registry;

    @Inject ParticipantRepository participants;

    @Inject PrivacyNoticeRepository notices;

    @Inject ConsentEventRepository events;

    @Inject NotificationOutboxRepository outbox;

    @Inject NotificationDispatcher dispatcher;

    @Inject DataSource dataSource;

    @Inject DataSourceResolver dataSources;

    private Participant caller;

    private Participant counterparty;

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

    /** Trusts the stub, admits loopback past the address fence, and keeps timeouts short. */
    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>(super.getProperties());
        properties.putAll(STUB_TLS.clientProperties());
        properties.put("consent-manager.participant-notifications.allowed-hosts", LOOPBACK_HOST);
        properties.put(
                "consent-manager.participant-notifications.resolve-timeout",
                RESOLVE_TIMEOUT.toMillis() + "ms");
        properties.put(
                "consent-manager.participant-notifications.connect-timeout",
                CONNECT_TIMEOUT.toMillis() + "ms");
        properties.put(
                "consent-manager.participant-notifications.read-timeout",
                READ_TIMEOUT.toMillis() + "ms");
        return properties;
    }

    @BeforeEach
    void registerBothParties() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        stub.resetAll();
        caller = participant(participants, KeycloakTestResource.PARTICIPANT_IDENTIFIER);
        counterparty =
                participants.save(
                        new Participant(
                                COUNTERPARTY,
                                COUNTERPARTY,
                                null,
                                null,
                                Map.of(
                                        Participant.CONSENT_NOTIFICATION_ENDPOINT,
                                        urlOf(COUNTERPARTY_PATH)),
                                null));
        stub.stubFor(post(urlEqualTo(COUNTERPARTY_PATH)).willReturn(aResponse().withStatus(OK)));
    }

    /**
     * Recipient behaviour: its answer ({@code null} for no registered endpoint), the outcome the
     * first drain leaves, and the answer a retry gets ({@code null} when none is due).
     */
    static Stream<Arguments> recipientBehaviours() {
        return Stream.of(
                Arguments.of(
                        "200",
                        aResponse().withStatus(OK),
                        NotificationOutboxStatus.DELIVERED,
                        null,
                        null),
                Arguments.of(
                        "404",
                        aResponse().withStatus(NOT_FOUND),
                        NotificationOutboxStatus.ABANDONED,
                        "HTTP 404",
                        null),
                Arguments.of(
                        "503",
                        aResponse().withStatus(SERVICE_UNAVAILABLE),
                        NotificationOutboxStatus.FAILED,
                        "HTTP 503",
                        aResponse().withStatus(OK)),
                Arguments.of(
                        "stall past the read timeout",
                        aResponse().withStatus(OK).withFixedDelay(STALL_MILLIS),
                        NotificationOutboxStatus.FAILED,
                        ParticipantWebhookClient.READ_TIMEOUT,
                        null),
                Arguments.of(
                        "no endpoint",
                        null,
                        NotificationOutboxStatus.ABANDONED,
                        NotificationDispatcher.NO_ENDPOINT,
                        null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recipientBehaviours")
    @DisplayName("a grant then revoke reaches the recipient as its endpoint answers, signed")
    void deliversAGrantAndARevokeAsTheRecipientAnswers(
            String behaviour,
            ResponseDefinitionBuilder answer,
            NotificationOutboxStatus firstOutcome,
            String lastError,
            ResponseDefinitionBuilder retryAnswer)
            throws Exception {
        registerEndpoint(answer == null ? null : urlOf(CALLER_PATH));
        if (answer != null) {
            stub.stubFor(post(urlEqualTo(CALLER_PATH)).willReturn(answer));
        }
        UUID consentId = grantThenRevoke();

        dispatcher.drainOnce();

        List<NotificationOutboxEntry> callerRows = rowsOf(caller.getId());
        assertThat(callerRows)
                .extracting(NotificationOutboxEntry::getEventType)
                .containsExactlyInAnyOrderElementsOf(MOVES);
        assertThat(callerRows)
                .allSatisfy(
                        row -> {
                            assertThat(row.getStatus()).isEqualTo(firstOutcome);
                            assertThat(row.getAttempts()).isOne();
                            assertThat(row.getLastError()).isEqualTo(lastError);
                        });
        int expectedRequests = answer == null ? 0 : MOVES.size();
        assertThat(received(CALLER_PATH)).as("one attempt per row").hasSize(expectedRequests);

        if (retryAnswer != null) {
            stub.stubFor(post(urlEqualTo(CALLER_PATH)).willReturn(retryAnswer));
            makeDue(caller.getId());
            dispatcher.drainOnce();

            assertThat(rowsOf(caller.getId()))
                    .allSatisfy(
                            row -> {
                                assertThat(row.getStatus())
                                        .isEqualTo(NotificationOutboxStatus.DELIVERED);
                                assertThat(row.getAttempts()).isEqualTo(2);
                            });
            assertThat(received(CALLER_PATH)).hasSize(2 * MOVES.size());
        }

        assertThat(rowsOf(counterparty.getId()))
                .as("the other party is notified of the same events whatever the caller answers")
                .allSatisfy(
                        row ->
                                assertThat(row.getStatus())
                                        .isEqualTo(NotificationOutboxStatus.DELIVERED))
                .extracting(NotificationOutboxEntry::getEventId)
                .containsExactlyInAnyOrderElementsOf(
                        callerRows.stream().map(NotificationOutboxEntry::getEventId).toList());
        assertVerifiablySigned(received(CALLER_PATH), consentId);
        assertVerifiablySigned(received(COUNTERPARTY_PATH), consentId);
    }

    /**
     * Each body is a move of {@code consentId} under the id of the event that recorded it, and its
     * signature verifies, typed as a notification, against the keys the service publishes.
     */
    private void assertVerifiablySigned(List<LoggedRequest> requests, UUID consentId)
            throws IOException, ParseException, JOSEException {
        JWKSet published = JWKSet.parse(client.toBlocking().retrieve(PATH_JWKS));
        Set<String> eventIds =
                StreamSupport.stream(events.findAll().spliterator(), false)
                        .map(event -> event.getId().toString())
                        .collect(Collectors.toSet());
        for (LoggedRequest request : requests) {
            String signature = request.getHeader(ParticipantWebhookClient.SIGNATURE_HEADER);
            Map<String, Object> body =
                    TestJson.mapper()
                            .readValue(
                                    request.getBodyAsString(),
                                    Argument.mapOf(String.class, Object.class));

            assertThat(body)
                    .containsEntry("consentId", consentId.toString())
                    .containsKey("eventType");
            assertThat(eventIds).contains((String) body.get("eventId"));
            assertThat(ReceiptSignatures.verifies(body, signature, published)).isTrue();
            assertThat(
                            JWSObject.parse(signature, new Payload(request.getBody()))
                                    .getHeader()
                                    .getType())
                    .isEqualTo(SignatureType.NOTIFICATION.header());
        }
    }

    /** Sets the caller's webhook through the API, or clears every endpoint on {@code null}. */
    private void registerEndpoint(String url) {
        Map<String, Object> body = new HashMap<>();
        body.put("legalName", caller.getLegalName());
        if (url != null) {
            body.put("endpoints", Map.of("consentNotification", url));
        }
        assertThat(
                        exchange(
                                        HttpRequest.PUT(PATH_PARTICIPANTS_ME, body),
                                        RealmPrincipal.PARTICIPANT)
                                .code())
                .isEqualTo(HttpStatus.OK.getCode());
    }

    /** The realm's user grants a notice naming both parties, then revokes it. */
    private UUID grantThenRevoke() throws IOException {
        PrivacyNotice notice =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI,
                                "Delivery notice",
                                caller.getId(),
                                counterparty.getId(),
                                PrivacyNoticeFixtures.emptyPayload()));
        HttpResponse<String> granted =
                exchange(
                        HttpRequest.POST(
                                PATH_CONSENTS,
                                Map.of(
                                        "privacyNoticeId",
                                        notice.getId().toString(),
                                        "decision",
                                        "GRANTED")),
                        RealmPrincipal.USER);
        assertThat(granted.code()).isEqualTo(HttpStatus.CREATED.getCode());
        UUID consentId =
                UUID.fromString(
                        (String)
                                TestJson.mapper()
                                        .readValue(
                                                granted.body(),
                                                Argument.mapOf(String.class, Object.class))
                                        .get("id"));
        assertThat(
                        exchange(
                                        HttpRequest.POST(
                                                PATH_CONSENTS + "/" + consentId + "/revoke",
                                                Map.of()),
                                        RealmPrincipal.USER)
                                .code())
                .isEqualTo(HttpStatus.OK.getCode());
        return consentId;
    }

    private List<NotificationOutboxEntry> rowsOf(UUID participantId) {
        return StreamSupport.stream(outbox.findAll().spliterator(), false)
                .filter(row -> row.getParticipantId().equals(participantId))
                .toList();
    }

    private static List<LoggedRequest> received(String path) {
        return stub.findAll(postRequestedFor(urlEqualTo(path)));
    }

    private static String urlOf(String path) {
        return "https://" + LOOPBACK_HOST + ":" + stub.httpsPort() + path;
    }

    /** Brings the participant's scheduled retries forward, so the next drain claims them. */
    private void makeDue(UUID participantId) throws SQLException {
        try (Connection connection = dataSources.resolve(dataSource).getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE notification_outbox"
                                        + " SET next_attempt_at = now() - interval '1 second'"
                                        + " WHERE participant_id = ?"
                                        + " AND status IN ('PENDING', 'FAILED')")) {
            statement.setObject(1, participantId);
            statement.executeUpdate();
        }
    }

    /** Issues the request as {@code principal}, returning a refusal rather than throwing it. */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> exchange(MutableHttpRequest<?> request, RealmPrincipal principal) {
        try {
            return client.toBlocking()
                    .exchange(
                            request.bearerAuth(KeycloakTestResource.accessToken(principal)),
                            String.class);
        } catch (HttpClientResponseException e) {
            return (HttpResponse<String>) e.getResponse();
        }
    }
}
