package com.seamware.consentmanager.notification;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentSnapshot;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.User;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Unpersisted users, participants and consents for the notification suites. */
final class NotificationTestFixtures {

    static final String SUBJECT_IDENTIFIER = "did:web:subject.example.org";
    static final String PARTICIPANT_IDENTIFIER = "did:web:acme.example.org";
    static final String BENIGN_LEGAL_NAME = "ACME Data GmbH";
    static final String BENIGN_TITLE = "Fitness Analytics";
    static final String FIRST_NAME = "Erika";
    static final String LAST_NAME = "Mustermann";
    static final String HOSTILE_LEGAL_NAME = "ACME - confirm at https://evil.example/verify";
    static final String HOSTILE_TITLE = "Analytics, see HTTP://evil.example or www.evil.example";
    static final Instant TERMINATED_AT = Instant.parse("2026-03-04T05:06:07.891234Z");
    static final String TERMINATED_AT_RENDERED = "2026-03-04T05:06:07Z";

    private NotificationTestFixtures() {}

    static User user(String email, String firstName, String lastName) {
        return new User(SUBJECT_IDENTIFIER, email, firstName, lastName);
    }

    static Participant participant(String legalName) {
        return new Participant(PARTICIPANT_IDENTIFIER, legalName, null, null, Map.of(), null);
    }

    /** A consent whose frozen notice carries {@code title} and whose last move is fixed. */
    static Consent consent(User user, Participant participant, String title) {
        Consent consent =
                Consent.withStatus(
                        ConsentStatus.TERMINATED,
                        user.getId(),
                        UUID.randomUUID(),
                        participant.getId(),
                        participant.getId(),
                        null,
                        null,
                        new ConsentSnapshot(
                                title, null, null, null, null, null, null, null, null, null));
        consent.setUpdatedAt(TERMINATED_AT);
        return consent;
    }
}
