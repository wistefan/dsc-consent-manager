package com.seamware.consentmanager.notification;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.exceptions.ConfigurationException;
import jakarta.annotation.PostConstruct;
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

    private final NotificationConfiguration configuration;

    SmtpConfigurationValidator(NotificationConfiguration configuration) {
        this.configuration = configuration;
    }

    /**
     * Fails startup on a blank sender or on credentials over a plaintext session, and warns once
     * about each transport relaxation.
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
    }
}
