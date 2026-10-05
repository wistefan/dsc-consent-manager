package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.AbstractUsersController;
import com.seamware.consentmanager.api.generated.model.User;
import com.seamware.consentmanager.api.generated.model.UserSearch;
import com.seamware.consentmanager.error.NotFoundException;
import com.seamware.consentmanager.security.ConsentManagerPrincipal;
import com.seamware.consentmanager.security.UserPrincipal;
import com.seamware.consentmanager.service.CallerScope;
import com.seamware.consentmanager.service.UserSearchCriteria;
import com.seamware.consentmanager.service.UserService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import java.util.List;

/**
 * Serves the {@code /users} operations declared in {@code api/openapi.yaml}: the {@code USER}-role
 * self-service pair and the {@code PARTICIPANT}-or-{@code CATALOG} read surface.
 *
 * <p>The self-service handlers provision nothing. {@code PrincipalResolutionFilter} has already run
 * {@code UserService.provisionFromToken} by the time a route with a principal parameter is invoked,
 * so the row exists and {@link UserPrincipal#created()} says whether this request's filter inserted
 * it. Asking the service again would always take the existing-row path and make {@code 201}
 * unreachable.
 */
@Controller
public class UserController extends AbstractUsersController {

    /** Published for an identifier naming nobody and for one outside the caller's scope alike. */
    private static final String NOT_FOUND_DETAIL = "No user is registered under this identifier.";

    private final UserService users;

    private final UserMapper mapper;

    public UserController(UserService users, UserMapper mapper) {
        this.users = users;
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

    /**
     * The user the identifier names, or {@code 404} when the caller may not read them.
     *
     * <p>An unscoped user is reported exactly as an absent one, deliberately: a {@code 403} here
     * would confirm that the identifier names a registered user.
     */
    @Override
    public HttpResponse<User> getUser(ConsentManagerPrincipal principal, String identifier) {
        return users.lookup(identifier, CallerScope.of(principal))
                .map(mapper::toRepresentation)
                .map(HttpResponse::ok)
                .orElseThrow(() -> new NotFoundException(NOT_FOUND_DETAIL));
    }

    /** The users matching the criteria, confined to what the caller may read. */
    @Override
    public HttpResponse<List<User>> searchUsers(
            ConsentManagerPrincipal principal, UserSearch criteria) {
        List<User> found =
                users.search(toCriteria(criteria), CallerScope.of(principal)).stream()
                        .map(mapper::toRepresentation)
                        .toList();
        return HttpResponse.ok(found);
    }

    /** The request body as the service's criteria; rejecting an empty one is the service's call. */
    private static UserSearchCriteria toCriteria(UserSearch body) {
        return new UserSearchCriteria(
                body.getIdentifier(), body.getEmail(), body.getParticipantIdentifier());
    }
}
