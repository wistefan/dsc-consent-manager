package com.seamware.consentmanager.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.tree.JsonNode;
import io.micronaut.serde.annotation.Serdeable;
import java.util.ArrayList;
import java.util.List;

/**
 * A contract as the Contract Service serves it, covering both the ecosystem and the bilateral
 * shape.
 *
 * <p>One record for two shapes, because the two endpoints answer with documents that share an
 * identifier, a status and a purpose list and differ only in which further members they carry.
 * Every component is therefore nullable and {@link #shape()} says which document arrived. Unknown
 * members are ignored, so an upstream addition does not break the fetch.
 */
@Serdeable
public record ContractResponse(
        @Nullable String uid,
        @JsonProperty("_id") @Nullable String mongoId,
        @Nullable String profile,
        @Nullable String status,
        @Nullable String createdAt,
        @Nullable String updatedAt,
        @Nullable List<Purpose> purpose,
        @Nullable String ecosystem,
        @Nullable String orchestrator,
        @Nullable List<ServiceOffering> serviceOfferings,
        @Nullable List<Member> members,
        @Nullable List<Member> revokedMembers,
        @Nullable String dataProvider,
        @Nullable String dataConsumer,
        @Nullable String serviceOffering,
        @Nullable List<Policy> policy,
        @Nullable List<Signature> signatures,
        @Nullable List<Signature> revokedSignatures,
        @Nullable TerminationAndValidity terminationAndValidity,
        @Nullable String limitationOfLiability,
        @Nullable String termsAndConditions) {

    /** Which of the two documents this is. */
    public enum Shape {
        /** A contract between the members of an ecosystem, orchestrated by one of them. */
        ECOSYSTEM,
        /** A contract between exactly one data provider and one data consumer. */
        BILATERAL
    }

    /**
     * The contract's own identifier, {@code uid} when the document declares it and {@code _id}
     * otherwise.
     *
     * <p>The two are the same value under two names: the service's schema calls it {@code uid}
     * while the stored document carries mongo's {@code _id}, and which one is serialized has
     * varied. Null only for a document declaring neither, which no endpoint produces.
     */
    @Nullable
    public String contractId() {
        return uid != null ? uid : mongoId;
    }

    /**
     * Which shape this document is.
     *
     * <p>Decided on the two parties: {@code dataProvider} and {@code dataConsumer} exist only on a
     * bilateral contract and are mandatory there, where an ecosystem contract names its parties
     * through {@code members} instead. Deliberately read off the document rather than off the
     * endpoint that answered, so a document kept in the cache or in a fixture carries its own
     * shape.
     */
    public Shape shape() {
        return dataProvider != null || dataConsumer != null ? Shape.BILATERAL : Shape.ECOSYSTEM;
    }

    /**
     * The data provider a bilateral contract names, as a participant identifier.
     *
     * <p>Trimmed, and null when the document states nothing, so that the identity of a party is
     * read the same way wherever it is read - the bound check and the stored recipient list cannot
     * disagree over a value the document padded.
     */
    @Nullable
    public String dataProviderIdentifier() {
        return stated(dataProvider);
    }

    /** The data consumer a bilateral contract names, read the same way as the provider. */
    @Nullable
    public String dataConsumerIdentifier() {
        return stated(dataConsumer);
    }

    /** The value's stated content, or null when it states nothing. */
    @Nullable
    private static String stated(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** One ecosystem member's offering, with the policies that member attached to it. */
    @Serdeable
    public record ServiceOffering(
            @Nullable String participant,
            @Nullable String serviceOffering,
            @Nullable List<Policy> policies) {

        /** The participant offering this service, as a participant identifier. */
        @Nullable
        public String participantIdentifier() {
            return stated(participant);
        }
    }

    /** One ODRL rule attached to an offering, as the contract states it. */
    @Serdeable
    public record Policy(@Nullable String ruleId, @Nullable String description) {}

    /**
     * One GDPR purpose the contract declares.
     *
     * <p>The dedicated purpose list, not the policies: these entries are what a privacy notice's
     * purposes section is actually about.
     */
    @Serdeable
    public record Purpose(
            @Nullable String uid,
            @Nullable String purpose,
            @Nullable String purposeCategory,
            @Nullable JsonNode piiCategory,
            @Nullable String consentType,
            @Nullable String termination,
            @Nullable Boolean thirdPartyDisclosure,
            @Nullable String thirdPartyName) {

        /**
         * The PII categories this purpose names, whichever way the document declared them.
         *
         * <p>Held as a tree because the two shapes disagree: the ecosystem document declares one
         * string, the bilateral one an array of them. Either fixed type would fail to decode the
         * other shape and turn a perfectly well-formed contract into a 502, so the one form is read
         * and both are normalised here. Never null; a purpose naming none yields an empty list.
         */
        public List<String> piiCategories() {
            if (piiCategory == null || piiCategory.isNull()) {
                return List.of();
            }
            if (!piiCategory.isArray()) {
                return List.of(piiCategory.coerceStringValue());
            }
            List<String> categories = new ArrayList<>();
            for (JsonNode element : piiCategory.values()) {
                if (!element.isNull()) {
                    categories.add(element.coerceStringValue());
                }
            }
            return List.copyOf(categories);
        }
    }

    /** One participant's membership of an ecosystem contract, signed or not yet signed. */
    @Serdeable
    public record Member(
            @Nullable String participant,
            @Nullable String role,
            @Nullable String signature,
            @Nullable String date) {

        /** The participant this membership names, as a participant identifier. */
        @Nullable
        public String identifier() {
            return stated(participant);
        }
    }

    /** One party's signature on a bilateral contract. */
    @Serdeable
    public record Signature(
            @Nullable String did,
            @Nullable String party,
            @Nullable String value,
            @Nullable String date) {}

    /** When a bilateral contract takes effect and how long terminating it takes. */
    @Serdeable
    public record TerminationAndValidity(
            @Nullable String effectiveDate, @Nullable String terminationPeriod) {}
}
