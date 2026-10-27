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
 * Serves {@code /privacy-notices}. Generation answers {@code 201} when it stored a notice and
 * {@code 200} when an identical active one existed; only retrieval resolves the catalog.
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
     * Serves a stored notice, resolved by default. Deliberately not scoped to the notice's parties:
     * a user must read a notice before any link to it exists.
     */
    @Override
    public HttpResponse<PrivacyNotice> getPrivacyNotice(
            ConsentManagerPrincipal principal, UUID id, Boolean resolve) {
        // The spec defaults resolve to true.
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
