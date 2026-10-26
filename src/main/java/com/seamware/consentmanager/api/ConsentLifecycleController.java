package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.AbstractConsentLifecycleController;
import com.seamware.consentmanager.api.generated.model.Consent;
import com.seamware.consentmanager.api.generated.model.ResumeConsent;
import com.seamware.consentmanager.security.ConsentManagerPrincipal;
import com.seamware.consentmanager.security.ParticipantPrincipal;
import com.seamware.consentmanager.security.UserPrincipal;
import com.seamware.consentmanager.service.ConsentLifecycleService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import java.util.UUID;

/**
 * Serves the {@code /consents/{id}/…} moves, each authorised against the consent itself: one naming
 * another subject is a {@code 404}. Terminate requires {@link
 * ParticipantPrincipal#requireActive()}.
 */
@Controller
public class ConsentLifecycleController extends AbstractConsentLifecycleController {

    private final ConsentLifecycleService lifecycle;

    private final ConsentMapper mapper;

    public ConsentLifecycleController(ConsentLifecycleService lifecycle, ConsentMapper mapper) {
        this.lifecycle = lifecycle;
        this.mapper = mapper;
    }

    @Override
    public HttpResponse<Consent> revokeConsent(UserPrincipal principal, UUID id) {
        return HttpResponse.ok(mapper.toRepresentation(lifecycle.revoke(principal.user(), id)));
    }

    @Override
    public HttpResponse<Consent> refuseConsent(UserPrincipal principal, UUID id) {
        return HttpResponse.ok(mapper.toRepresentation(lifecycle.refuse(principal.user(), id)));
    }

    @Override
    public HttpResponse<Consent> reConfirmConsent(UserPrincipal principal, UUID id) {
        return HttpResponse.ok(mapper.toRepresentation(lifecycle.reConfirm(principal.user(), id)));
    }

    /**
     * Grants an unanswered request for the subject or a relaying catalog. A missing body equals one
     * naming no subject: fine for a {@code USER}, a {@code 400} for a {@code CATALOG}.
     */
    @Override
    public HttpResponse<Consent> resumeConsent(
            ConsentManagerPrincipal principal, UUID id, ResumeConsent request) {
        String userIdentifier = request == null ? null : request.getUserIdentifier();
        return HttpResponse.ok(
                mapper.toRepresentation(lifecycle.resume(principal, id, userIdentifier)));
    }

    @Override
    public HttpResponse<Consent> terminateConsent(ParticipantPrincipal principal, UUID id) {
        return HttpResponse.ok(
                mapper.toRepresentation(lifecycle.terminate(principal.requireActive(), id)));
    }
}
