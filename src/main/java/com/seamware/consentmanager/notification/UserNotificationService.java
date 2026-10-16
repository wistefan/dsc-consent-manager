package com.seamware.consentmanager.notification;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.User;

/**
 * Informs a data subject about consent events a participant caused. Called after the write has
 * committed; implementations never throw, and a user without an email is skipped silently.
 */
public interface UserNotificationService {

    /** Tells the subject a participant has requested their consent. */
    void consentRequested(User user, Consent consent, Participant participant);

    /** Tells the subject a participant has terminated their consent. */
    void consentTerminated(User user, Consent consent, Participant participant);
}
