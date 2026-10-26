package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.AbstractContractDiscoveryController;
import com.seamware.consentmanager.api.generated.model.AvailableContract;
import com.seamware.consentmanager.client.dto.ContractResponse;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.security.ParticipantPrincipal;
import com.seamware.consentmanager.security.UserPrincipal;
import com.seamware.consentmanager.service.ContractDiscoveryService;
import com.seamware.consentmanager.service.ContractSide;
import com.seamware.consentmanager.service.DiscoveredContract;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import java.util.List;

/**
 * Serves contract discovery for a data subject and, per side, for a participant. The participant is
 * resolved before {@code role} is read, so an unregistered token gets {@code 403} regardless.
 */
@Controller
public class ContractDiscoveryController extends AbstractContractDiscoveryController {

    private final ContractDiscoveryService discovery;

    public ContractDiscoveryController(ContractDiscoveryService discovery) {
        this.discovery = discovery;
    }

    @Override
    public HttpResponse<List<AvailableContract>> listMyAvailableContracts(UserPrincipal principal) {
        return HttpResponse.ok(toModels(discovery.forUser(principal.user())));
    }

    @Override
    public HttpResponse<List<AvailableContract>> listAvailableContracts(
            ParticipantPrincipal principal, String role) {
        Participant participant = principal.requireRegistered();
        return HttpResponse.ok(
                toModels(discovery.forParticipant(participant, ContractSide.fromValue(role))));
    }

    private static List<AvailableContract> toModels(List<DiscoveredContract> contracts) {
        return contracts.stream().map(ContractDiscoveryController::toModel).toList();
    }

    /** Maps a discovered contract; the parties a contract's shape does not name stay absent. */
    static AvailableContract toModel(DiscoveredContract discovered) {
        ContractResponse contract = discovered.contract();
        // Fluent setters: ConsentStatusInvariantTest bans the bean status setter here by text.
        AvailableContract model =
                new AvailableContract(
                                discovered.contractUri(),
                                AvailableContract.ShapeEnum.valueOf(contract.shape().name()),
                                discovered.sides().stream()
                                        .map(
                                                side ->
                                                        AvailableContract.RolesEnum.valueOf(
                                                                side.name()))
                                        .toList())
                        .contractId(contract.contractId())
                        .status(contract.status());
        return contract.shape() == ContractResponse.Shape.BILATERAL
                ? model.dataProvider(contract.dataProviderIdentifier())
                        .dataConsumer(contract.dataConsumerIdentifier())
                : model.ecosystem(contract.ecosystem());
    }
}
