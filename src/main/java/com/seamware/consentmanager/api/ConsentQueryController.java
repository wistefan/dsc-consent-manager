package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.AbstractConsentQueryController;
import com.seamware.consentmanager.api.generated.model.ConsentPage;
import com.seamware.consentmanager.api.generated.model.ConsentResponse;
import com.seamware.consentmanager.security.UserPrincipal;
import com.seamware.consentmanager.service.ConsentDetails;
import com.seamware.consentmanager.service.ConsentQueryCriteria;
import com.seamware.consentmanager.service.ConsentQueryService;
import io.micronaut.data.model.Page;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import java.util.List;
import java.util.UUID;

/**
 * Serves the {@code /consents/me} query operations for a data subject reading their own decisions.
 *
 * <p>The subject is taken from {@link UserPrincipal#user()} and nowhere else, so the page carries
 * only the caller's own consents and one addressed by id resolves only when the caller owns it.
 *
 * <p>{@code format} and {@code includeLineage} are accepted and ignored here: receipt rendering and
 * lineage traversal land in later steps, and every value renders the full representation meanwhile.
 */
@Controller
public class ConsentQueryController extends AbstractConsentQueryController {

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

    private ConsentPage toPage(Page<ConsentDetails> page) {
        return new ConsentPage(
                page.getContent().stream().map(mapper::toResponse).toList(),
                page.getPageNumber(),
                page.getSize(),
                page.getTotalSize(),
                page.getTotalPages());
    }
}
