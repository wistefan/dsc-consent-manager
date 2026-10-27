package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.AbstractConsentRequestsController;
import com.seamware.consentmanager.api.generated.model.Consent;
import com.seamware.consentmanager.api.generated.model.ConsentRequest;
import com.seamware.consentmanager.security.ParticipantPrincipal;
import com.seamware.consentmanager.service.ConsentDetails;
import com.seamware.consentmanager.service.ConsentRequestCommand;
import com.seamware.consentmanager.service.ConsentService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;

/**
 * Serves {@code POST /consents/requests}, always {@code 201}. The asker is the caller's own {@link
 * ParticipantPrincipal#requireActive()}, so none can ask in another's name.
 */
@Controller
public class ConsentRequestController extends AbstractConsentRequestsController {

    private final ConsentService consents;

    private final ConsentMapper mapper;

    public ConsentRequestController(ConsentService consents, ConsentMapper mapper) {
        this.consents = consents;
        this.mapper = mapper;
    }

    @Override
    public HttpResponse<Consent> requestConsent(
            ParticipantPrincipal principal, ConsentRequest request) {
        ConsentRequestCommand command =
                new ConsentRequestCommand(
                        request.getPrivacyNoticeId(), request.getUserIdentifier());
        ConsentDetails opened = consents.request(principal.requireActive(), command);
        return HttpResponse.created(mapper.toRepresentation(opened));
    }
}
