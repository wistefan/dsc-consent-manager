package com.seamware.consentmanager.support;

import com.seamware.consentmanager.domain.ConsentSnapshot;

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
}
