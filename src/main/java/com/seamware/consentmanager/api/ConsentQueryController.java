package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.AbstractConsentQueryController;
import com.seamware.consentmanager.api.generated.model.ConsentPage;
import com.seamware.consentmanager.api.generated.model.ConsentRepresentation;
import com.seamware.consentmanager.api.generated.model.ConsentResponse;
import com.seamware.consentmanager.api.generated.model.ConsentValidity;
import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.security.ParticipantPrincipal;
import com.seamware.consentmanager.security.UserPrincipal;
import com.seamware.consentmanager.service.ConsentDetails;
import com.seamware.consentmanager.service.ConsentLineageService;
import com.seamware.consentmanager.service.ConsentQueryCriteria;
import com.seamware.consentmanager.service.ConsentQueryService;
import com.seamware.consentmanager.service.ConsentReceiptService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.Page;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Serves consent queries for a subject ({@code /consents/me}) and a participant ({@code
 * /consents}), each scoped by the principal, never by the request. See ADR-0013.
 *
 * <p>The validity check alone requires {@link ParticipantPrincipal#requireActive()}: its answer
 * licenses processing, so a deregistered participant is refused rather than told {@code valid}.
 */
@Controller
public class ConsentQueryController extends AbstractConsentQueryController {

    /** Keeps a yes/no answer out of every cache; a stale "yes" is the worst answer. */
    private static final String NO_STORE = "no-store";

    private final ConsentQueryService queries;

    private final ConsentMapper mapper;

    private final ConsentReceiptService receipts;

    private final ConsentLineageService lineages;

    public ConsentQueryController(
            ConsentQueryService queries,
            ConsentMapper mapper,
            ConsentReceiptService receipts,
            ConsentLineageService lineages) {
        this.queries = queries;
        this.mapper = mapper;
        this.receipts = receipts;
        this.lineages = lineages;
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
        User subject = principal.user();
        return HttpResponse.ok(
                toPage(
                        queries.listForUser(subject, criteria),
                        lineageWalk(
                                includeLineage, consent -> lineages.forUser(subject, consent))));
    }

    @Override
    public HttpResponse<ConsentRepresentation> getMyConsent(
            UserPrincipal principal, UUID id, String format, Boolean includeLineage) {
        ConsentFormat selected = ConsentFormat.of(format, Boolean.TRUE.equals(includeLineage));
        User subject = principal.user();
        return HttpResponse.ok(
                render(
                        queries.getForUser(subject, id),
                        selected,
                        lineageWalk(
                                includeLineage, consent -> lineages.forUser(subject, consent))));
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
                                principal.requireRegistered(), userIdentifier, criteria),
                        null));
    }

    @Override
    public HttpResponse<ConsentRepresentation> getConsent(
            ParticipantPrincipal principal, UUID id, String format, Boolean includeLineage) {
        ConsentFormat selected = ConsentFormat.of(format, Boolean.TRUE.equals(includeLineage));
        Participant caller = principal.requireRegistered();
        return HttpResponse.ok(
                render(
                        queries.getForParticipant(caller, id),
                        selected,
                        lineageWalk(
                                includeLineage,
                                consent -> lineages.forParticipant(caller, consent))));
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

    /** {@code details} in {@code format}; a receipt is read off the frozen snapshot. */
    private ConsentRepresentation render(
            ConsentDetails details, ConsentFormat format, @Nullable LineageWalk lineage) {
        return switch (format) {
            case FULL -> toResponse(details, lineage);
            case RECEIPT -> receipts.toIso29184(details);
            case RECEIPT_KANTARA -> receipts.toKantara(details);
        };
    }

    private ConsentPage toPage(Page<ConsentDetails> page, @Nullable LineageWalk lineage) {
        return new ConsentPage(
                page.getContent().stream().map(details -> toResponse(details, lineage)).toList(),
                page.getPageNumber(),
                page.getSize(),
                page.getTotalSize(),
                page.getTotalPages());
    }

    /** The full representation, carrying its lineage only when a walk was asked for. */
    private ConsentResponse toResponse(ConsentDetails details, @Nullable LineageWalk lineage) {
        return lineage == null
                ? mapper.toResponse(details)
                : mapper.toResponse(details, lineage.apply(details.consent()));
    }

    /** {@code walk} when {@code includeLineage} is set; {@code null} renders no {@code lineage}. */
    @Nullable
    private static LineageWalk lineageWalk(@Nullable Boolean includeLineage, LineageWalk walk) {
        return Boolean.TRUE.equals(includeLineage) ? walk : null;
    }

    /** The caller-scoped chain one consent supersedes, newest first. */
    private interface LineageWalk extends Function<Consent, List<Consent>> {}
}
