package com.seamware.consentmanager.service;

import com.seamware.consentmanager.client.dto.ContractResponse;
import com.seamware.consentmanager.client.dto.ContractResponse.Member;
import com.seamware.consentmanager.client.dto.ContractResponse.Policy;
import com.seamware.consentmanager.client.dto.ContractResponse.ServiceOffering;
import com.seamware.consentmanager.client.dto.ContractResponse.Shape;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.ControllerDetails;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.DataReference;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.Recipient;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Turns a fetched contract plus the provider's registration into privacy-notice content.
 *
 * <p>Pure (no I/O, no clock), so generation works while the catalog is unreachable. Unsourced
 * sections are absent, not empty; lists keep first-seen order so regeneration compares stably.
 */
@Singleton
public class ContractContentExtractor {

    /**
     * Reserved URN for a disclosed but unnamed third party; reported rather than dropped, so the
     * notice never under-discloses. Clients match it and render it in their own words.
     */
    private static final String UNNAMED_THIRD_PARTY = "urn:consent-manager:third-party:unnamed";

    /** The notice content; the controller comes from the provider's registration. */
    @NonNull
    public NoticeContent extract(
            @NonNull ContractResponse contract, @NonNull Participant provider) {
        PrivacyNoticePayload payload =
                new PrivacyNoticePayload(
                        purposes(contract),
                        categoriesOfData(contract),
                        data(contract, provider),
                        recipients(contract),
                        controllerDetails(provider),
                        retentionPeriod(contract),
                        piiPrincipalRights(contract),
                        internationalTransfers(contract),
                        // Withdrawal is this service's procedure; no contract shape sources it.
                        null);
        return new NoticeContent(title(contract), payload);
    }

    /**
     * The title: the ecosystem or bilateral offering, else the contract id, else none. Usually a
     * catalog URI, since reading a name from the catalog would block generation while it is down.
     */
    @Nullable
    private String title(ContractResponse contract) {
        String subject =
                contract.shape() == Shape.BILATERAL
                        ? contract.serviceOffering()
                        : contract.ecosystem();
        return firstStated(subject, contract.contractId());
    }

    /**
     * The declared purposes (unnamed ones by id); policy descriptions only when none are declared,
     * never appended, as a usage rule is not a processing purpose.
     */
    @Nullable
    private List<String> purposes(ContractResponse contract) {
        List<String> declared =
                orEmpty(contract.purpose()).stream()
                        .map(purpose -> firstStated(purpose.purpose(), purpose.uid()))
                        .filter(Objects::nonNull)
                        .toList();
        if (!declared.isEmpty()) {
            return distinct(declared);
        }
        return distinct(
                policies(contract).stream()
                        .map(policy -> text(policy.description()))
                        .filter(Objects::nonNull)
                        .toList());
    }

    /** The policies the contract attaches, wherever this shape attaches them. */
    private List<Policy> policies(ContractResponse contract) {
        if (contract.shape() == Shape.BILATERAL) {
            return orEmpty(contract.policy());
        }
        return orEmpty(contract.serviceOfferings()).stream()
                .flatMap(offering -> orEmpty(offering.policies()).stream())
                .toList();
    }

    /** The PII categories the purposes name, whichever way each document declared them. */
    @Nullable
    private List<String> categoriesOfData(ContractResponse contract) {
        return distinct(
                orEmpty(contract.purpose()).stream()
                        .flatMap(purpose -> purpose.piiCategories().stream())
                        .map(this::text)
                        .filter(Objects::nonNull)
                        .toList());
    }

    /**
     * The service-offering URIs the notice covers, limited to this provider's own offerings so the
     * controller is never credited with another participant's data.
     */
    @Nullable
    private List<DataReference> data(ContractResponse contract, Participant provider) {
        Stream<String> offerings =
                contract.shape() == Shape.BILATERAL
                        ? Stream.of(contract.serviceOffering())
                        : orEmpty(contract.serviceOfferings()).stream()
                                .filter(
                                        offering ->
                                                Objects.equals(
                                                        offering.participantIdentifier(),
                                                        provider.getIdentifier()))
                                .map(ServiceOffering::serviceOffering);
        List<String> uris = distinct(offerings.map(this::text).filter(Objects::nonNull).toList());
        return uris == null ? null : uris.stream().map(DataReference::offering).toList();
    }

    /**
     * The recipients, by the contract's participant identifiers verbatim ({@code
     * participants.identifier}). Revoked members are excluded; unsigned members are kept.
     */
    @Nullable
    private List<Recipient> recipients(ContractResponse contract) {
        return named(identifiers(contract));
    }

    /** The recipient identifiers the contract names, de-duplicated in first-seen order. */
    @Nullable
    private List<String> identifiers(ContractResponse contract) {
        if (contract.shape() == Shape.BILATERAL) {
            return distinct(
                    Stream.of(contract.dataProviderIdentifier(), contract.dataConsumerIdentifier())
                            .filter(Objects::nonNull)
                            .toList());
        }
        Set<String> revoked = participants(contract.revokedMembers()).collect(Collectors.toSet());
        return distinct(
                participants(contract.members())
                        .filter(participant -> !revoked.contains(participant))
                        .toList());
    }

    /** Identifier-only recipients; resolved retrieval fills in the rest from the registry. */
    @Nullable
    private static List<Recipient> named(@Nullable List<String> identifiers) {
        return identifiers == null
                ? null
                : identifiers.stream()
                        .map(identifier -> new Recipient(identifier, null, null, null))
                        .toList();
    }

    /** The participants a member list names, the ones naming nobody dropped. */
    private Stream<String> participants(@Nullable List<Member> members) {
        return orEmpty(members).stream().map(Member::identifier).filter(Objects::nonNull);
    }

    /** The controller, as this service has the provider registered. */
    private ControllerDetails controllerDetails(Participant provider) {
        return new ControllerDetails(
                text(provider.getLegalName()),
                text(provider.getEmail()),
                text(provider.getIdentifier()),
                text(provider.getSelfDescriptionUri()));
    }

    /**
     * Retention in the contract's own wording (ISO-8601 or prose; display only). An ecosystem
     * contract reports it only when every purpose states the same value.
     */
    @Nullable
    private String retentionPeriod(ContractResponse contract) {
        if (contract.shape() == Shape.BILATERAL) {
            return contract.terminationAndValidity() == null
                    ? null
                    : text(contract.terminationAndValidity().terminationPeriod());
        }
        // Nulls survive distinct(), so a purpose stating no termination counts as disagreeing.
        List<String> stated =
                orEmpty(contract.purpose()).stream()
                        .map(purpose -> text(purpose.termination()))
                        .distinct()
                        .toList();
        return stated.size() == 1 ? stated.getFirst() : null;
    }

    /** The principal's rights: the contract's terms and conditions, from either shape. */
    @Nullable
    private String piiPrincipalRights(ContractResponse contract) {
        return text(contract.termsAndConditions());
    }

    /**
     * Third parties the purposes declare disclosure to, unnamed ones as {@link
     * #UNNAMED_THIRD_PARTY}. No jurisdiction is known, so an entry may be domestic.
     */
    @Nullable
    private List<String> internationalTransfers(ContractResponse contract) {
        return distinct(
                orEmpty(contract.purpose()).stream()
                        .filter(purpose -> Boolean.TRUE.equals(purpose.thirdPartyDisclosure()))
                        .map(purpose -> firstStated(purpose.thirdPartyName(), UNNAMED_THIRD_PARTY))
                        .toList());
    }

    /** The first of the two values that states anything, or null when neither does. */
    @Nullable
    private String firstStated(@Nullable String preferred, @Nullable String fallback) {
        String stated = text(preferred);
        return stated != null ? stated : text(fallback);
    }

    /** The value's stated content, or null when it states nothing. */
    @Nullable
    private String text(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The values de-duplicated in first-seen order, or null when there are none. */
    @Nullable
    private List<String> distinct(List<String> values) {
        List<String> unique = values.stream().distinct().toList();
        return unique.isEmpty() ? null : unique;
    }

    /** The list, or an empty one when the document omitted it. */
    private <T> List<T> orEmpty(@Nullable List<T> values) {
        return values == null ? List.of() : values;
    }
}
