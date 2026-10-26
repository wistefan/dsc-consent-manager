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
 * The one definition of a contract's parties: derives them, and judges a declared pair by the same
 * evidence {@link #requireBound} uses. Pure; never consults the participant directory.
 */
@Singleton
public class ContractPartyResolver {

    /**
     * Role an ecosystem contract records for the member providing the data, lower-cased. A role
     * outside this vocabulary reads as no role, falling back to service-offering evidence.
     */
    private static final String DATA_PROVIDER_ROLE = "data provider";

    /** Role an ecosystem contract records for the member consuming the data, lower-cased. */
    private static final String DATA_CONSUMER_ROLE = "data consumer";

    /** Runs of whitespace a recorded role is collapsed on before it is compared. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** How many parties a side must carry for the contract to name that side by itself. */
    private static final int UNAMBIGUOUS = 1;

    /**
     * The two parties a contract names by itself; {@code 400} when it names no single pair. An
     * ecosystem side is read off recorded roles first, then service offerings.
     */
    @NonNull
    public ContractParties resolve(@NonNull ContractResponse contract) {
        return switch (contract.shape()) {
            case BILATERAL -> bilateralParties(contract);
            case ECOSYSTEM -> ecosystemParties(contract);
        };
    }

    /** Whether the contract puts the participant on the side, as {@link #requireBound} reads it. */
    public boolean binds(
            @NonNull ContractResponse contract,
            @NonNull String participant,
            @NonNull ContractSide side) {
        return switch (contract.shape()) {
            case BILATERAL ->
                    participant.equals(
                            switch (side) {
                                case PROVIDER -> contract.dataProviderIdentifier();
                                case CONSUMER -> contract.dataConsumerIdentifier();
                            });
            case ECOSYSTEM ->
                    activeMembers(contract).contains(participant)
                            && !recordedOnOtherSide(contract, participant, side)
                            && !(side == ContractSide.CONSUMER
                                    && offersWithoutRole(contract, participant));
        };
    }

    /**
     * Refuses ({@code 400}) a declared pair the contract does not name as parties, or whose
     * declared direction it contradicts.
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

    /** The two parties a bilateral contract states; {@code 400} when either is absent. */
    private static ContractParties bilateralParties(ContractResponse contract) {
        String provider = contract.dataProviderIdentifier();
        String consumer = contract.dataConsumerIdentifier();
        requireStated(provider, "data provider");
        requireStated(consumer, "data consumer");
        return new ContractParties(provider, consumer);
    }

    /**
     * The two parties an ecosystem contract evidences, one per side or {@code 400}. Roles outrank
     * offerings per side, the reading {@link #requireEcosystemParties} applies.
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
     * Refuses a declared pair an ecosystem contract does not support, membership first, then
     * direction by role or offering; with neither evidenced, the declaration stands.
     */
    private static void requireEcosystemParties(
            ContractResponse contract, String provider, String consumer) {
        Set<String> members = activeMembers(contract);
        requireNamedParty(members.contains(provider), provider);
        requireNamedParty(members.contains(consumer), consumer);

        if (recordedOnOtherSide(contract, provider, ContractSide.PROVIDER)) {
            throw contradicted(provider, "data consumer", "data provider");
        }
        if (recordedOnOtherSide(contract, consumer, ContractSide.CONSUMER)) {
            throw contradicted(consumer, "data provider", "data consumer");
        }
        if (offersWithoutRole(contract, consumer)) {
            throw new BadRequestException(
                    "The contract names '"
                            + consumer
                            + "' as offering a service under it and records no role saying"
                            + " otherwise, so it cannot be this notice's data consumer.");
        }
    }

    /** Whether the contract records the participant in the role opposite {@code side} only. */
    private static boolean recordedOnOtherSide(
            ContractResponse contract, String participant, ContractSide side) {
        Set<String> providers = membersInRole(contract, DATA_PROVIDER_ROLE);
        Set<String> consumers = membersInRole(contract, DATA_CONSUMER_ROLE);
        return switch (side) {
            case PROVIDER -> consumers.contains(participant) && !providers.contains(participant);
            case CONSUMER -> providers.contains(participant) && !consumers.contains(participant);
        };
    }

    /** Whether the participant offers a service and records no role, which reads it as provider. */
    private static boolean offersWithoutRole(ContractResponse contract, String participant) {
        return !membersInRole(contract, DATA_PROVIDER_ROLE).contains(participant)
                && !membersInRole(contract, DATA_CONSUMER_ROLE).contains(participant)
                && offeringParticipants(contract).contains(participant);
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
     * Refuses a side the contract does not narrow to one party, reporting counts only: the caller
     * is not yet known to be a party, so naming members would leak a contract's membership.
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

    /** Participants offering a service under the contract; the provider side absent any role. */
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
