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
 * security: []}, and the generator emits {@code @Secured(SecurityRule.IS_ANONYMOUS)} on the
 * inherited API method for every operation it considers to carry no security requirement, which
 * matches that declaration. That annotation on its own is not evidence that the generator tracks
 * the specification's security requirements, because {@code IS_ANONYMOUS} is equally what it emits
 * for a specification with no security section at all. Confirming that a {@code bearerAuth}
 * requirement yields {@code IS_AUTHENTICATED}, and asserting specification-versus-annotation
 * consistency for every operation, is the job of the dedicated consistency test introduced with the
 * first secured endpoint.
 *
 * <p>Note for any later step that wants to pin this operation's access rule from Java: the
 * generated {@code @Secured} sits on {@code AbstractStatusController.getApiStatusApi()}, which is
 * the method carrying the routing annotations, and that method delegates to the {@code
 * getApiStatus()} overridden below. Annotating this concrete class, or its override, therefore does
 * <em>not</em> govern the route -- the rule on the routed wrapper wins. Changing the rule for this
 * operation means changing its {@code security} declaration in the specification.
 *
 * <p>The endpoint exposes no consent data and performs no database access, so it stays answerable
 * even when downstream dependencies are unavailable.
 */
@Controller
public class ApiStatusController extends AbstractStatusController {

    /** Status value reported while the API is reachable. */
    private static final String STATUS_OK = "ok";

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
