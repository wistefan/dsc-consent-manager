package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.AbstractConsentsController;
import com.seamware.consentmanager.api.generated.model.Consent;
import com.seamware.consentmanager.api.generated.model.GrantConsent;
import com.seamware.consentmanager.security.UserPrincipal;
import com.seamware.consentmanager.service.ConsentDecision;
import com.seamware.consentmanager.service.ConsentDetails;
import com.seamware.consentmanager.service.ConsentService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;

/**
 * Serves {@code POST /consents}; the subject comes only from {@link UserPrincipal#user()}. Answers
 * {@code 201} for a new consent, {@code 200} when answering a request a participant had opened.
 */
@Controller
public class ConsentController extends AbstractConsentsController {

    private final ConsentService consents;

    private final ConsentMapper mapper;

    public ConsentController(ConsentService consents, ConsentMapper mapper) {
        this.consents = consents;
        this.mapper = mapper;
    }

    @Override
    public HttpResponse<Consent> grantConsent(UserPrincipal principal, GrantConsent request) {
        ConsentDecision decision =
                new ConsentDecision(
                        request.getPrivacyNoticeId(),
                        mapper.toStatus(request.getDecision()),
                        request.getPurposes(),
                        request.getData());
        ConsentDetails recorded = consents.decide(principal.user(), decision);
        Consent body = mapper.toRepresentation(recorded);
        return recorded.created() ? HttpResponse.created(body) : HttpResponse.ok(body);
    }
}
