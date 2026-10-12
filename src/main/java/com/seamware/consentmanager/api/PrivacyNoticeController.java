package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.AbstractPrivacyNoticesController;
import com.seamware.consentmanager.api.generated.model.GenerateByContract;
import com.seamware.consentmanager.api.generated.model.GeneratePrivacyNotice;
import com.seamware.consentmanager.api.generated.model.PrivacyNotice;
import com.seamware.consentmanager.security.ConsentManagerPrincipal;
import com.seamware.consentmanager.security.ParticipantPrincipal;
import com.seamware.consentmanager.service.GeneratedNotice;
import com.seamware.consentmanager.service.PrivacyNoticeService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import java.util.UUID;

/**
 * Serves the {@code /privacy-notices} operations.
 *
 * <p>The two generating operations are one generation differing only in where the two parties come
 * from, so they render through the same path. The status code is the whole report of what happened:
 * {@code 201} when this request stored a notice, archiving whatever it superseded, and {@code 200}
 * when an identical active notice was already there and nothing was written. Neither dereferences
 * the catalog, so their resolution block is {@link PrivacyNoticeMapper#unresolved()} throughout;
 * retrieval is the one operation that reports anything else.
 *
 * <p>{@code PrivacyNotice} here is the generated API model; the stored row is named in full to keep
 * the two apart.
 */
@Controller
public class PrivacyNoticeController extends AbstractPrivacyNoticesController {

    private final PrivacyNoticeService notices;

    private final PrivacyNoticeMapper mapper;

    public PrivacyNoticeController(PrivacyNoticeService notices, PrivacyNoticeMapper mapper) {
        this.notices = notices;
        this.mapper = mapper;
    }

    @Override
    public HttpResponse<PrivacyNotice> generatePrivacyNotice(
            ParticipantPrincipal principal, GeneratePrivacyNotice request) {
        GeneratedNotice generated =
                notices.generate(
                        principal,
                        request.getContractUri(),
                        request.getDataProvider(),
                        request.getDataConsumer());
        return served(generated);
    }

    @Override
    public HttpResponse<PrivacyNotice> generatePrivacyNoticeByContract(
            ParticipantPrincipal principal, GenerateByContract request) {
        return served(notices.generateByContract(principal, request.getContractUri()));
    }

    /**
     * Serves a stored notice, resolved unless the caller asked otherwise.
     *
     * <p>The principal is authorization and nothing more: retrieval is deliberately not scoped to
     * the notice's parties, because a user has to be able to read the notice they are being asked
     * to consent to before any link to it exists.
     */
    @Override
    public HttpResponse<PrivacyNotice> getPrivacyNotice(
            ConsentManagerPrincipal principal, UUID id, Boolean resolve) {
        // The specification defaults this to true, and a caller omitting it means the same.
        return HttpResponse.ok(mapper.toApi(notices.get(id, resolve == null || resolve)));
    }

    /** Renders a generated notice under the code its outcome calls for. */
    private HttpResponse<PrivacyNotice> served(GeneratedNotice generated) {
        PrivacyNotice body =
                mapper.toApi(
                        generated.notice(),
                        generated.dataProvider(),
                        generated.dataConsumer(),
                        PrivacyNoticeMapper.unresolved());
        return generated.created() ? HttpResponse.created(body) : HttpResponse.ok(body);
    }
}
