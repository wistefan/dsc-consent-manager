package com.seamware.consentmanager.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

/**
 * Contract-derived notice content, stored as JSONB in {@code privacy_notices.payload} under the
 * published section headings; a section with no source is null, never empty.
 */
@Serdeable
public record PrivacyNoticePayload(
        @Nullable List<String> purposes,
        @Nullable List<String> categoriesOfData,
        @Nullable List<DataReference> data,
        @Nullable List<Recipient> recipients,
        @Nullable ControllerDetails controllerDetails,
        @Nullable String retentionPeriod,
        @Nullable String piiPrincipalRights,
        @Nullable List<String> internationalTransfers,
        @Nullable String withdrawalOfConsent) {

    /** The data controller, taken from the provider's registration rather than the contract. */
    @Serdeable
    public record ControllerDetails(
            @Nullable String name,
            @Nullable String contact,
            @Nullable String identifier,
            @Nullable String selfDescriptionUri) {}

    /**
     * One party the data reaches; generation stores the identifier only, and a resolved retrieval
     * fills name and contact from the participant registry when a row exists.
     */
    @Serdeable
    public record Recipient(
            @Nullable String identifier,
            @Nullable String name,
            @Nullable String contact,
            @Nullable String selfDescriptionUri) {}

    /**
     * One catalog document the notice covers, by URI; title and description are filled only by a
     * resolved retrieval. {@code partOf} is absent on an offering and names it on a data resource.
     */
    @Serdeable
    public record DataReference(
            @Nullable String uri,
            @Nullable String title,
            @Nullable String description,
            @Nullable String partOf) {

        /** A reference to a document a contract named directly, nothing read from it yet. */
        public static DataReference offering(@Nullable String uri) {
            return new DataReference(uri, null, null, null);
        }
    }
}
