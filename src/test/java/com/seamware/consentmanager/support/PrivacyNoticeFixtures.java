package com.seamware.consentmanager.support;

import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.DataReference;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.Recipient;
import java.util.List;

/** Privacy notice values the suites seed when the notice's content is beside the point. */
public final class PrivacyNoticeFixtures {

    private PrivacyNoticeFixtures() {}

    /**
     * A payload with every section absent, for a suite seeding a notice it never reads. Shared so a
     * reordered or added nullable component cannot silently shift seeded notices.
     */
    public static PrivacyNoticePayload emptyPayload() {
        return new PrivacyNoticePayload(null, null, null, null, null, null, null, null, null);
    }

    /**
     * A payload carrying only what a resolved retrieval dereferences. Contract-filled headings stay
     * absent, so a response value can only come from the contract.
     */
    public static PrivacyNoticePayload payloadToResolve(
            List<String> purposes, List<DataReference> data, List<Recipient> recipients) {
        return new PrivacyNoticePayload(
                purposes, null, data, recipients, null, null, null, null, null);
    }

    /** A data reference as generation stores one: the URI alone, nothing dereferenced yet. */
    public static DataReference reference(String uri) {
        return DataReference.offering(uri);
    }

    /** A recipient as generation stores one: the identifier alone, with no local row read. */
    public static Recipient recipient(String identifier) {
        return new Recipient(identifier, null, null, null);
    }
}
