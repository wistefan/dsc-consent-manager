package com.seamware.consentmanager.notification;

import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.exceptions.ConfigurationException;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks the SMTP settings while the context starts, so a broken or insecure transport is reported
 * at boot rather than on the first send, which may be hours later or never.
 */
@Context
@Requires(condition = SmtpConfiguredCondition.class)
public class SmtpConfigurationValidator {

    private static final Logger LOG = LoggerFactory.getLogger(SmtpConfigurationValidator.class);

    static final String FROM_ADDRESS_VARIABLE = "SMTP_FROM_ADDRESS";
    static final String TRUST_ALL_VARIABLE = "SMTP_INSECURE_TRUST_ALL_CERTIFICATES";
    static final String STARTTLS_VARIABLE = "SMTP_STARTTLS";
    static final String PORT_VARIABLE = "SMTP_PORT";
    static final String CLIENT_URL_VARIABLE = "NOTIFICATION_CLIENT_URL";

    /** SMTPS port, which speaks TLS before the greeting; this session only negotiates STARTTLS. */
    static final int IMPLICIT_TLS_PORT = 465;

    private static final Set<String> CLIENT_URL_SCHEMES = Set.of("http", "https");

    private final NotificationConfiguration configuration;
    private final ConsentManagerConfiguration.Notifications notifications;

    SmtpConfigurationValidator(
            NotificationConfiguration configuration,
            ConsentManagerConfiguration.Notifications notifications) {
        this.configuration = configuration;
        this.notifications = notifications;
    }

    /**
     * Fails startup on a blank sender, on credentials over a plaintext session or on a client URL
     * no mail client can open, and warns once about each transport relaxation.
     */
    @PostConstruct
    void validate() {
        String fromAddress = configuration.getFromAddress();
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new ConfigurationException(
                    "SMTP_HOST is set but %s (smtp.from-address) is blank; notifications need a sender address"
                            .formatted(FROM_ADDRESS_VARIABLE));
        }
        if (!configuration.isStarttls()) {
            if (configuration.hasCredentials()) {
                throw new ConfigurationException(
                        "%s is false while SMTP_USER is set; the password would cross the network in plaintext"
                                .formatted(STARTTLS_VARIABLE));
            }
            LOG.warn(
                    "{} is false: notifications are sent unencrypted. Use this only against a local test server.",
                    STARTTLS_VARIABLE);
        }
        if (configuration.isInsecureTrustAllCertificates()) {
            LOG.warn(
                    "{} is on: SMTP server certificates and host names are not verified. Never use this outside development.",
                    TRUST_ALL_VARIABLE);
        }
        if (configuration.getPort() == IMPLICIT_TLS_PORT) {
            LOG.warn(
                    "{} is {}, the implicit-TLS port, which is not supported: every send will time out. Use the STARTTLS submission port.",
                    PORT_VARIABLE,
                    IMPLICIT_TLS_PORT);
        }
        validateClientUrl(notifications.getClientUrl());
    }

    private static void validateClientUrl(URI clientUrl) {
        if (clientUrl == null) {
            return;
        }
        String scheme = clientUrl.getScheme();
        boolean openable =
                clientUrl.isAbsolute()
                        && CLIENT_URL_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))
                        && clientUrl.getHost() != null;
        if (!openable) {
            throw new ConfigurationException(
                    "%s must be an absolute http(s) URL with a host; a mail client cannot open the configured value"
                            .formatted(CLIENT_URL_VARIABLE));
        }
    }
}
