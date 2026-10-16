package com.seamware.consentmanager.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.User;
import io.micronaut.email.Email;
import io.micronaut.email.EmailSender;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** A notification the saturated executor refuses is dropped and logged, never thrown. */
class SmtpUserNotificationServiceTest {

    private static final int SINGLE = 1;
    private static final String RECIPIENT = "subject@example.org";
    private static final String DROPPED = "dropped";

    private final Logger serviceLogger =
            (Logger) LoggerFactory.getLogger(SmtpUserNotificationService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger sends = new AtomicInteger();
    private ThreadPoolExecutor executor;

    @BeforeEach
    void saturateExecutor() {
        appender.start();
        serviceLogger.addAppender(appender);
        executor =
                new ThreadPoolExecutor(
                        SINGLE,
                        SINGLE,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(SINGLE),
                        new ThreadPoolExecutor.AbortPolicy());
        executor.execute(this::awaitRelease);
        executor.execute(this::awaitRelease);
    }

    @AfterEach
    void release() {
        release.countDown();
        executor.shutdown();
        serviceLogger.detachAppender(appender);
    }

    @Test
    @DisplayName("a rejected submit returns normally and logs a dropped-notification ERROR")
    void rejectedSubmitIsLoggedAndSwallowed() {
        SmtpUserNotificationService service = service();
        User user = NotificationTestFixtures.user(RECIPIENT, null, null);
        Participant participant =
                NotificationTestFixtures.participant(NotificationTestFixtures.BENIGN_LEGAL_NAME);
        Consent consent =
                NotificationTestFixtures.consent(
                        user, participant, NotificationTestFixtures.BENIGN_TITLE);

        assertThatCode(() -> service.consentRequested(user, consent, participant))
                .doesNotThrowAnyException();

        assertThat(appender.list)
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                            assertThat(event.getFormattedMessage())
                                    .contains(DROPPED)
                                    .contains(NotificationKind.CONSENT_REQUESTED.toString())
                                    .contains(String.valueOf(consent.getId()));
                        });
        assertThat(sends).hasValue(0);
    }

    private SmtpUserNotificationService service() {
        NotificationConfiguration smtp = new NotificationConfiguration();
        smtp.setFromAddress(NotificationContexts.FROM_ADDRESS);
        return new SmtpUserNotificationService(
                new CountingSender(),
                executor,
                smtp,
                new ConsentManagerConfiguration.Notifications());
    }

    private void awaitRelease() {
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Counts sends; none may happen once the executor refused the task. */
    private final class CountingSender implements EmailSender<Object, Object> {

        @Override
        public Object send(Email.Builder email, Consumer<Object> customizer) {
            sends.incrementAndGet();
            return null;
        }

        @Override
        public String getName() {
            return CountingSender.class.getSimpleName();
        }
    }
}
