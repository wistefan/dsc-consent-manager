package com.seamware.consentmanager.service;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static com.seamware.consentmanager.support.ConsentFixtures.participant;
import static com.seamware.consentmanager.support.ConsentFixtures.principalFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.domain.UserParticipant;
import com.seamware.consentmanager.error.BadRequestException;
import com.seamware.consentmanager.error.ConflictException;
import com.seamware.consentmanager.error.ForbiddenException;
import com.seamware.consentmanager.error.NotFoundException;
import com.seamware.consentmanager.notification.UserNotificationService;
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
import com.seamware.consentmanager.support.PrivacyNoticeFixtures;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * Pins who the subject is notified by: a participant's request and a participant's termination,
 * once each and only after the write committed, and none of the terminations other paths perform.
 *
 * <p>The notifier is replaced with a capturing double, so what is under test is that the call is
 * made with the right arguments, not what the email says. Security is on because the failure case
 * asserts the HTTP status a committed write still answers with when notifying it throws.
 */
@MicronautTest(transactional = false)
@Property(name = "spec.name", value = "ConsentNotificationIT")
@DisplayName("Subject notification on request and termination")
class ConsentNotificationIT extends KeycloakAndPostgresTestResource {

    /** The caller: the identifier the {@code PARTICIPANT} token carries. */
    private static final String CALLER = KeycloakTestResource.PARTICIPANT_IDENTIFIER;

    private static final String COUNTERPARTY = "urn:test:participant:notification-consumer";

    /** A participant no seeded notice names on the caller's side. */
    private static final String STRANGER = "urn:test:participant:notification-stranger";

    private static final String CONTRACT_URI = "urn:test:contract:notification";

    private static final String STRANGER_CONTRACT_URI = "urn:test:contract:notification-stranger";

    private static final String NOTICE_TITLE = "Notice the notification cases act on";

    private static final String ISSUER = "https://issuer.test/realms/notification";

    private static final String EMAIL = "subject@example.org";

    private static final String LOCAL_IDENTIFIER = "notification-local";

    private static final String PATH_CONSENTS = "/consents";

    private static final String PATH_CONSENT_REQUESTS = PATH_CONSENTS + "/requests";

    private static final String TERMINATE = "terminate";

    private static final String PROPERTY_NOTICE = "privacyNoticeId";

    private static final String PROPERTY_SUBJECT = "userIdentifier";

    private static final Map<String, Object> NO_BODY = Map.of();

    /** Longest a cold Keycloak container may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    private static final List<ConsentStatus> ALL_STATUSES = List.of(ConsentStatus.values());

    private static final List<Class<?>> CALL_SITES =
            List.of(ConsentService.class, ConsentLifecycleService.class);

    @Inject ConsentService service;

    @Inject ConsentLifecycleService lifecycle;

    @Inject UserService userService;

    @Inject ParticipantService participantService;

    @Inject CapturingNotifier notifier;

    @Inject ConsentRepository consents;

    @Inject ConsentEventRepository events;

    @Inject PrivacyNoticeRepository notices;

    @Inject ParticipantRepository participants;

    @Inject UserRepository users;

    @Inject UserParticipantRepository links;

    @Inject IdentityProviderRegistry registry;

    @Inject
    @Client("/")
    HttpClient client;

    private final List<UUID> seededUsers = new ArrayList<>();

    private final List<UUID> seededNotices = new ArrayList<>();

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    private Participant caller;

    private PrivacyNotice notice;

    /** The two operations that notify, with what each leaves stored and where it logs. */
    enum Trigger {
        REQUEST(
                Kind.REQUESTED,
                ConsentStatus.PENDING,
                ConsentEventState.CONSENT_REQUESTED,
                ConsentService.class,
                HttpStatus.CREATED),
        TERMINATE(
                Kind.TERMINATED,
                ConsentStatus.TERMINATED,
                ConsentEventState.CONSENT_TERMINATED,
                ConsentLifecycleService.class,
                HttpStatus.OK);

        private final Kind kind;
        private final ConsentStatus stored;
        private final ConsentEventState event;
        private final Class<?> callSite;
        private final HttpStatus success;

        Trigger(
                Kind kind,
                ConsentStatus stored,
                ConsentEventState event,
                Class<?> callSite,
                HttpStatus success) {
            this.kind = kind;
            this.stored = stored;
            this.event = event;
            this.callSite = callSite;
            this.success = success;
        }
    }

    /** The terminations other paths perform, none of which is the participant's to announce. */
    enum ExcludedTermination {
        RESUME_COLLATERAL,
        DECISION_CLEANUP,
        ERASURE,
        DEREGISTRATION
    }

    /** The ways a notifying operation is turned away before it writes anything. */
    enum Refusal {
        REQUEST_UNKNOWN_NOTICE(NotFoundException.class),
        REQUEST_BY_NON_PARTY(ForbiddenException.class),
        REQUEST_ALREADY_OPEN(ConflictException.class),
        TERMINATE_UNKNOWN_CONSENT(NotFoundException.class),
        TERMINATE_BY_NON_PARTY(ForbiddenException.class),
        TERMINATE_TERMINAL_CONSENT(BadRequestException.class);

        private final Class<? extends RuntimeException> expected;

        Refusal(Class<? extends RuntimeException> expected) {
            this.expected = expected;
        }
    }

    static Stream<Arguments> triggersPerRecipient() {
        return Stream.of(Trigger.values())
                .flatMap(
                        trigger ->
                                Stream.of(EMAIL, null).map(email -> Arguments.of(trigger, email)));
    }

    @BeforeEach
    void seedTheNoticeAndCaptureTheCallSites() {
        notifier.reset();
        removeCommittedRows();
        caller = participant(participants, CALLER);
        notice = notice(CONTRACT_URI, caller, participant(participants, COUNTERPARTY));
        logged.list.clear();
        logged.start();
        CALL_SITES.forEach(callSite -> logger(callSite).addAppender(logged));
    }

    /** Rows these cases commit outlive the transaction; consents go first, holding the rest. */
    @AfterEach
    void removeCommittedRows() {
        CALL_SITES.forEach(callSite -> logger(callSite).detachAppender(logged));
        seededUsers.forEach(
                userId -> {
                    consents.findByUserIdAndStatusIn(userId, ALL_STATUSES)
                            .forEach(
                                    consent -> {
                                        events.findByConsentIdOrderByOccurredAtAsc(consent.getId())
                                                .forEach(events::delete);
                                        consents.delete(consent);
                                    });
                    links.deleteByIdUserId(userId);
                    users.deleteById(userId);
                });
        seededUsers.clear();
        seededNotices.forEach(notices::deleteById);
        seededNotices.clear();
        Stream.of(CALLER, COUNTERPARTY, STRANGER)
                .forEach(
                        identifier ->
                                participants
                                        .findByIdentifier(identifier)
                                        .ifPresent(participants::delete));
    }

    @ParameterizedTest(name = "{0}, email {1}")
    @MethodSource("triggersPerRecipient")
    @DisplayName("notifies once, naming the subject, the committed consent and the participant")
    void notifiesTheSubjectOnce(Trigger trigger, String email) {
        User subject = linkedUser(email);

        UUID consentId = perform(trigger, subject);

        Consent stored = consents.findById(consentId).orElseThrow();
        assertThat(notifier.captured())
                .singleElement()
                .satisfies(
                        notified -> {
                            assertThat(notified.kind()).isEqualTo(trigger.kind);
                            assertThat(notified.userId()).isEqualTo(subject.getId());
                            assertThat(notified.email()).isEqualTo(email);
                            assertThat(notified.consentId()).isEqualTo(consentId);
                            assertThat(notified.status()).isEqualTo(trigger.stored);
                            assertThat(notified.updatedAt())
                                    .as("the committed move time the template formats")
                                    .isEqualTo(stored.getUpdatedAt());
                            assertThat(notified.participantId()).isEqualTo(caller.getId());
                        });
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ExcludedTermination.class)
    @DisplayName("a termination the participant did not ask for notifies nobody")
    void doesNotNotifyAnotherPathsTermination(ExcludedTermination path) {
        User subject = linkedUser(EMAIL);
        UUID first = seedConsent(subject, notice, ConsentStatus.PENDING);
        UUID second = seedConsent(subject, notice, ConsentStatus.PENDING);

        switch (path) {
            case RESUME_COLLATERAL -> lifecycle.resume(principalFor(ISSUER, subject), first, null);
            case DECISION_CLEANUP ->
                    service.decide(
                            subject,
                            new ConsentDecision(notice.getId(), ConsentStatus.GRANTED, null, null));
            case ERASURE -> userService.erase(subject);
            case DEREGISTRATION -> participantService.deregister(caller);
        }

        assertThat(List.of(first, second))
                .as("the path did terminate something, so its silence is a decision")
                .anySatisfy(
                        id ->
                                assertThat(consents.findById(id).orElseThrow().getStatus())
                                        .isEqualTo(ConsentStatus.TERMINATED));
        assertThat(notifier.captured()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Refusal.class)
    @DisplayName("a refused request or termination notifies nobody")
    void doesNotNotifyARefusal(Refusal refusal) {
        User subject = linkedUser(EMAIL);
        Participant stranger = participant(participants, STRANGER);

        assertThatThrownBy(() -> refuse(refusal, subject, stranger)).isInstanceOf(refusal.expected);

        assertThat(notifier.captured()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Trigger.class)
    @DisplayName("a notifier that throws leaves the committed write and its success status alone")
    void keepsTheCommittedWriteWhenNotifyingFails(Trigger trigger) {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
        notifier.failing(true);
        User subject = linkedUser(EMAIL);

        HttpResponse<String> response = performOverHttp(trigger, subject);

        assertThat(response.code()).isEqualTo(trigger.success.getCode());
        Consent stored = consents.findByUserIdAndStatusIn(subject.getId(), ALL_STATUSES).getFirst();
        assertThat(stored.getStatus()).isEqualTo(trigger.stored);
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(stored.getId()))
                .map(ConsentEvent::getEventState)
                .last()
                .isEqualTo(trigger.event);
        assertThat(logged.list)
                .singleElement()
                .satisfies(
                        line -> {
                            assertThat(line.getLevel()).isEqualTo(Level.ERROR);
                            assertThat(line.getLoggerName()).isEqualTo(trigger.callSite.getName());
                            assertThat(line.getFormattedMessage())
                                    .contains(stored.getId().toString());
                        });
    }

    /** Runs the operation in the caller's name and returns the consent it wrote. */
    private UUID perform(Trigger trigger, User subject) {
        return switch (trigger) {
            case REQUEST ->
                    service.request(
                                    caller,
                                    new ConsentRequestCommand(
                                            notice.getId(), subject.getIdentifier()))
                            .consent()
                            .getId();
            case TERMINATE -> {
                UUID consentId = seedConsent(subject, notice, ConsentStatus.GRANTED);
                lifecycle.terminate(caller, consentId);
                yield consentId;
            }
        };
    }

    private HttpResponse<String> performOverHttp(Trigger trigger, User subject) {
        MutableHttpRequest<?> request =
                switch (trigger) {
                    case REQUEST ->
                            HttpRequest.POST(
                                    PATH_CONSENT_REQUESTS,
                                    Map.of(
                                            PROPERTY_NOTICE,
                                            notice.getId().toString(),
                                            PROPERTY_SUBJECT,
                                            subject.getIdentifier()));
                    case TERMINATE ->
                            HttpRequest.POST(
                                    PATH_CONSENTS
                                            + "/"
                                            + seedConsent(subject, notice, ConsentStatus.GRANTED)
                                            + "/"
                                            + TERMINATE,
                                    NO_BODY);
                };
        return exchange(
                request.bearerAuth(KeycloakTestResource.accessToken(RealmPrincipal.PARTICIPANT)));
    }

    private void refuse(Refusal refusal, User subject, Participant stranger) {
        switch (refusal) {
            case REQUEST_UNKNOWN_NOTICE ->
                    service.request(
                            caller,
                            new ConsentRequestCommand(UUID.randomUUID(), subject.getIdentifier()));
            case REQUEST_BY_NON_PARTY ->
                    service.request(
                            stranger,
                            new ConsentRequestCommand(notice.getId(), subject.getIdentifier()));
            case REQUEST_ALREADY_OPEN -> {
                seedConsent(subject, notice, ConsentStatus.PENDING);
                service.request(
                        caller, new ConsentRequestCommand(notice.getId(), subject.getIdentifier()));
            }
            case TERMINATE_UNKNOWN_CONSENT -> lifecycle.terminate(caller, UUID.randomUUID());
            case TERMINATE_BY_NON_PARTY ->
                    lifecycle.terminate(
                            stranger, seedConsent(subject, notice, ConsentStatus.GRANTED));
            case TERMINATE_TERMINAL_CONSENT ->
                    lifecycle.terminate(
                            caller, seedConsent(subject, notice, ConsentStatus.REVOKED));
        }
    }

    /** A subject affiliated with the caller, so the caller may ask it. */
    private User linkedUser(String email) {
        User subject =
                users.save(
                        new User(
                                "urn:test:user:notification:" + UUID.randomUUID(),
                                email,
                                null,
                                null));
        seededUsers.add(subject.getId());
        links.save(new UserParticipant(subject.getId(), caller.getId(), LOCAL_IDENTIFIER));
        return subject;
    }

    private UUID seedConsent(User subject, PrivacyNotice on, ConsentStatus status) {
        return consents.save(
                        Consent.withStatus(
                                status,
                                subject.getId(),
                                on.getId(),
                                on.getProviderId(),
                                on.getConsumerId(),
                                null,
                                on.getContractUri(),
                                noTerms()))
                .getId();
    }

    private PrivacyNotice notice(String contractUri, Participant provider, Participant consumer) {
        PrivacyNotice saved =
                notices.save(
                        new PrivacyNotice(
                                contractUri,
                                NOTICE_TITLE,
                                provider.getId(),
                                consumer.getId(),
                                PrivacyNoticeFixtures.emptyPayload()));
        seededNotices.add(saved.getId());
        return saved;
    }

    /** Issues the request, returning a refusal rather than throwing it. */
    @SuppressWarnings("unchecked")
    private HttpResponse<String> exchange(MutableHttpRequest<?> request) {
        try {
            return client.toBlocking().exchange(request, String.class);
        } catch (HttpClientResponseException e) {
            return (HttpResponse<String>) e.getResponse();
        }
    }

    private static Logger logger(Class<?> callSite) {
        return (Logger) LoggerFactory.getLogger(callSite);
    }

    /** Which of the two notifications was asked for. */
    enum Kind {
        REQUESTED,
        TERMINATED
    }

    /** One captured call, reduced to what the assertions read. */
    record Notified(
            Kind kind,
            UUID userId,
            String email,
            UUID consentId,
            ConsentStatus status,
            Instant updatedAt,
            UUID participantId) {

        static Notified of(Kind kind, User user, Consent consent, Participant participant) {
            return new Notified(
                    kind,
                    user.getId(),
                    user.getEmail(),
                    consent.getId(),
                    consent.getStatus(),
                    consent.getUpdatedAt(),
                    participant.getId());
        }
    }

    /**
     * Stands in for the bound notifier and records every call, throwing after recording when told
     * to. Active for this suite alone, keyed on {@code spec.name}.
     */
    @Singleton
    @Replaces(UserNotificationService.class)
    @Requires(property = "spec.name", value = "ConsentNotificationIT")
    static class CapturingNotifier implements UserNotificationService {

        private final List<Notified> captured = new CopyOnWriteArrayList<>();

        private volatile boolean failing;

        @Override
        public void consentRequested(User user, Consent consent, Participant participant) {
            record(Notified.of(Kind.REQUESTED, user, consent, participant));
        }

        @Override
        public void consentTerminated(User user, Consent consent, Participant participant) {
            record(Notified.of(Kind.TERMINATED, user, consent, participant));
        }

        private void record(Notified notified) {
            captured.add(notified);
            if (failing) {
                throw new IllegalStateException("notifier failure injected by the suite");
            }
        }

        List<Notified> captured() {
            return List.copyOf(captured);
        }

        void failing(boolean failing) {
            this.failing = failing;
        }

        void reset() {
            captured.clear();
            failing = false;
        }
    }
}
