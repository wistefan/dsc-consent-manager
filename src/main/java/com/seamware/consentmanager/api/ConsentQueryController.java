package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.AbstractConsentQueryController;
import com.seamware.consentmanager.api.generated.model.ConsentPage;
import com.seamware.consentmanager.api.generated.model.ConsentRepresentation;
import com.seamware.consentmanager.api.generated.model.ConsentValidity;
import com.seamware.consentmanager.security.ParticipantPrincipal;
import com.seamware.consentmanager.security.UserPrincipal;
import com.seamware.consentmanager.service.ConsentDetails;
import com.seamware.consentmanager.service.ConsentQueryCriteria;
import com.seamware.consentmanager.service.ConsentQueryService;
import com.seamware.consentmanager.service.ConsentReceiptService;
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
 * <p>The validity check alone resolves the caller through {@link
 * ParticipantPrincipal#requireActive()}: its answer licenses processing, which is acting in the
 * dataspace, so a deregistered participant whose grants were retained is refused rather than told
 * {@code valid: true}.
 *
 * <p>{@code format} selects the representation of a single-consent read through {@link
 * ConsentFormat}; a listing renders {@code full} alone and refuses any other value with a {@code
 * 400}. {@code includeLineage} is accepted and ignored until lineage traversal lands.
 */
@Controller
public class ConsentQueryController extends AbstractConsentQueryController {

    /** Keeps a yes/no answer out of every cache; a stale "yes" is the worst possible answer. */
    private static final String NO_STORE = "no-store";

    private final ConsentQueryService queries;

    private final ConsentMapper mapper;

    private final ConsentReceiptService receipts;

    public ConsentQueryController(
            ConsentQueryService queries, ConsentMapper mapper, ConsentReceiptService receipts) {
        this.queries = queries;
        this.mapper = mapper;
        this.receipts = receipts;
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
        ConsentFormat.requireListable(format);
        ConsentQueryCriteria criteria =
                new ConsentQueryCriteria(mapper.toStatuses(status), privacyNoticeId, page, size);
        return HttpResponse.ok(toPage(queries.listForUser(principal.user(), criteria)));
    }

    @Override
    public HttpResponse<ConsentRepresentation> getMyConsent(
            UserPrincipal principal, UUID id, String format, Boolean includeLineage) {
        ConsentFormat selected = ConsentFormat.of(format);
        return HttpResponse.ok(render(queries.getForUser(principal.user(), id), selected));
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
    public HttpResponse<ConsentRepresentation> getConsent(
            ParticipantPrincipal principal, UUID id, String format) {
        ConsentFormat selected = ConsentFormat.of(format);
        return HttpResponse.ok(
                render(queries.getForParticipant(principal.requireRegistered(), id), selected));
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
                                principal.requireActive(),
                                userIdentifier,
                                privacyNoticeId,
                                purpose));
        return HttpResponse.ok(body).header(HttpHeaders.CACHE_CONTROL, NO_STORE);
    }

    /**
     * {@code details} in {@code format}; every receipt is read off the consent's frozen snapshot.
     */
    private ConsentRepresentation render(ConsentDetails details, ConsentFormat format) {
        return switch (format) {
            case FULL -> mapper.toResponse(details);
            case RECEIPT -> receipts.toIso29184(details);
            case RECEIPT_KANTARA -> receipts.toKantara(details);
        };
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
