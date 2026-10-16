package com.seamware.consentmanager.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micronaut.context.ApplicationContext;
import jakarta.mail.Session;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/** The SMTP session verifies the server unless explicitly told not to, and says so at startup. */
class SmtpSessionFactoryTest {

    private static final String TRUST_ALL_PROPERTY = "smtp.insecure-trust-all-certificates";
    private static final String STARTTLS_PROPERTY = "smtp.starttls";
    private static final String PORT_PROPERTY = "smtp.port";
    private static final String CLIENT_URL_PROPERTY = "consent-manager.notifications.client-url";

    private final Logger validatorLogger =
            (Logger) LoggerFactory.getLogger(SmtpConfigurationValidator.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void captureValidatorLog() {
        appender.start();
        validatorLogger.addAppender(appender);
    }

    @AfterEach
    void releaseValidatorLog() {
        validatorLogger.detachAppender(appender);
    }

    static Stream<Arguments> sessionProperties() {
        return Stream.of(
                Arguments.of(false, SmtpSessionFactory.CHECK_SERVER_IDENTITY, "true"),
                Arguments.of(false, SmtpSessionFactory.SSL_TRUST, null),
                Arguments.of(false, SmtpSessionFactory.STARTTLS_ENABLE, "true"),
                Arguments.of(false, SmtpSessionFactory.STARTTLS_REQUIRED, "true"),
                Arguments.of(false, SmtpSessionFactory.HOST, NotificationContexts.CONFIGURED_HOST),
                Arguments.of(false, SmtpSessionFactory.PORT, "587"),
                Arguments.of(false, SmtpSessionFactory.AUTH, "false"),
                Arguments.of(false, SmtpSessionFactory.CONNECTION_TIMEOUT_KEY, "5000"),
                Arguments.of(false, SmtpSessionFactory.READ_TIMEOUT_KEY, "10000"),
                Arguments.of(false, SmtpSessionFactory.WRITE_TIMEOUT_KEY, "10000"),
                Arguments.of(true, SmtpSessionFactory.SSL_TRUST, "*"),
                Arguments.of(true, SmtpSessionFactory.CHECK_SERVER_IDENTITY, "false"));
    }

    @ParameterizedTest(name = "trust-all={0}: {1}={2}")
    @MethodSource("sessionProperties")
    @DisplayName("the session carries the expected transport property")
    void configuresTheSession(boolean trustAll, String key, String expected) {
        try (ApplicationContext context = start(trustAll)) {
            Properties properties = context.getBean(Session.class).getProperties();
            assertThat(properties.getProperty(key)).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("a configured user makes the session authenticate")
    void authenticatesWithAUser() {
        Map<String, Object> properties =
                NotificationContexts.smtp(Map.of("smtp.user", "mailer", "smtp.password", "secret"));
        try (ApplicationContext context = NotificationContexts.start(properties)) {
            assertThat(context.getBean(Session.class).getProperty(SmtpSessionFactory.AUTH))
                    .isEqualTo("true");
        }
    }

    static Stream<Arguments> relaxations() {
        return Stream.of(
                Arguments.of(
                        TRUST_ALL_PROPERTY, true, SmtpConfigurationValidator.TRUST_ALL_VARIABLE, 1),
                Arguments.of(
                        TRUST_ALL_PROPERTY,
                        false,
                        SmtpConfigurationValidator.TRUST_ALL_VARIABLE,
                        0),
                Arguments.of(
                        STARTTLS_PROPERTY, false, SmtpConfigurationValidator.STARTTLS_VARIABLE, 1),
                Arguments.of(
                        STARTTLS_PROPERTY, true, SmtpConfigurationValidator.STARTTLS_VARIABLE, 0),
                Arguments.of(
                        PORT_PROPERTY,
                        SmtpConfigurationValidator.IMPLICIT_TLS_PORT,
                        SmtpConfigurationValidator.PORT_VARIABLE,
                        1),
                Arguments.of(
                        PORT_PROPERTY,
                        NotificationConfiguration.DEFAULT_PORT,
                        SmtpConfigurationValidator.PORT_VARIABLE,
                        0));
    }

    @ParameterizedTest(name = "{0}={1} -> {3} warning(s) naming {2}")
    @MethodSource("relaxations")
    @DisplayName("a transport relaxation is warned about at startup, before any session exists")
    void warnsAboutRelaxationsAtStartup(
            String property, Object value, String variable, int expectedWarnings) {
        try (ApplicationContext ignored =
                NotificationContexts.start(NotificationContexts.smtp(Map.of(property, value)))) {
            // Asserted before any Session is fetched: the warning must not wait for the first send.
            assertThat(warnings(variable))
                    .as("startup warnings naming %s", variable)
                    .hasSize(expectedWarnings);
        }
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of(
                        Map.of("smtp.from-address", ""),
                        SmtpConfigurationValidator.FROM_ADDRESS_VARIABLE),
                Arguments.of(
                        Map.of(
                                STARTTLS_PROPERTY,
                                false,
                                "smtp.user",
                                "mailer",
                                "smtp.password",
                                "secret"),
                        SmtpConfigurationValidator.STARTTLS_VARIABLE),
                Arguments.of(
                        Map.of(CLIENT_URL_PROPERTY, "wallet.example.org"),
                        SmtpConfigurationValidator.CLIENT_URL_VARIABLE),
                Arguments.of(
                        Map.of(CLIENT_URL_PROPERTY, "ftp://wallet.example.org"),
                        SmtpConfigurationValidator.CLIENT_URL_VARIABLE),
                Arguments.of(
                        Map.of(CLIENT_URL_PROPERTY, "https:wallet.example.org"),
                        SmtpConfigurationValidator.CLIENT_URL_VARIABLE));
    }

    @Test
    @DisplayName("an absolute https client url is accepted at startup")
    void acceptsAnAbsoluteClientUrl() {
        Map<String, Object> properties =
                NotificationContexts.smtp(
                        Map.of(CLIENT_URL_PROPERTY, "https://wallet.example.org/consents"));
        assertThatCode(() -> NotificationContexts.start(properties).close())
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} -> names {1}")
    @MethodSource("refusals")
    @DisplayName("startup fails on an unusable or unsafe transport and names the variable")
    void refusesToStart(Map<String, Object> overrides, String variable) {
        Map<String, Object> properties = NotificationContexts.smtp(overrides);
        assertThatThrownBy(() -> NotificationContexts.start(properties).close())
                .satisfies(
                        failure -> assertThat(rootCause(failure).getMessage()).contains(variable));
    }

    private static ApplicationContext start(boolean trustAll) {
        return NotificationContexts.start(
                NotificationContexts.smtp(Map.of(TRUST_ALL_PROPERTY, trustAll)));
    }

    private List<String> warnings(String variable) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains(variable))
                .toList();
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
