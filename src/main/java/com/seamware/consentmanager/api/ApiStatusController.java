package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.AbstractStatusController;
import com.seamware.consentmanager.api.generated.model.ApiStatus;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;

/**
 * Serves the public {@code /api-status} reachability probe; touches no database, so it answers
 * while dependencies are down. Its access rule lives in the spec, not on this class.
 */
@Controller
public class ApiStatusController extends AbstractStatusController {

    /** Status value reported while the API is reachable. */
    private static final String STATUS_OK = "ok";

    /** Always {@value #STATUS_OK} with a {@code 200}. */
    @Override
    public HttpResponse<ApiStatus> getApiStatus() {
        return HttpResponse.ok(new ApiStatus(STATUS_OK));
    }
}
