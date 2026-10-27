package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.AbstractUsersController;
import com.seamware.consentmanager.api.generated.model.ErasureSummary;
import com.seamware.consentmanager.api.generated.model.User;
import com.seamware.consentmanager.api.generated.model.UserSearch;
import com.seamware.consentmanager.error.NotFoundException;
import com.seamware.consentmanager.security.ConsentManagerPrincipal;
import com.seamware.consentmanager.security.UserPrincipal;
import com.seamware.consentmanager.service.CallerScope;
import com.seamware.consentmanager.service.ConsentReporter;
import com.seamware.consentmanager.service.ErasureResult;
import com.seamware.consentmanager.service.UserSearchCriteria;
import com.seamware.consentmanager.service.UserService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import java.util.List;

/**
 * Serves {@code /users}: {@code USER} self-service and the {@code PARTICIPANT}/{@code CATALOG}
 * reads. Provisions nothing; the principal filter already did, see {@link UserPrincipal#created()}.
 */
@Controller
public class UserController extends AbstractUsersController {

    /** Published for an identifier naming nobody and for one outside the caller's scope alike. */
    private static final String NOT_FOUND_DETAIL = "No user is registered under this identifier.";

    private final UserService users;

    private final ConsentReporter reporter;

    private final UserMapper mapper;

    public UserController(UserService users, UserMapper mapper, ConsentReporter reporter) {
        this.users = users;
        this.reporter = reporter;
        this.mapper = mapper;
    }

    /** {@code 201} when this request registered the caller, {@code 200} when already known. */
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
     * Erases the caller's record (never {@code 404}). The pseudonym, the only handle on retained
     * consents, is published once and absent when the record was deleted outright. See ADR-0007.
     */
    @Override
    public HttpResponse<ErasureSummary> eraseCurrentUser(UserPrincipal principal) {
        ErasureResult erased = users.erase(principal.user());
        reporter.report(erased.moves());
        return HttpResponse.ok(
                new ErasureSummary(
                                erased.consentsRevoked(),
                                erased.consentsTerminated(),
                                erased.linksRemoved())
                        .pseudonym(erased.pseudonym()));
    }

    /**
     * The user the identifier names, or {@code 404} when out of the caller's scope (a 403 would
     * confirm the identifier); {@code participants} is scoped to the caller too.
     */
    @Override
    public HttpResponse<User> getUser(ConsentManagerPrincipal principal, String identifier) {
        CallerScope scope = CallerScope.of(principal);
        return users.lookup(identifier, scope)
                .map(user -> mapper.toRepresentation(user, scope))
                .map(HttpResponse::ok)
                .orElseThrow(() -> new NotFoundException(NOT_FOUND_DETAIL));
    }

    /** The users matching the criteria, confined to what the caller may read. */
    @Override
    public HttpResponse<List<User>> searchUsers(
            ConsentManagerPrincipal principal, UserSearch criteria) {
        CallerScope scope = CallerScope.of(principal);
        List<User> found =
                users.search(toCriteria(criteria), scope).stream()
                        .map(user -> mapper.toRepresentation(user, scope))
                        .toList();
        return HttpResponse.ok(found);
    }

    /** The request body as the service's criteria; rejecting an empty one is the service's call. */
    private static UserSearchCriteria toCriteria(UserSearch body) {
        return new UserSearchCriteria(
                body.getIdentifier(), body.getEmail(), body.getParticipantIdentifier());
    }
}
