package com.seamware.consentmanager.notification;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.notification.NotificationRecipients.Notification;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.service.RecordedConsent;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import java.util.UUID;

/**
 * Queues the participant notifications a consent move owes, inside the move's own transaction, so
 * the move and its notifications commit or roll back together.
 */
@Singleton
public class ParticipantNotificationService {

    private static final String SUBJECT_MISSING =
            "Consent %s names user %s, which does not exist; its notification cannot be queued.";

    private final NotificationOutboxRepository outbox;
    private final UserRepository users;

    public ParticipantNotificationService(
            NotificationOutboxRepository outbox, UserRepository users) {
        this.outbox = outbox;
        this.users = users;
    }

    /**
     * Saves one outbox row per recipient of {@code recorded}, a move from {@code held} (null for a
     * creation) asked for by {@code initiator} (null when no party asked). Must run inside the
     * move's transaction and after its event is stored; refuses to run outside one.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public void enqueue(
            @NonNull RecordedConsent recorded,
            @Nullable ConsentStatus held,
            @Nullable UUID initiator) {
        Consent consent = recorded.consent();
        NotificationRecipients.of(
                        held,
                        consent.getStatus(),
                        consent.getProviderId(),
                        consent.getConsumerId(),
                        initiator)
                .ifPresent(notification -> save(consent, recorded.event(), notification));
    }

    private void save(Consent consent, ConsentEvent event, Notification notification) {
        NotificationPayload payload =
                new NotificationPayload(
                        notification.eventType(),
                        event.getId(),
                        event.getOccurredAt(),
                        consent.getId(),
                        consent.getPrivacyNoticeId(),
                        subjectIdentifier(consent),
                        consent.getStatus(),
                        consent.isConsented());
        outbox.saveAll(
                notification.recipients().stream()
                        .map(recipient -> NotificationOutboxEntry.pending(recipient, payload))
                        .toList());
    }

    /** The subject's current identifier; the consent's foreign key guarantees the row exists. */
    private String subjectIdentifier(Consent consent) {
        return users.findById(consent.getUserId())
                .map(User::getIdentifier)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        SUBJECT_MISSING.formatted(
                                                consent.getId(), consent.getUserId())));
    }
}
