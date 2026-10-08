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
 * Turns a fetched contract into the content of a privacy notice.
 *
 * <p>Pure: no I/O, no repository, no clock. Every section is derived from the contract document
 * itself plus the provider's own registration, which is what lets a notice be generated while the
 * catalog behind the contract is unreachable - the catalog documents are dereferenced later, when a
 * notice is served resolved.
 *
 * <p>A section the contract carries no source for is left absent rather than empty, and every list
 * is de-duplicated preserving first-seen order. The ordering is not cosmetic: repeated generation
 * compares the payload it would store against the stored one, so a payload that reshuffled would
 * report a contract change that did not happen.
 */
@Singleton
public class ContractContentExtractor {

    /**
     * Stands in for a third party a purpose discloses to without naming it.
     *
     * <p>Dropping such a purpose would publish a notice saying the data goes to no third party
     * while the contract says it does, which is the under-disclosure direction and the one that
     * harms the data subject. The disclosure is reported; only the name is missing.
     *
     * <p>A reserved URN rather than prose, because the entries around it are party names a client
     * displays verbatim: a client matches this one and renders it in its own words, and a third
     * party actually called "Unnamed third party" stays distinguishable from a missing name.
     */
    private static final String UNNAMED_THIRD_PARTY = "urn:consent-manager:third-party:unnamed";

    /**
     * The contract's content as a notice states it, with the controller taken from the provider's
     * registration rather than from the contract.
     */
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
                        // Withdrawing consent is a procedure of this service, not a term of a
                        // contract between two participants, so neither shape carries a source for
                        // it. Filled once consent withdrawal exists.
                        null);
        return new NoticeContent(title(contract), payload);
    }

    /**
     * The notice's title: the contract's subject, falling back to its identifier.
     *
     * <p>Derived rather than read, because neither contract shape carries a title. The subject is
     * the ecosystem a contract governs or the offering a bilateral contract is about; a contract
     * naming neither that nor an identifier yields no title at all.
     *
     * <p>Both subjects are catalog URIs in practice, so the stored title usually is one. That is
     * deliberate: a human-readable name lives in the catalog document rather than in the contract,
     * and reading it at generation would make a notice ungeneratable while the catalog is down.
     * Serving a resolved name instead is retrieval's to decide, against the self-description it
     * already fetches; the stored column stays as derived either way.
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
     * What the contract says the data is processed for.
     *
     * <p>The dedicated purpose list first, since its entries are GDPR purposes and the policies are
     * ODRL rules; the policy descriptions only when the contract declares no purposes, so a notice
     * says something about processing rather than nothing. A purpose stating no text is named by
     * its identifier rather than dropped.
     *
     * <p>The fallback is total on purpose: where purposes exist the policy descriptions are dropped
     * rather than appended, because a usage rule listed as a processing purpose would misstate what
     * the data is processed for. Carrying them under a heading of their own is a change to the
     * notice's published vocabulary, which the spec owns and this mapping does not.
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
     * The catalog documents the notice covers, by URI and nothing else.
     *
     * <p>Service offerings, because a contract names offerings and never the data resources behind
     * them; resolving those is a retrieval-time walk of the catalog.
     *
     * <p>Only the offerings this notice's own provider makes. An ecosystem contract may name
     * several participants as offering a service, and listing another participant's offering under
     * a notice that names this provider as controller would say it controls data it does not
     * provide.
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
        return uris == null
                ? null
                : uris.stream().map(uri -> new DataReference(uri, null, null)).toList();
    }

    /**
     * The participants the data reaches under this contract.
     *
     * <p>Each party is reported as the contract names it, with no normalisation: a contract names
     * its parties by the dataspace's global participant identifier, which is the value {@code
     * participants.identifier} holds, so a recipient resolves through {@code findByIdentifier} and
     * never through a self-description URL. A deployment whose contracts name parties some other
     * way registers its participants under that same value; this service adds no second lookup.
     *
     * <p>A revoked member is excluded wherever it appears: it is no longer party to the contract,
     * and naming it as a recipient would overstate who receives the data. A member that has not
     * signed yet is kept, because it is still named as a recipient-to-be and omitting it would
     * understate who receives the data - the direction a notice must not err in.
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

    /**
     * Each identifier as a recipient carrying nothing else.
     *
     * <p>A contract names its parties and describes none of them; the remaining components are what
     * a resolved retrieval fills in from this service's participant registry.
     */
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
     * How long the data is kept.
     *
     * <p>A bilateral contract states it once, in its termination terms. An ecosystem contract
     * states it per purpose, so it is reported only when every purpose agrees on it: a purpose
     * naming none, or two naming different periods, leaves the section absent rather than letting
     * one purpose's period stand for the whole notice.
     *
     * <p>Reported in the contract's own wording, which is not one format: a bilateral termination
     * period is an ISO-8601 duration, an ecosystem purpose's termination is free prose. A reader
     * displays it and does not parse it.
     */
    @Nullable
    private String retentionPeriod(ContractResponse contract) {
        if (contract.shape() == Shape.BILATERAL) {
            return contract.terminationAndValidity() == null
                    ? null
                    : text(contract.terminationAndValidity().terminationPeriod());
        }
        // Nulls survive distinct(), so a purpose stating no termination is one more disagreeing
        // value rather than an invisible one.
        List<String> stated =
                orEmpty(contract.purpose()).stream()
                        .map(purpose -> text(purpose.termination()))
                        .distinct()
                        .toList();
        return stated.size() == 1 ? stated.getFirst() : null;
    }

    /**
     * What the PII principal may do about the data, in the contract's own words.
     *
     * <p>The contract's terms and conditions, read wherever a document states them rather than only
     * on the shape that usually does: an ecosystem contract carrying terms would otherwise have
     * them dropped, and the two shapes share the member. That one block of text fills this heading
     * alone - printing it again under withdrawal of consent would dress one paragraph up as two
     * sections.
     */
    @Nullable
    private String piiPrincipalRights(ContractResponse contract) {
        return text(contract.termsAndConditions());
    }

    /**
     * The third parties a purpose discloses to, named only where disclosure is declared.
     *
     * <p>This section reports third-party disclosure as the contract declares it, which is not the
     * same heading as transfer to a third country: neither contract shape carries a jurisdiction
     * for a third party, so a named recipient here may well be domestic. A disclosure the contract
     * declares without naming the party is still reported, under {@link #UNNAMED_THIRD_PARTY}.
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
