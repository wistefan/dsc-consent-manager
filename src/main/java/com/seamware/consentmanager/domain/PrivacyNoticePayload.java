package com.seamware.consentmanager.domain;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

/**
 * The contract-derived content of a privacy notice, stored as JSONB in the {@code payload} column
 * of {@code privacy_notices}.
 *
 * <p>Named after the headings a privacy notice is published under, so the stored form and the
 * served form of a legal document do not disagree on what a section is called. Every component is
 * nullable because a contract need not carry a source for every heading, and an absent component is
 * the honest encoding of that: a section with no source is left out rather than written as an empty
 * string or an empty list.
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

    /**
     * The data controller, named and reachable.
     *
     * <p>Taken from the provider participant's own registration rather than from the contract, so
     * the notice names the controller as this service knows it.
     */
    @Serdeable
    public record ControllerDetails(
            @Nullable String name,
            @Nullable String contact,
            @Nullable String identifier,
            @Nullable String selfDescriptionUri) {}

    /**
     * One party the data reaches, named as the contract names it.
     *
     * <p>Generation stores the identifier alone, because that is all a contract carries; the name
     * and contact are filled in by a resolved retrieval reading this service's own participant
     * registry, and a recipient with no row here keeps its bare identifier.
     */
    @Serdeable
    public record Recipient(
            @Nullable String identifier,
            @Nullable String name,
            @Nullable String contact,
            @Nullable String selfDescriptionUri) {}

    /**
     * One catalog document the notice covers, by URI.
     *
     * <p>Generation stores the URI alone, because that is all a contract names; the title and
     * description are filled in by a resolved retrieval, which is the only place the catalog
     * document is actually read.
     */
    @Serdeable
    public record DataReference(
            @Nullable String uri, @Nullable String title, @Nullable String description) {}
}
