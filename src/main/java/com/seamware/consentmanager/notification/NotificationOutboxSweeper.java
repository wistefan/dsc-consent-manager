package com.seamware.consentmanager.notification;

import com.seamware.consentmanager.config.ConsentManagerConfiguration.ParticipantNotifications;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abandons and redacts rows past the max age, then deletes history past retention. Runs whether or
 * not delivery is enabled, since it is the bound on keeping an erased subject's identifier
 * (ADR-0007); it sends nothing, so it is safe on every replica.
 */
@Singleton
@Requires(beans = DataSource.class)
public class NotificationOutboxSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationOutboxSweeper.class);

    private final NotificationOutboxRepository outbox;
    private final ParticipantNotifications settings;

    NotificationOutboxSweeper(
            NotificationOutboxRepository outbox, ParticipantNotifications settings) {
        this.outbox = outbox;
        this.settings = settings;
    }

    /** One sweep; scheduled from startup on, public so tests drive it. */
    @Scheduled(
            fixedDelay = "${consent-manager.participant-notifications.sweep-interval}",
            initialDelay = "0s")
    public void sweepOnce() {
        int abandoned = outbox.abandonOlderThan(settings.getMaxAge().toSeconds());
        if (abandoned > 0) {
            LOG.warn(
                    "{} undelivered notifications abandoned: reason={}",
                    abandoned,
                    NotificationOutboxRepository.AbandonReason.MAX_AGE_EXCEEDED);
        }
        int deleted = outbox.deleteTerminalOlderThan(settings.getRetention().toSeconds());
        if (deleted > 0) {
            LOG.info("{} notifications past retention deleted", deleted);
        }
    }
}
