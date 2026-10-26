package com.seamware.consentmanager.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.icegreen.greenmail.store.FolderException;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.support.Await;
import io.micronaut.context.ApplicationContext;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/** Delivers both notifications to an in-process GreenMail server and proves sends never block. */
class SmtpUserNotificationServiceIT {

    private static final String CLIENT_URL = "https://wallet.example.org/inbox";
    private static final String RECIPIENT = "subject@example.org";
    private static final String SENTINEL = "sentinel@example.org";
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration FAILURE_LOG_TIMEOUT = Duration.ofSeconds(10);

    /** Far below the 5 s connect timeout, so returning within it proves the send was handed off. */
    private static final Duration HAND_OFF_BOUND = Duration.ofSeconds(1);

    private static final String MULTIPART_ALTERNATIVE = "multipart/alternative";
    private static final String TEXT_HTML = "text/html";
    private static final String TEXT_PLAIN = "text/plain";

    /** Markers of a state-changing or credential-bearing link (AC 4). */
    private static final List<String> FORBIDDEN_MARKERS =
            List.of("token", "access_token", "/consents/");

    /** Only forbidden for the benign fixture: the hostile legal name itself spells it out. */
    private static final String VERIFY = "verify";

    private static final Pattern URL =
            Pattern.compile("[a-z][a-z0-9+.-]*://\\S+", Pattern.CASE_INSENSITIVE);
    private static final List<String> AUTOLINK_SEQUENCES = List.of("mailto:", "www.");
    private static final String SMTP_PORT = "smtp.port";
    private static final String SMTP_STARTTLS = "smtp.starttls";
    private static final String CLIENT_URL_PROPERTY = "consent-manager.notifications.client-url";

    private static GreenMail greenMail;
    private static ApplicationContext context;
    private static UserNotificationService notifier;

    enum Recipient {
        ADDRESSABLE,
        NO_EMAIL
    }

    enum Fixture {
        BENIGN(NotificationTestFixtures.BENIGN_LEGAL_NAME, NotificationTestFixtures.BENIGN_TITLE),
        HOSTILE(
                NotificationTestFixtures.HOSTILE_LEGAL_NAME,
                NotificationTestFixtures.HOSTILE_TITLE);

        final String legalName;
        final String title;

        Fixture(String legalName, String title) {
            this.legalName = legalName;
            this.title = title;
        }
    }

    @BeforeAll
    static void startServerAndContext() {
        greenMail = new GreenMail(ServerSetupTest.SMTP.dynamicPort());
        greenMail.start();
        context =
                NotificationContexts.start(
                        NotificationContexts.smtp(
                                Map.of(
                                        SMTP_PORT,
                                        greenMail.getSmtp().getPort(),
                                        SMTP_STARTTLS,
                                        false,
                                        CLIENT_URL_PROPERTY,
                                        CLIENT_URL)));
        notifier = context.getBean(UserNotificationService.class);
    }

    @AfterAll
    static void stopServerAndContext() {
        context.close();
        greenMail.stop();
    }

    @BeforeEach
    void purge() throws FolderException {
        greenMail.purgeEmailFromAllMailboxes();
    }

    static Stream<Arguments> kindsAndRecipients() {
        return Arrays.stream(NotificationKind.values())
                .flatMap(
                        kind ->
                                Arrays.stream(Recipient.values())
                                        .map(recipient -> Arguments.of(kind, recipient)));
    }

    static Stream<Arguments> kindsAndFixtures() {
        return Arrays.stream(NotificationKind.values())
                .flatMap(
                        kind ->
                                Arrays.stream(Fixture.values())
                                        .map(fixture -> Arguments.of(kind, fixture)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("kindsAndRecipients")
    @DisplayName("an addressable user gets one multipart message; one without email gets nothing")
    void deliversOnlyToAddressableUsers(NotificationKind kind, Recipient recipient)
            throws Exception {
        if (recipient == Recipient.NO_EMAIL) {
            assertThatCode(() -> notify(kind, NotificationTestFixtures.user(null, null, null)))
                    .doesNotThrowAnyException();
        }
        String expectedRecipient = recipient == Recipient.NO_EMAIL ? SENTINEL : RECIPIENT;
        notify(kind, NotificationTestFixtures.user(expectedRecipient, null, null));

        assertThat(greenMail.waitForIncomingEmail(DELIVERY_TIMEOUT.toMillis(), 1)).isTrue();
        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(1);
        MimeMessage message = received[0];
        assertThat(message.getRecipients(Message.RecipientType.TO))
                .extracting(address -> ((InternetAddress) address).getAddress())
                .containsExactly(expectedRecipient);
        assertThat(message.getSubject()).isEqualTo(kind.subject());
        MimeMultipart alternative = findAlternative(message.getContent());
        assertThat(alternative).as("multipart/alternative part").isNotNull();
        assertThat(contentTypes(alternative))
                .satisfiesExactlyInAnyOrder(
                        type -> assertThat(type).startsWith(TEXT_HTML),
                        type -> assertThat(type).startsWith(TEXT_PLAIN));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("kindsAndFixtures")
    @DisplayName("no delivered body carries a token, the consent id or an unconfigured URL")
    void deliveredBodiesCarryNoForeignLink(NotificationKind kind, Fixture fixture)
            throws Exception {
        User user =
                NotificationTestFixtures.user(
                        RECIPIENT,
                        NotificationTestFixtures.FIRST_NAME,
                        NotificationTestFixtures.LAST_NAME);
        Participant participant = NotificationTestFixtures.participant(fixture.legalName);
        Consent consent = NotificationTestFixtures.consent(user, participant, fixture.title);
        notify(kind, user, consent, participant);

        assertThat(greenMail.waitForIncomingEmail(DELIVERY_TIMEOUT.toMillis(), 1)).isTrue();
        MimeMultipart alternative =
                findAlternative(greenMail.getReceivedMessages()[0].getContent());
        bodies(alternative)
                .forEach(
                        body -> {
                            assertThat(body)
                                    .doesNotContain(FORBIDDEN_MARKERS)
                                    .doesNotContain(String.valueOf(consent.getId()))
                                    .doesNotContain(AUTOLINK_SEQUENCES);
                            if (fixture == Fixture.BENIGN) {
                                assertThat(body).doesNotContainIgnoringCase(VERIFY);
                            }
                            assertThat(URL.matcher(body).results().map(match -> match.group()))
                                    .allSatisfy(url -> assertThat(url).startsWith(CLIENT_URL));
                        });
    }

    @ParameterizedTest
    @EnumSource(NotificationKind.class)
    @DisplayName("a send failure returns normally and is logged as an ERROR by the service")
    void sendFailureIsLogged(NotificationKind kind) throws IOException {
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(SmtpUserNotificationService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        try (ApplicationContext failing = smtpContext(closedPort())) {
            // Attached after startup, which reconfigures Logback and would detach it.
            appender.start();
            serviceLogger.addAppender(appender);
            UserNotificationService service = failing.getBean(UserNotificationService.class);

            assertThatCode(
                            () ->
                                    notify(
                                            service,
                                            kind,
                                            NotificationTestFixtures.user(RECIPIENT, null, null)))
                    .doesNotThrowAnyException();

            Await.until(
                    "an ERROR from SmtpUserNotificationService",
                    FAILURE_LOG_TIMEOUT,
                    () ->
                            List.copyOf(appender.list).stream()
                                    .anyMatch(
                                            event ->
                                                    event.getLevel() == Level.ERROR
                                                            && event.getLoggerName()
                                                                    .equals(
                                                                            SmtpUserNotificationService
                                                                                    .class
                                                                                    .getName())));
        } finally {
            serviceLogger.detachAppender(appender);
        }
    }

    @ParameterizedTest
    @EnumSource(NotificationKind.class)
    @DisplayName("a server that accepts and never answers does not delay the caller")
    void unresponsiveServerDoesNotBlock(NotificationKind kind) throws IOException {
        // Never accepting still completes the TCP handshake through the backlog, so the client
        // connects and then waits for a greeting that never comes.
        try (ServerSocket silent = new ServerSocket(0);
                ApplicationContext stalled = smtpContext(silent.getLocalPort())) {
            UserNotificationService service = stalled.getBean(UserNotificationService.class);
            User user = NotificationTestFixtures.user(RECIPIENT, null, null);

            long started = System.nanoTime();
            notify(service, kind, user);
            notify(service, kind, user);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertThat(elapsed).isLessThan(HAND_OFF_BOUND);
        }
    }

    private static ApplicationContext smtpContext(int port) {
        return NotificationContexts.start(
                NotificationContexts.smtp(Map.of(SMTP_PORT, port, SMTP_STARTTLS, false)));
    }

    /** A localhost port nothing listens on once the probe socket is closed. */
    private static int closedPort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static void notify(NotificationKind kind, User user) {
        notify(notifier, kind, user);
    }

    private static void notify(UserNotificationService service, NotificationKind kind, User user) {
        Participant participant =
                NotificationTestFixtures.participant(NotificationTestFixtures.BENIGN_LEGAL_NAME);
        Consent consent =
                NotificationTestFixtures.consent(
                        user, participant, NotificationTestFixtures.BENIGN_TITLE);
        notify(service, kind, user, consent, participant);
    }

    private static void notify(
            NotificationKind kind, User user, Consent consent, Participant participant) {
        notify(notifier, kind, user, consent, participant);
    }

    private static void notify(
            UserNotificationService service,
            NotificationKind kind,
            User user,
            Consent consent,
            Participant participant) {
        switch (kind) {
            case CONSENT_REQUESTED -> service.consentRequested(user, consent, participant);
            case CONSENT_TERMINATED -> service.consentTerminated(user, consent, participant);
        }
    }

    /** The {@code multipart/alternative} part, at the top level or nested under a mixed one. */
    private static MimeMultipart findAlternative(Object content)
            throws MessagingException, IOException {
        if (!(content instanceof MimeMultipart multipart)) {
            return null;
        }
        if (multipart.getContentType().toLowerCase().startsWith(MULTIPART_ALTERNATIVE)) {
            return multipart;
        }
        for (int i = 0; i < multipart.getCount(); i++) {
            MimeMultipart nested = findAlternative(multipart.getBodyPart(i).getContent());
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static List<String> contentTypes(MimeMultipart multipart) throws MessagingException {
        List<String> types = new ArrayList<>();
        for (int i = 0; i < multipart.getCount(); i++) {
            types.add(multipart.getBodyPart(i).getContentType().toLowerCase());
        }
        return types;
    }

    private static List<String> bodies(MimeMultipart multipart)
            throws MessagingException, IOException {
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            bodies.add((String) part.getContent());
        }
        return bodies;
    }
}
