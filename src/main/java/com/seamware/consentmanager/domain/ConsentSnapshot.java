package com.seamware.consentmanager.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

/**
 * The terms a user decided on, frozen as JSONB in {@code consents.snapshot}, in {@link
 * PrivacyNoticePayload}'s vocabulary but holding only the elected purposes and categories. Every
 * component is nullable; {@code contractUri} is frozen in {@code consents.contract_uri}.
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
     * Freezes {@code notice}'s terms narrowed to the elections; a null election takes the notice's
     * full list, an empty one freezes an empty scope.
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
