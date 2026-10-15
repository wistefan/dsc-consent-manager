package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.AbstractConsentQueryController;
import com.seamware.consentmanager.api.generated.model.ConsentPage;
import com.seamware.consentmanager.api.generated.model.ConsentResponse;
import com.seamware.consentmanager.api.generated.model.ConsentValidity;
import com.seamware.consentmanager.security.ParticipantPrincipal;
import com.seamware.consentmanager.security.UserPrincipal;
import com.seamware.consentmanager.service.ConsentDetails;
import com.seamware.consentmanager.service.ConsentQueryCriteria;
import com.seamware.consentmanager.service.ConsentQueryService;
import io.micronaut.data.model.Page;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import java.util.List;
import java.util.UUID;

/**
 * Serves the consent query operations: {@code /consents/me} for a data subject reading their own
 * decisions, and {@code /consents} (+ {@code /consents/id}) for a participant reading the ones it
 * is a party to.
 *
 * <p>The subject is taken from {@link UserPrincipal#user()} and the participant from {@link
 * ParticipantPrincipal#requireRegistered()}, never from the request, so each caller's page carries
 * only consents in its own scope and one addressed by id resolves only within it. An unregistered
 * participant is refused by {@code requireRegistered()} with a {@code 403} before any read.
 *
 * <p>{@code format} and {@code includeLineage} are accepted and ignored here: receipt rendering and
 * lineage traversal land in later steps, and every value renders the full representation meanwhile.
 */
@Controller
public class ConsentQueryController extends AbstractConsentQueryController {

    /** Keeps a yes/no answer out of every cache; a stale "yes" is the worst possible answer. */
    private static final String NO_STORE = "no-store";

    private final ConsentQueryService queries;

    private final ConsentMapper mapper;

    public ConsentQueryController(ConsentQueryService queries, ConsentMapper mapper) {
        this.queries = queries;
        this.mapper = mapper;
    }

    @Override
    public HttpResponse<ConsentPage> listMyConsents(
            UserPrincipal principal,
            Integer page,
            Integer size,
            List<String> status,
            UUID privacyNoticeId,
            String format,
            Boolean includeLineage) {
        ConsentQueryCriteria criteria =
                new ConsentQueryCriteria(mapper.toStatuses(status), privacyNoticeId, page, size);
        return HttpResponse.ok(toPage(queries.listForUser(principal.user(), criteria)));
    }

    @Override
    public HttpResponse<ConsentResponse> getMyConsent(
            UserPrincipal principal, UUID id, String format, Boolean includeLineage) {
        return HttpResponse.ok(mapper.toResponse(queries.getForUser(principal.user(), id)));
    }

    @Override
    public HttpResponse<ConsentPage> listConsents(
            ParticipantPrincipal principal,
            String userIdentifier,
            UUID privacyNoticeId,
            List<String> status,
            Integer page,
            Integer size) {
        ConsentQueryCriteria criteria =
                new ConsentQueryCriteria(mapper.toStatuses(status), privacyNoticeId, page, size);
        return HttpResponse.ok(
                toPage(
                        queries.listForParticipant(
                                principal.requireRegistered(), userIdentifier, criteria)));
    }

    @Override
    public HttpResponse<ConsentResponse> getConsent(ParticipantPrincipal principal, UUID id) {
        return HttpResponse.ok(
                mapper.toResponse(queries.getForParticipant(principal.requireRegistered(), id)));
    }

    @Override
    public HttpResponse<ConsentValidity> checkConsentValidity(
            ParticipantPrincipal principal,
            String userIdentifier,
            UUID privacyNoticeId,
            String purpose) {
        ConsentValidity body =
                mapper.toValidity(
                        queries.checkValidity(
                                principal.requireRegistered(),
                                userIdentifier,
                                privacyNoticeId,
                                purpose));
        return HttpResponse.ok(body).header(HttpHeaders.CACHE_CONTROL, NO_STORE);
    }

    private ConsentPage toPage(Page<ConsentDetails> page) {
        return new ConsentPage(
                page.getContent().stream().map(mapper::toResponse).toList(),
                page.getPageNumber(),
                page.getSize(),
                page.getTotalSize(),
                page.getTotalPages());
    }
}
