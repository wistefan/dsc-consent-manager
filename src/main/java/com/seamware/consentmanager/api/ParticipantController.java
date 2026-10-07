package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.AbstractParticipantsController;
import com.seamware.consentmanager.api.generated.model.Participant;
import com.seamware.consentmanager.api.generated.model.ParticipantRegistration;
import com.seamware.consentmanager.security.ParticipantPrincipal;
import com.seamware.consentmanager.service.ParticipantService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;

/**
 * Serves the {@code /participants} operations declared in {@code api/openapi.yaml}.
 *
 * <p>This is the one route a {@link ParticipantPrincipal} whose row is absent may reach, which is
 * what makes self-registration possible at all; every other participant-scoped handler takes its
 * row through {@link ParticipantPrincipal#requireRegistered()}.
 *
 * <p>{@code Participant} and {@code ParticipantRegistration} here are the generated API models; the
 * stored row and the service's record of the same name are written out in full to keep them apart.
 */
@Controller
public class ParticipantController extends AbstractParticipantsController {

    private final ParticipantService participants;

    private final ParticipantMapper mapper;

    public ParticipantController(ParticipantService participants, ParticipantMapper mapper) {
        this.participants = participants;
        this.mapper = mapper;
    }

    /**
     * Registers the organization the token names, under the identifier the token carries.
     *
     * <p>Always {@code 201} or a refusal: an identifier already registered is a {@code 409} rather
     * than an idempotent {@code 200}, because the body is a full self-description that would
     * otherwise be silently either discarded or applied.
     */
    @Override
    public HttpResponse<Participant> registerParticipant(
            ParticipantPrincipal principal, ParticipantRegistration body) {
        return HttpResponse.created(
                mapper.toRepresentation(
                        participants.register(principal.identifier(), toRegistration(body))));
    }

    private com.seamware.consentmanager.service.ParticipantRegistration toRegistration(
            ParticipantRegistration body) {
        return new com.seamware.consentmanager.service.ParticipantRegistration(
                body.getLegalName(),
                body.getSelfDescriptionUri(),
                body.getEmail(),
                mapper.toEndpointsColumn(body.getEndpoints()),
                mapper.toLegalPersonColumn(body.getLegalPerson()));
    }
}
