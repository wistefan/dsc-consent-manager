package com.seamware.consentmanager.notification;

import com.seamware.consentmanager.domain.ConsentStatus;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Which participants a consent move notifies, and as what. Only a move that creates or withdraws
 * permission (held or target {@code GRANTED}) notifies; its initiator is never told of its own
 * move.
 */
public final class NotificationRecipients {

    /** What withdrawing a grant reports, per the status the grant moves to. */
    private static final Map<ConsentStatus, NotificationEventType> WITHDRAWALS = withdrawals();

    private NotificationRecipients() {}

    /** One move's notification: what it reports and to whom; {@code recipients} is never empty. */
    public record Notification(NotificationEventType eventType, Set<UUID> recipients) {}

    /**
     * The notification a move owes, or empty when it changes no permission or leaves no one to
     * tell. {@code held} is null for a creation; {@code initiator} is null for a move no party
     * asked for.
     */
    @NonNull
    public static Optional<Notification> of(
            @Nullable ConsentStatus held,
            @NonNull ConsentStatus target,
            @NonNull UUID providerId,
            @NonNull UUID consumerId,
            @Nullable UUID initiator) {
        return eventType(held, target)
                .flatMap(
                        type -> {
                            Set<UUID> recipients = new LinkedHashSet<>();
                            recipients.add(providerId);
                            recipients.add(consumerId);
                            if (initiator != null) {
                                recipients.remove(initiator);
                            }
                            return recipients.isEmpty()
                                    ? Optional.empty()
                                    : Optional.of(new Notification(type, Set.copyOf(recipients)));
                        });
    }

    private static Optional<NotificationEventType> eventType(
            @Nullable ConsentStatus held, ConsentStatus target) {
        if (held == ConsentStatus.GRANTED) {
            return Optional.ofNullable(WITHDRAWALS.get(target));
        }
        return target == ConsentStatus.GRANTED
                ? Optional.of(NotificationEventType.CONSENT_GRANTED)
                : Optional.empty();
    }

    private static Map<ConsentStatus, NotificationEventType> withdrawals() {
        Map<ConsentStatus, NotificationEventType> table = new EnumMap<>(ConsentStatus.class);
        table.put(ConsentStatus.REVOKED, NotificationEventType.CONSENT_REVOKED);
        table.put(ConsentStatus.TERMINATED, NotificationEventType.CONSENT_TERMINATED);
        table.put(ConsentStatus.EXPIRED, NotificationEventType.CONSENT_EXPIRED);
        return Map.copyOf(table);
    }
}
