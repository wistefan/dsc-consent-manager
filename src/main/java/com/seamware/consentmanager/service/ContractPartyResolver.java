package com.seamware.consentmanager.service;

import com.seamware.consentmanager.client.dto.ContractResponse;
import com.seamware.consentmanager.client.dto.ContractResponse.Member;
import com.seamware.consentmanager.client.dto.ContractResponse.ServiceOffering;
import com.seamware.consentmanager.error.BadRequestException;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Says who a contract's parties are: it reads the two off a contract that names them unambiguously,
 * and judges a pair a caller declared against the same evidence.
 *
 * <p>One definition of a party for both generation operations. Were the derived form to read the
 * document differently from the declared form's binding check, a contract could yield two parties
 * that the very next step refuses - so the sides are derived here from exactly the evidence {@link
 * #requireBound} tests against, and both operations share this bean rather than a convention.
 *
 * <p>Pure: it decides on the fetched document alone and never consults the participant directory,
 * which is what lets a refusal here be about the contract rather than about this deployment.
 */
@Singleton
public class ContractPartyResolver {

    /**
     * Role an ecosystem contract records for the member providing the data, lower-cased.
     *
     * <p>The two role names the Contract Service writes. A role outside this vocabulary is read as
     * no role at all rather than as a contradiction, so an upstream wording change degrades to the
     * service-offering evidence instead of refusing a well-formed request.
     */
    private static final String DATA_PROVIDER_ROLE = "data provider";

    /** Role an ecosystem contract records for the member consuming the data, lower-cased. */
    private static final String DATA_CONSUMER_ROLE = "data consumer";

    /** Runs of whitespace a recorded role is collapsed on before it is compared. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** How many parties a side must carry for the contract to name that side by itself. */
    private static final int UNAMBIGUOUS = 1;

    /**
     * The two parties a contract names by itself; {@code 400} when it names no single pair.
     *
     * <p>A bilateral contract states both outright. An ecosystem contract names N members and no
     * two of them inherently, so each side is read off the evidence the document carries - the role
     * it records for a still-bound member first, and failing that who offers a service under it -
     * and a side that resolves to anything other than one party is refused rather than guessed at.
     */
    @NonNull
    public ContractParties resolve(@NonNull ContractResponse contract) {
        return switch (contract.shape()) {
            case BILATERAL -> bilateralParties(contract);
            case ECOSYSTEM -> ecosystemParties(contract);
        };
    }

    /**
     * Refuses a declared pair the fetched contract does not support.
     *
     * <p>Two conditions, reported apart because they fail for different reasons. The contract must
     * name both participants as parties to it - without that a request body could name any
     * registered participant as consumer and the published notice would misname a party, and a
     * party that differs from the stored notice's could no longer be read as the contract itself
     * having changed. And the contract must not contradict the direction declared, because a notice
     * generated the wrong way round names the data's recipient as its controller.
     */
    public void requireBound(
            @NonNull ContractResponse contract,
            @NonNull String provider,
            @NonNull String consumer) {
        switch (contract.shape()) {
            case BILATERAL -> {
                requireNamed(
                        provider.equals(contract.dataProviderIdentifier()),
                        provider,
                        "data provider");
                requireNamed(
                        consumer.equals(contract.dataConsumerIdentifier()),
                        consumer,
                        "data consumer");
            }
            case ECOSYSTEM -> requireEcosystemParties(contract, provider, consumer);
        }
    }

    /**
     * The two parties a bilateral contract states; {@code 400} when it states either as nothing.
     */
    private static ContractParties bilateralParties(ContractResponse contract) {
        String provider = contract.dataProviderIdentifier();
        String consumer = contract.dataConsumerIdentifier();
        requireStated(provider, "data provider");
        requireStated(consumer, "data consumer");
        return new ContractParties(provider, consumer);
    }

    /**
     * The two parties an ecosystem contract evidences, one per side or {@code 400}.
     *
     * <p>Role evidence outranks offering evidence on each side independently: a contract that
     * records roles says who is where, and one that does not still shows who offers the service.
     * Where neither side's roles are recorded the offering participants are the providing side and
     * every other still-bound member is the consuming side - the same reading {@link
     * #requireEcosystemParties} applies to a declared pair, so a pair derived here always passes
     * it.
     */
    private static ContractParties ecosystemParties(ContractResponse contract) {
        Set<String> active = activeMembers(contract);
        Set<String> offering = offeringParticipants(contract);

        Set<String> recordedProviders = membersInRole(contract, DATA_PROVIDER_ROLE);
        Set<String> providers =
                recordedProviders.isEmpty() ? intersection(offering, active) : recordedProviders;

        Set<String> recordedConsumers = membersInRole(contract, DATA_CONSUMER_ROLE);
        Set<String> consumers =
                recordedConsumers.isEmpty()
                        ? difference(difference(active, providers), offering)
                        : recordedConsumers;

        requireSingle(providers, "data provider", consumers);
        requireSingle(consumers, "data consumer", providers);
        return new ContractParties(only(providers), only(consumers));
    }

    /**
     * Refuses a declared pair an ecosystem contract does not support, membership first and
     * direction second.
     *
     * <p>An ecosystem contract names N parties and no two of them inherently, so the direction is
     * read off whatever evidence the document gives: the role it records for a member, when that
     * role is one of the two {@link #DATA_PROVIDER_ROLE this vocabulary} knows, and otherwise who
     * offers a service under the contract. A member evidenced on one side may not be declared on
     * the other. A contract that evidences neither side - members carrying no role, and no service
     * offerings, which the Contract Service does serve - constrains membership only, and the
     * request's own declaration stands.
     */
    private static void requireEcosystemParties(
            ContractResponse contract, String provider, String consumer) {
        Set<String> members = activeMembers(contract);
        requireNamedParty(members.contains(provider), provider);
        requireNamedParty(members.contains(consumer), consumer);

        Set<String> recordedProviders = membersInRole(contract, DATA_PROVIDER_ROLE);
        Set<String> recordedConsumers = membersInRole(contract, DATA_CONSUMER_ROLE);
        if (recordedConsumers.contains(provider) && !recordedProviders.contains(provider)) {
            throw contradicted(provider, "data consumer", "data provider");
        }
        if (recordedProviders.contains(consumer) && !recordedConsumers.contains(consumer)) {
            throw contradicted(consumer, "data provider", "data consumer");
        }
        if (!recordedConsumers.contains(consumer)
                && !recordedProviders.contains(consumer)
                && offeringParticipants(contract).contains(consumer)) {
            throw new BadRequestException(
                    "The contract names '"
                            + consumer
                            + "' as offering a service under it and records no role saying"
                            + " otherwise, so it cannot be this notice's data consumer.");
        }
    }

    /** Refuses a bilateral contract that leaves one of its two parties unstated. */
    private static void requireStated(@Nullable String identifier, String role) {
        if (identifier == null) {
            throw new BadRequestException(
                    "The contract names no "
                            + role
                            + ", so its parties cannot be read off it. Declare both parties with"
                            + " POST /privacy-notices instead.");
        }
    }

    /**
     * Refuses a side the contract does not narrow to one party.
     *
     * <p>Both counts are reported, because which side is ambiguous is rarely the whole story: a
     * contract with two consumers against one provider and one with no evidence at all are read
     * very differently by whoever has to declare the pair by hand. The participants themselves are
     * <em>not</em> named: this refusal is reached before anything has established that the caller
     * is a party, so naming them would hand a contract's membership to whoever can guess its URI. A
     * party can read the names off the contract it already holds, and a count is all a caller needs
     * in order to declare the pair.
     */
    private static void requireSingle(Set<String> side, String role, Set<String> other) {
        if (side.size() != UNAMBIGUOUS) {
            throw new BadRequestException(
                    "The contract names "
                            + side.size()
                            + " participants on its "
                            + role
                            + " side and "
                            + other.size()
                            + " on the other, so it does not say which two parties this notice is"
                            + " between. Declare both parties with POST /privacy-notices instead.");
        }
    }

    private static String only(Set<String> side) {
        return side.iterator().next();
    }

    private static void requireNamed(boolean bound, String identifier, String role) {
        if (!bound) {
            throw new BadRequestException(
                    "The contract does not bind '" + identifier + "' as its " + role + ".");
        }
    }

    private static void requireNamedParty(boolean named, String identifier) {
        if (!named) {
            throw new BadRequestException(
                    "The contract does not name '" + identifier + "' as a party to it.");
        }
    }

    /** Says which side the contract puts a participant on, and which side the request claimed. */
    private static BadRequestException contradicted(
            String identifier, String recorded, String declared) {
        return new BadRequestException(
                "The contract records '"
                        + identifier
                        + "' as its "
                        + recorded
                        + ", so it cannot be this notice's "
                        + declared
                        + ".");
    }

    /** Participants an ecosystem contract still binds: its members, less the revoked ones. */
    private static Set<String> activeMembers(ContractResponse contract) {
        return difference(
                participantsOf(contract.members()), participantsOf(contract.revokedMembers()));
    }

    /** The still-bound members an ecosystem contract records in the given role. */
    private static Set<String> membersInRole(ContractResponse contract, String role) {
        Set<String> active = activeMembers(contract);
        return members(contract.members())
                .filter(member -> role.equals(recordedRole(member.role())))
                .map(Member::identifier)
                .filter(active::contains)
                .collect(Collectors.toSet());
    }

    /** A recorded role as it is compared: trimmed, lower-cased, inner whitespace collapsed. */
    @Nullable
    private static String recordedRole(@Nullable String role) {
        if (role == null) {
            return null;
        }
        String recorded = WHITESPACE.matcher(role.trim()).replaceAll(" ").toLowerCase(Locale.ROOT);
        return recorded.isEmpty() ? null : recorded;
    }

    private static Set<String> participantsOf(@Nullable List<Member> members) {
        return members(members)
                .map(Member::identifier)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static Stream<Member> members(@Nullable List<Member> members) {
        return members == null ? Stream.of() : members.stream();
    }

    /**
     * Participants an ecosystem contract names as offering a service, which is the provider side
     * whenever the document records no role to read it off instead.
     */
    private static Set<String> offeringParticipants(ContractResponse contract) {
        List<ServiceOffering> offerings = contract.serviceOfferings();
        return offerings == null
                ? Set.of()
                : offerings.stream()
                        .map(ServiceOffering::participantIdentifier)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        return left.stream().filter(right::contains).collect(Collectors.toSet());
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        return left.stream()
                .filter(element -> !right.contains(element))
                .collect(Collectors.toSet());
    }
}
