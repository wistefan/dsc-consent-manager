package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.AbstractUsersController;
import com.seamware.consentmanager.api.generated.model.User;
import com.seamware.consentmanager.security.UserPrincipal;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;

/**
 * Serves the {@code USER}-role self-service operations declared in {@code api/openapi.yaml}.
 *
 * <p>Neither handler provisions anything. {@code PrincipalResolutionFilter} has already run {@code
 * UserService.provisionFromToken} by the time a route with a principal parameter is invoked, so the
 * row exists and {@link UserPrincipal#created()} says whether this request's filter inserted it.
 * Asking the service again would always take the existing-row path and make {@code 201}
 * unreachable.
 */
@Controller
public class UserController extends AbstractUsersController {

    private final UserMapper mapper;

    public UserController(UserMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * {@code 201} when this request registered the caller, {@code 200} when it was already known.
     */
    @Override
    public HttpResponse<User> registerUser(UserPrincipal principal) {
        User body = mapper.toRepresentation(principal.user());
        return principal.created() ? HttpResponse.created(body) : HttpResponse.ok(body);
    }

    /** The caller's own record. Registration on first sight is why this never reports 404. */
    @Override
    public HttpResponse<User> getCurrentUser(UserPrincipal principal) {
        return HttpResponse.ok(mapper.toRepresentation(principal.user()));
    }
}
