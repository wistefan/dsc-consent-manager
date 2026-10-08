package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.AbstractPrivacyNoticesController;
import com.seamware.consentmanager.api.generated.model.GeneratePrivacyNotice;
import com.seamware.consentmanager.api.generated.model.PrivacyNotice;
import com.seamware.consentmanager.security.ParticipantPrincipal;
import com.seamware.consentmanager.service.GeneratedNotice;
import com.seamware.consentmanager.service.PrivacyNoticeService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;

/**
 * Serves the {@code /privacy-notices} operations.
 *
 * <p>The status code is the whole report of what happened: {@code 201} when this request stored a
 * notice, archiving whatever it superseded, and {@code 200} when an identical active notice was
 * already there and nothing was written. Nothing here dereferences the catalog, so the resolution
 * block is {@link PrivacyNoticeMapper#unresolved()} throughout.
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
        PrivacyNotice body =
                mapper.toApi(
                        generated.notice(),
                        generated.dataProvider(),
                        generated.dataConsumer(),
                        PrivacyNoticeMapper.unresolved());
        return generated.created() ? HttpResponse.created(body) : HttpResponse.ok(body);
    }
}
