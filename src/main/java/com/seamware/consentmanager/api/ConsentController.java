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
 * Serves the {@code /consents} operations.
 *
 * <p>The subject is taken from {@link UserPrincipal#user()} and from nowhere else: the body names a
 * notice and a decision, so a user identifier sent alongside is deserialized into nothing and
 * cannot redirect the decision at another person.
 *
 * <p>The status code reports which record the body is: {@code 201} for a consent this decision
 * brought into being, {@code 200} where it answered a request a participant had already opened,
 * whose identifier and parent link were there before this call.
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
