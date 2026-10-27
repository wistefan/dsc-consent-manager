package com.seamware.consentmanager.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.tree.JsonNode;
import io.micronaut.serde.annotation.Serdeable;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * A Contract Service contract in either the ecosystem or bilateral shape; every component is
 * nullable, unknown members are ignored, and {@link #shape()} says which arrived.
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

    /** The contract's identifier: {@code uid}, else the same value as {@code _id}; else null. */
    @Nullable
    public String contractId() {
        return uid != null ? uid : mongoId;
    }

    /**
     * Bilateral when it names {@code dataProvider} or {@code dataConsumer}; read off the document,
     * not the endpoint, so a cached document carries its own shape.
     */
    public Shape shape() {
        return dataProvider != null || dataConsumer != null ? Shape.BILATERAL : Shape.ECOSYSTEM;
    }

    /** The bilateral data provider's identifier, trimmed, null when blank, so readers agree. */
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

    /** One GDPR purpose from the dedicated purpose list, the source of a notice's purposes. */
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
         * The PII categories, normalised from a string (ecosystem) or an array (bilateral); never
         * null.
         */
        public List<String> piiCategories() {
            if (piiCategory == null || piiCategory.isNull()) {
                return List.of();
            }
            if (!piiCategory.isArray()) {
                return List.of(piiCategory.coerceStringValue());
            }
            return StreamSupport.stream(piiCategory.values().spliterator(), false)
                    .filter(element -> !element.isNull())
                    .map(JsonNode::coerceStringValue)
                    .collect(Collectors.toUnmodifiableList());
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
