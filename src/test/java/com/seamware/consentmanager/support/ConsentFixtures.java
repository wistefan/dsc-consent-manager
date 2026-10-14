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
     * A snapshot freezing nothing, for a suite seeding a consent whose terms it never reads.
     *
     * <p>One call site rather than a row of positional nulls in each suite, for the reason {@link
     * PrivacyNoticeFixtures#emptyPayload()} states: every component is nullable, so a reordering
     * would otherwise shift what a seeded consent means with nothing to flag it.
     */
    public static ConsentSnapshot noTerms() {
        return new ConsentSnapshot(null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * The subject's own token, as the resolution filter would have handed it to a handler.
     *
     * <p>Shared for the reason {@link #noTerms()} is: the profile claims are all nullable, so a
     * claim inserted in the middle of the record would otherwise shift what each suite's principal
     * asserts with nothing to flag it.
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
     * The participant with this identifier, registered on first use.
     *
     * <p>Find-or-save rather than save, because a suite sharing a pair across cases seeds it once
     * while its rows outlive each transaction.
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
