package com.seamware.consentmanager.support;

import com.seamware.consentmanager.domain.PrivacyNoticePayload;

/** Privacy notice values the suites seed when the notice's content is beside the point. */
public final class PrivacyNoticeFixtures {

    private PrivacyNoticeFixtures() {}

    /**
     * A payload with every section absent, for a suite seeding a notice it never reads.
     *
     * <p>One call site rather than a row of positional nulls in each suite: the record's components
     * are all nullable, so a reordering or an added component would otherwise shift what a seeded
     * notice means with nothing to flag it.
     */
    public static PrivacyNoticePayload emptyPayload() {
        return new PrivacyNoticePayload(null, null, null, null, null, null, null, null, null);
    }
}
