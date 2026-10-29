package com.seamware.consentmanager.api;

import static com.seamware.consentmanager.api.ParticipantMapper.atUtc;

import com.seamware.consentmanager.api.generated.model.ParticipantNotification;
import com.seamware.consentmanager.api.generated.model.ParticipantNotification.EventTypeEnum;
import com.seamware.consentmanager.api.generated.model.ParticipantNotification.StatusEnum;
import com.seamware.consentmanager.api.generated.model.ParticipantNotificationPage;
import com.seamware.consentmanager.notification.NotificationOutboxEntry;
import com.seamware.consentmanager.notification.NotificationOutboxStatus;
import io.micronaut.data.model.Page;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Renders outbox rows as delivery history; reads columns only, never the subject-naming payload.
 */
@Singleton
public class ParticipantNotificationMapper {

    /** Statuses whose {@code next_attempt_at} is a due time rather than a stale lease or retry. */
    private static final Set<NotificationOutboxStatus> DUE =
            Set.of(NotificationOutboxStatus.PENDING, NotificationOutboxStatus.FAILED);

    /** One page of history, reporting the size actually applied. */
    public ParticipantNotificationPage toPage(Page<NotificationOutboxEntry> page) {
        return new ParticipantNotificationPage(
                page.getContent().stream().map(this::toRepresentation).toList(),
                page.getPageNumber(),
                page.getSize(),
                page.getTotalSize(),
                page.getTotalPages());
    }

    /** One history entry; {@code nextAttemptAt} only while the row is still due. */
    public ParticipantNotification toRepresentation(NotificationOutboxEntry entry) {
        return new ParticipantNotification(
                        entry.getId(),
                        entry.getEventId(),
                        EventTypeEnum.fromValue(entry.getEventType().name()),
                        entry.getConsentId(),
                        StatusEnum.fromValue(entry.getStatus().name()),
                        entry.getAttempts(),
                        atUtc(entry.getCreatedAt()))
                .nextAttemptAt(
                        DUE.contains(entry.getStatus()) ? atUtc(entry.getNextAttemptAt()) : null)
                .deliveredAt(atUtc(entry.getDeliveredAt()))
                .lastError(entry.getLastError());
    }
}
