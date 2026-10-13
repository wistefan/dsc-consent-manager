package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.AbstractConsentLifecycleController;
import com.seamware.consentmanager.api.generated.model.Consent;
import com.seamware.consentmanager.security.ParticipantPrincipal;
import com.seamware.consentmanager.security.UserPrincipal;
import com.seamware.consentmanager.service.ConsentLifecycleService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import java.util.UUID;

/**
 * Serves the {@code /consents/{id}/…} operations that move a consent after it exists.
 *
 * <p>Every one of them is authorised against the consent itself rather than against the token's
 * role: the subject comes from {@link UserPrincipal#user()}, and a consent naming anyone else is a
 * {@code 404}. Terminating acts in the dataspace in the participant's name, so it resolves the
 * caller through {@link ParticipantPrincipal#requireActive()} and a departed participant reaches it
 * no more than an unregistered one does.
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

    @Override
    public HttpResponse<Consent> terminateConsent(ParticipantPrincipal principal, UUID id) {
        return HttpResponse.ok(
                mapper.toRepresentation(lifecycle.terminate(principal.requireActive(), id)));
    }
}
