package com.seamware.consentmanager.notification;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Nullable;
import lombok.Getter;
import lombok.Setter;

/**
 * Outgoing mail transport, bound under {@code smtp}. Blank strings stand for unset values, since
 * the {@code ${VAR:}} placeholders in application.yml resolve an unset variable to the empty
 * string.
 */
@Getter
@Setter
@ConfigurationProperties("smtp")
public class NotificationConfiguration {

    /** Submission port, the STARTTLS default. */
    public static final int DEFAULT_PORT = 587;

    @Nullable private String host;
    private int port = DEFAULT_PORT;
    @Nullable private String user;

    /** Never logged; excluded from {@link #toString()}. */
    @Nullable private String password;

    private boolean starttls = true;
    @Nullable private String fromAddress;
    @Nullable private String fromName;

    /** Dev-only opt-out: trust every certificate and skip the host-name check. */
    private boolean insecureTrustAllCertificates;

    /** True when a user is set, so the session authenticates. */
    public boolean hasCredentials() {
        return user != null && !user.isBlank();
    }

    @Override
    public String toString() {
        return "NotificationConfiguration{host=%s, port=%d, user=%s, starttls=%s, fromAddress=%s, insecureTrustAllCertificates=%s}"
                .formatted(host, port, user, starttls, fromAddress, insecureTrustAllCertificates);
    }
}
