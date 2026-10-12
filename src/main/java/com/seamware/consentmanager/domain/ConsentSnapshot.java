package com.seamware.consentmanager.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

/**
 * The terms a user actually decided on, frozen at decision time and stored as JSONB in the {@code
 * snapshot} column of {@code consents}.
 *
 * <p>Written in {@link PrivacyNoticePayload}'s vocabulary, so the notice and the consent do not
 * disagree on what a term is called, with two deliberate differences. {@code purposes} and {@code
 * dataCategories} hold the <em>elected</em> subset rather than the notice's full lists, because
 * that is what was consented to. {@code dataReferences} keeps the payload's {@code data} under a
 * name that cannot be read as a category: the elected categories say what kind of data was
 * consented to, the references say which documents, and an auditor reading a consent whose notice
 * has since been archived needs both.
 *
 * <p>The notice's {@code contractUri} is not copied in. {@code consents.contract_uri} already
 * freezes it, and a second copy here would be a second source of truth that can disagree.
 *
 * <p>Every component is nullable, mirroring the payload: a notice need not carry a source for every
 * term, and an absent term is left out rather than written as an empty list.
 */
@Serdeable
public record ConsentSnapshot(
        @Nullable String title,
        @Nullable List<String> purposes,
        @Nullable List<String> dataCategories,
        @Nullable List<PrivacyNoticePayload.DataReference> dataReferences,
        @Nullable List<PrivacyNoticePayload.Recipient> recipients,
        @Nullable PrivacyNoticePayload.ControllerDetails controllerDetails,
        @Nullable String retentionPeriod,
        @Nullable String piiPrincipalRights,
        @Nullable List<String> internationalTransfers,
        @Nullable String withdrawalOfConsent) {

    /**
     * Freezes {@code notice}'s terms, narrowed to the elected scope.
     *
     * <p>Only an absent (null) election takes the notice's own list, which is what a decision that
     * names no subset consents to. An explicitly empty election freezes an empty scope rather than
     * the whole list, so the narrowest reading of a malformed decision is the one recorded and the
     * service's refusal of an empty election is a second guard rather than the only one. The
     * non-scope terms are copied verbatim.
     */
    public static ConsentSnapshot of(
            PrivacyNotice notice,
            @Nullable List<String> electedPurposes,
            @Nullable List<String> electedDataCategories) {
        PrivacyNoticePayload payload = notice.getPayload();
        return new ConsentSnapshot(
                notice.getTitle(),
                elected(electedPurposes, payload.purposes()),
                elected(electedDataCategories, payload.categoriesOfData()),
                payload.data(),
                payload.recipients(),
                payload.controllerDetails(),
                payload.retentionPeriod(),
                payload.piiPrincipalRights(),
                payload.internationalTransfers(),
                payload.withdrawalOfConsent());
    }

    @Nullable
    private static List<String> elected(
            @Nullable List<String> elected, @Nullable List<String> declared) {
        return elected == null ? declared : List.copyOf(elected);
    }
}
