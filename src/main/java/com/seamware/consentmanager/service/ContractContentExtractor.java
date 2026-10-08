package com.seamware.consentmanager.service;

import com.seamware.consentmanager.client.dto.ContractResponse;
import com.seamware.consentmanager.client.dto.ContractResponse.Policy;
import com.seamware.consentmanager.client.dto.ContractResponse.Purpose;
import com.seamware.consentmanager.client.dto.ContractResponse.Shape;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.ControllerDetails;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.DataReference;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
                        data(contract),
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
     */
    @Nullable
    private String title(ContractResponse contract) {
        String subject =
                contract.shape() == Shape.BILATERAL
                        ? contract.serviceOffering()
                        : contract.ecosystem();
        String stated = text(subject);
        return stated != null ? stated : text(contract.contractId());
    }

    /**
     * What the contract says the data is processed for.
     *
     * <p>The dedicated purpose list first, since its entries are GDPR purposes and the policies are
     * ODRL rules; the policy descriptions only when the contract declares no purposes, so a notice
     * says something about processing rather than nothing.
     */
    @Nullable
    private List<String> purposes(ContractResponse contract) {
        List<String> declared = new ArrayList<>();
        for (Purpose purpose : orEmpty(contract.purpose())) {
            String stated = text(purpose.purpose());
            String named = stated != null ? stated : text(purpose.uid());
            if (named != null) {
                declared.add(named);
            }
        }
        if (!declared.isEmpty()) {
            return distinct(declared);
        }
        List<String> descriptions = new ArrayList<>();
        for (Policy policy : policies(contract)) {
            String description = text(policy.description());
            if (description != null) {
                descriptions.add(description);
            }
        }
        return distinct(descriptions);
    }

    /** The policies the contract attaches, wherever this shape attaches them. */
    private List<Policy> policies(ContractResponse contract) {
        if (contract.shape() == Shape.BILATERAL) {
            return orEmpty(contract.policy());
        }
        List<Policy> policies = new ArrayList<>();
        for (ContractResponse.ServiceOffering offering : orEmpty(contract.serviceOfferings())) {
            policies.addAll(orEmpty(offering.policies()));
        }
        return policies;
    }

    /** The PII categories the purposes name, whichever way each document declared them. */
    @Nullable
    private List<String> categoriesOfData(ContractResponse contract) {
        List<String> categories = new ArrayList<>();
        for (Purpose purpose : orEmpty(contract.purpose())) {
            for (String category : purpose.piiCategories()) {
                String named = text(category);
                if (named != null) {
                    categories.add(named);
                }
            }
        }
        return distinct(categories);
    }

    /**
     * The catalog documents the notice covers, by URI and nothing else.
     *
     * <p>Service offerings, because a contract names offerings and never the data resources behind
     * them; resolving those is a retrieval-time walk of the catalog.
     */
    @Nullable
    private List<DataReference> data(ContractResponse contract) {
        List<String> uris = new ArrayList<>();
        if (contract.shape() == Shape.BILATERAL) {
            String offering = text(contract.serviceOffering());
            if (offering != null) {
                uris.add(offering);
            }
        } else {
            for (ContractResponse.ServiceOffering offering : orEmpty(contract.serviceOfferings())) {
                String uri = text(offering.serviceOffering());
                if (uri != null) {
                    uris.add(uri);
                }
            }
        }
        List<String> distinct = distinct(uris);
        if (distinct == null) {
            return null;
        }
        return distinct.stream().map(uri -> new DataReference(uri, null, null)).toList();
    }

    /**
     * The participants the data reaches under this contract.
     *
     * <p>A revoked member is excluded wherever it appears: it is no longer party to the contract,
     * and naming it as a recipient would overstate who receives the data.
     */
    @Nullable
    private List<String> recipients(ContractResponse contract) {
        if (contract.shape() == Shape.BILATERAL) {
            List<String> parties = new ArrayList<>();
            addIfNamed(parties, contract.dataProvider());
            addIfNamed(parties, contract.dataConsumer());
            return distinct(parties);
        }
        Set<String> revoked = new LinkedHashSet<>();
        for (ContractResponse.Member member : orEmpty(contract.revokedMembers())) {
            addIfNamed(revoked, member.participant());
        }
        List<String> members = new ArrayList<>();
        for (ContractResponse.Member member : orEmpty(contract.members())) {
            String participant = text(member.participant());
            if (participant != null && !revoked.contains(participant)) {
                members.add(participant);
            }
        }
        return distinct(members);
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
     */
    @Nullable
    private String retentionPeriod(ContractResponse contract) {
        if (contract.shape() == Shape.BILATERAL) {
            return contract.terminationAndValidity() == null
                    ? null
                    : text(contract.terminationAndValidity().terminationPeriod());
        }
        List<Purpose> purposes = orEmpty(contract.purpose());
        if (purposes.isEmpty()) {
            return null;
        }
        String agreed = null;
        for (Purpose purpose : purposes) {
            String termination = text(purpose.termination());
            if (termination == null) {
                return null;
            }
            if (agreed == null) {
                agreed = termination;
            } else if (!agreed.equals(termination)) {
                return null;
            }
        }
        return agreed;
    }

    /**
     * What the PII principal may do about the data, in the contract's own words.
     *
     * <p>Only a bilateral contract carries a candidate, its terms and conditions, and that one
     * block of text fills this heading alone - printing it again under withdrawal of consent would
     * dress one paragraph up as two sections.
     */
    @Nullable
    private String piiPrincipalRights(ContractResponse contract) {
        return contract.shape() == Shape.BILATERAL ? text(contract.termsAndConditions()) : null;
    }

    /** The third parties a purpose discloses to, named only where disclosure is declared. */
    @Nullable
    private List<String> internationalTransfers(ContractResponse contract) {
        List<String> parties = new ArrayList<>();
        for (Purpose purpose : orEmpty(contract.purpose())) {
            if (Boolean.TRUE.equals(purpose.thirdPartyDisclosure())) {
                addIfNamed(parties, purpose.thirdPartyName());
            }
        }
        return distinct(parties);
    }

    /** Adds the value under its trimmed form when it states anything. */
    private void addIfNamed(Collection<String> target, @Nullable String value) {
        String named = text(value);
        if (named != null) {
            target.add(named);
        }
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
        if (values.isEmpty()) {
            return null;
        }
        return List.copyOf(new LinkedHashSet<>(values));
    }

    /** The list, or an empty one when the document omitted it. */
    private <T> List<T> orEmpty(@Nullable List<T> values) {
        return values == null ? List.of() : values;
    }
}
