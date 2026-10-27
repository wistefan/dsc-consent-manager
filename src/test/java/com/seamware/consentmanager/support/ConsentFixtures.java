package com.seamware.consentmanager.support;

import com.seamware.consentmanager.domain.ConsentSnapshot;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.security.UserPrincipal;
import java.util.Map;

/** Consent values the suites seed when the consent's content is beside the point. */
public final class ConsentFixtures {

    private ConsentFixtures() {}

    /**
     * A snapshot freezing nothing, for a suite seeding a consent whose terms it never reads. Shared
     * for {@link PrivacyNoticeFixtures#emptyPayload()}'s reason.
     */
    public static ConsentSnapshot noTerms() {
        return new ConsentSnapshot(null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * The subject's own token, as the resolution filter would hand it to a handler. Shared so an
     * inserted nullable claim cannot silently shift each suite's principal.
     */
    public static UserPrincipal principalFor(String issuer, User subject) {
        return new UserPrincipal(
                issuer,
                subject.getIdentifier(),
                subject.getIdentifier(),
                null,
                false,
                null,
                null,
                null,
                subject,
                false);
    }

    /**
     * The participant with this identifier, registered on first use. Find-or-save, since a suite's
     * shared rows outlive each transaction.
     */
    public static Participant participant(ParticipantRepository participants, String identifier) {
        return participants
                .findByIdentifier(identifier)
                .orElseGet(
                        () ->
                                participants.save(
                                        new Participant(
                                                identifier,
                                                identifier,
                                                null,
                                                null,
                                                Map.of(),
                                                null)));
    }
}
