package com.seamware.consentmanager.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.context.ApplicationContext;
import io.micronaut.email.javamail.sender.SessionProvider;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.mail.Session;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Exactly one notification implementation binds for every {@code smtp.host}, read through the real
 * application.yml so a {@code host: ${SMTP_HOST:}} line there would fail the absent case.
 */
class NotificationBindingTest {

    static Stream<Arguments> unconfiguredHosts() {
        Map<String, Object> absent = new HashMap<>();
        return Stream.of(
                Arguments.of("absent", absent),
                Arguments.of("empty", Map.of(SmtpHost.PROPERTY, "")),
                Arguments.of("blank", Map.of(SmtpHost.PROPERTY, "   ")));
    }

    @ParameterizedTest(name = "smtp.host {0}")
    @MethodSource("unconfiguredHosts")
    @DisplayName("an unset or blank smtp.host binds only the logging implementation")
    void bindsTheLoggingImplementationWithoutAHost(String label, Map<String, Object> properties) {
        try (ApplicationContext context = NotificationContexts.start(properties)) {
            assertThat(context.getBeansOfType(UserNotificationService.class))
                    .singleElement()
                    .isInstanceOf(LoggingUserNotificationService.class);
            assertThat(context.containsBean(Session.class)).isFalse();
            assertThat(notificationExecutorExists(context)).isFalse();
        }
    }

    @Test
    @DisplayName("a configured smtp.host binds the SMTP session, its provider and the executor")
    void bindsTheSmtpBeansWithAHost() {
        NotificationThreadPool executor;
        try (ApplicationContext context =
                NotificationContexts.start(NotificationContexts.smtp(Map.of()))) {
            assertThat(context.getBeansOfType(LoggingUserNotificationService.class)).isEmpty();
            Session session = context.getBean(Session.class);
            assertThat(context.getBeansOfType(SessionProvider.class))
                    .as("the javamail module's own provider must be replaced, not joined")
                    .singleElement()
                    .extracting(SessionProvider::session)
                    .isSameAs(session);
            assertThat(notificationExecutorExists(context)).isTrue();
            executor =
                    context.getBean(
                            NotificationThreadPool.class,
                            Qualifiers.byName(NotificationExecutorFactory.EXECUTOR_NAME));
            assertThat(executor.isShutdown()).isFalse();
        }
        assertThat(executor.isShutdown())
                .as("closing the context must run the pool's preDestroy drain")
                .isTrue();
    }

    private static boolean notificationExecutorExists(ApplicationContext context) {
        return context.containsBean(
                Executor.class, Qualifiers.byName(NotificationExecutorFactory.EXECUTOR_NAME));
    }
}
