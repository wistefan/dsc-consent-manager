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
 * Serves {@code POST /consents/requests}.
 *
 * <p>The asking participant is {@link ParticipantPrincipal#requireActive()} and nothing else: the
 * body names a notice and a subject, so no caller can put the question in another's name, and a
 * participant that has left the dataspace cannot open requests while still reading its own record.
 *
 * <p>Always {@code 201} - a request creates the record it returns, and answering it is the
 * subject's call on {@code POST /consents}.
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
