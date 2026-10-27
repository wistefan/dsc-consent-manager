package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.AbstractParticipantsController;
import com.seamware.consentmanager.api.generated.model.DeregistrationSummary;
import com.seamware.consentmanager.api.generated.model.Participant;
import com.seamware.consentmanager.api.generated.model.ParticipantPage;
import com.seamware.consentmanager.api.generated.model.ParticipantRegistration;
import com.seamware.consentmanager.api.generated.model.ParticipantUpdate;
import com.seamware.consentmanager.security.ConsentManagerPrincipal;
import com.seamware.consentmanager.security.ParticipantPrincipal;
import com.seamware.consentmanager.service.ConsentReporter;
import com.seamware.consentmanager.service.DeregistrationResult;
import com.seamware.consentmanager.service.ParticipantService;
import io.micronaut.data.model.Page;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;

/**
 * Serves {@code /participants}. Registration is the one route an unregistered {@link
 * ParticipantPrincipal} may reach; the rest use {@link ParticipantPrincipal#requireRegistered()}.
 */
@Controller
public class ParticipantController extends AbstractParticipantsController {

    private final ParticipantService participants;

    private final ParticipantMapper mapper;

    private final ConsentReporter reporter;

    public ParticipantController(
            ParticipantService participants, ParticipantMapper mapper, ConsentReporter reporter) {
        this.participants = participants;
        this.reporter = reporter;
        this.mapper = mapper;
    }

    /**
     * Registers the token's organization under its identifier: {@code 201}, or {@code 409} when
     * already registered, so a full body is never silently discarded or applied.
     */
    @Override
    public HttpResponse<Participant> registerParticipant(
            ParticipantPrincipal principal, ParticipantRegistration body) {
        return HttpResponse.created(
                mapper.toRepresentation(
                        participants.register(principal.identifier(), toRegistration(body))));
    }

    /** Reads back the caller's own registration; the token decides which record that is. */
    @Override
    public HttpResponse<Participant> getCurrentParticipant(ParticipantPrincipal principal) {
        return HttpResponse.ok(mapper.toRepresentation(principal.requireRegistered()));
    }

    /**
     * Replaces the caller's own self-description in full; the row comes from the principal, so the
     * body can never name another identifier.
     */
    @Override
    public HttpResponse<Participant> updateCurrentParticipant(
            ParticipantPrincipal principal, ParticipantUpdate body) {
        return HttpResponse.ok(
                mapper.toRepresentation(
                        participants.update(principal.requireRegistered(), toUpdate(body))));
    }

    /**
     * Deregisters the caller's organization, answering a summary of what the cascade closed; moves
     * are reported only after commit. See ADR-0008.
     */
    @Override
    public HttpResponse<DeregistrationSummary> deregisterCurrentParticipant(
            ParticipantPrincipal principal) {
        DeregistrationResult result = participants.deregister(principal.requireRegistered());
        reporter.report(result.moves());
        return HttpResponse.ok(
                new DeregistrationSummary(
                                result.consentsTerminated(),
                                result.consentsRetained(),
                                result.noticesArchived(),
                                result.linksRemoved())
                        .deregisteredAt(ParticipantMapper.atUtc(result.deregisteredAt())));
    }

    /** One page of the directory; readable by every role, unregistered participants included. */
    @Override
    public HttpResponse<ParticipantPage> listParticipants(
            ConsentManagerPrincipal principal, Integer page, Integer size, String identifier) {
        return HttpResponse.ok(toPage(participants.list(page, size, identifier)));
    }

    /** Resolves one participant by its global identifier, deregistered ones included. */
    @Override
    public HttpResponse<Participant> getParticipantByIdentifier(
            ConsentManagerPrincipal principal, String identifier) {
        return HttpResponse.ok(mapper.toRepresentation(participants.find(identifier)));
    }

    private ParticipantPage toPage(Page<com.seamware.consentmanager.domain.Participant> page) {
        return new ParticipantPage(
                page.getContent().stream().map(mapper::toRepresentation).toList(),
                page.getPageNumber(),
                page.getSize(),
                page.getTotalSize(),
                page.getTotalPages());
    }

    private com.seamware.consentmanager.service.ParticipantUpdate toUpdate(ParticipantUpdate body) {
        return new com.seamware.consentmanager.service.ParticipantUpdate(
                body.getLegalName(),
                body.getSelfDescriptionUri(),
                body.getEmail(),
                mapper.toEndpointsColumn(body.getEndpoints()),
                mapper.toLegalPersonColumn(body.getLegalPerson()));
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
