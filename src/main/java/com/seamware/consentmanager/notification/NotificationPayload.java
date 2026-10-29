package com.seamware.consentmanager.notification;

import com.seamware.consentmanager.domain.ConsentStatus;
import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;
import java.util.UUID;

/**
 * The document a participant is sent, frozen at enqueue so every retry signs the same bytes. {@code
 * eventId} is the reported {@code consent_events} id, the recipient's deduplication key.
 */
@Serdeable
public record NotificationPayload(
        NotificationEventType eventType,
        UUID eventId,
        Instant occurredAt,
        UUID consentId,
        UUID privacyNoticeId,
        String userIdentifier,
        ConsentStatus status,
        boolean consented) {}
