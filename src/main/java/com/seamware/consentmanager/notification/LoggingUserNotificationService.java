package com.seamware.consentmanager.notification;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.User;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stand-in bound while no SMTP host is configured: sends nothing and logs no address. */
@Singleton
@Requires(condition = SmtpNotConfiguredCondition.class)
public class LoggingUserNotificationService implements UserNotificationService {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingUserNotificationService.class);

    @Override
    public void consentRequested(User user, Consent consent, Participant participant) {
        log("consent-requested", user, consent);
    }

    @Override
    public void consentTerminated(User user, Consent consent, Participant participant) {
        log("consent-terminated", user, consent);
    }

    private static void log(String kind, User user, Consent consent) {
        LOG.debug(
                "SMTP not configured; {} notification to user {} for consent {} not sent",
                kind,
                user.getId(),
                consent.getId());
    }
}
