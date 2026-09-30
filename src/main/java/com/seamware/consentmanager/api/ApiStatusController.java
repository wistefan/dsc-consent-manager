package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.AbstractStatusController;
import com.seamware.consentmanager.api.generated.model.ApiStatus;
import io.micronaut.http.annotation.Controller;

/**
 * Concrete controller implementing the {@code getApiStatus} operation declared in {@code
 * api/openapi.yaml}.
 *
 * <p>The OpenAPI generator is configured with {@code generateControllerAsAbstract=true}, so it
 * emits only {@link AbstractStatusController}, which carries the routing and OpenAPI annotations
 * but is not itself a bean. A hand-written concrete subclass annotated with {@link Controller} is
 * therefore required for the operation to be routed at all; without it the endpoint returns 404.
 *
 * <p>{@code /api-status} is a public reachability probe. The specification declares it {@code
 * security: []}, which the generator translates into {@code @Secured(SecurityRule.IS_ANONYMOUS)} on
 * the inherited API method. It exposes no consent data and performs no database access, so it stays
 * answerable even when downstream dependencies are unavailable.
 */
@Controller
public class ApiStatusController extends AbstractStatusController {

    /** Status value reported while the API is reachable. */
    static final String STATUS_OK = "ok";

    /**
     * Returns the API reachability indicator.
     *
     * @return an {@link ApiStatus} whose {@code status} field is {@value #STATUS_OK}
     */
    @Override
    public ApiStatus getApiStatus() {
        return new ApiStatus(STATUS_OK);
    }
}
