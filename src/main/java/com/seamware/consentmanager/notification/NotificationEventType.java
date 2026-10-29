package com.seamware.consentmanager.notification;

import io.micronaut.serde.annotation.Serdeable;

/** What a participant notification reports; mirrors {@code chk_notification_outbox_event_type}. */
@Serdeable
public enum NotificationEventType {
    CONSENT_GRANTED,
    CONSENT_REVOKED,
    CONSENT_TERMINATED,
    CONSENT_EXPIRED
}
