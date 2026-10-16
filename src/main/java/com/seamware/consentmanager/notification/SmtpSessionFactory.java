package com.seamware.consentmanager.notification;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.email.javamail.sender.DefaultSessionProvider;
import io.micronaut.email.javamail.sender.SessionProvider;
import jakarta.inject.Singleton;
import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import java.time.Duration;
import java.util.Properties;

/**
 * The single mail session source, built only from {@link NotificationConfiguration}; replaces the
 * javamail module's provider so nothing is read from {@code javamail.*}.
 */
@Factory
@Requires(condition = SmtpConfiguredCondition.class)
public class SmtpSessionFactory {

    /** Bounds how long a sender thread waits for the server; JavaMail's own default is infinite. */
    static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(5);

    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    static final Duration WRITE_TIMEOUT = Duration.ofSeconds(10);

    static final String HOST = "mail.smtp.host";
    static final String PORT = "mail.smtp.port";
    static final String AUTH = "mail.smtp.auth";
    static final String STARTTLS_ENABLE = "mail.smtp.starttls.enable";
    static final String STARTTLS_REQUIRED = "mail.smtp.starttls.required";
    static final String CHECK_SERVER_IDENTITY = "mail.smtp.ssl.checkserveridentity";
    static final String SSL_TRUST = "mail.smtp.ssl.trust";
    static final String CONNECTION_TIMEOUT_KEY = "mail.smtp.connectiontimeout";
    static final String READ_TIMEOUT_KEY = "mail.smtp.timeout";
    static final String WRITE_TIMEOUT_KEY = "mail.smtp.writetimeout";

    /** {@code mail.smtp.ssl.trust} value that trusts every host. */
    static final String TRUST_ALL_HOSTS = "*";

    /** The SMTP session every notification is sent through. */
    @Singleton
    Session session(NotificationConfiguration configuration) {
        return Session.getInstance(properties(configuration), authenticator(configuration));
    }

    /** Hands {@link #session} to the javamail sender in place of its default provider. */
    @Bean
    @Singleton
    @Replaces(DefaultSessionProvider.class)
    SessionProvider sessionProvider(Session session) {
        return () -> session;
    }

    private static Properties properties(NotificationConfiguration configuration) {
        Properties properties = new Properties();
        properties.put(HOST, configuration.getHost());
        properties.put(PORT, String.valueOf(configuration.getPort()));
        properties.put(AUTH, String.valueOf(configuration.hasCredentials()));
        properties.put(STARTTLS_ENABLE, String.valueOf(configuration.isStarttls()));
        properties.put(STARTTLS_REQUIRED, String.valueOf(configuration.isStarttls()));
        properties.put(CONNECTION_TIMEOUT_KEY, String.valueOf(CONNECTION_TIMEOUT.toMillis()));
        properties.put(READ_TIMEOUT_KEY, String.valueOf(READ_TIMEOUT.toMillis()));
        properties.put(WRITE_TIMEOUT_KEY, String.valueOf(WRITE_TIMEOUT.toMillis()));
        boolean verifyServer = !configuration.isInsecureTrustAllCertificates();
        properties.put(CHECK_SERVER_IDENTITY, String.valueOf(verifyServer));
        if (!verifyServer) {
            properties.put(SSL_TRUST, TRUST_ALL_HOSTS);
        }
        return properties;
    }

    private static Authenticator authenticator(NotificationConfiguration configuration) {
        if (!configuration.hasCredentials()) {
            return null;
        }
        PasswordAuthentication credentials =
                new PasswordAuthentication(
                        configuration.getUser(),
                        configuration.getPassword() == null ? "" : configuration.getPassword());
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return credentials;
            }
        };
    }
}
