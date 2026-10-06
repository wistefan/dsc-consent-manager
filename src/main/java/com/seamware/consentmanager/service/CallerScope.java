package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.error.ForbiddenException;
import com.seamware.consentmanager.security.CatalogPrincipal;
import com.seamware.consentmanager.security.ConsentManagerPrincipal;
import com.seamware.consentmanager.security.ParticipantPrincipal;
import com.seamware.consentmanager.security.Role;
import com.seamware.consentmanager.security.UserPrincipal;
import io.micronaut.core.annotation.Nullable;

/**
 * How much of the user population a caller may read: its own linked users, or the dataspace.
 *
 * <p>Derived from the typed principal rather than from anything in the request, so a participant
 * cannot widen its own scope by what it sends.
 *
 * @param role the role the caller acts as on this operation
 * @param participant the participant whose links bound the result, {@code null} when the caller
 *     reads dataspace-wide
 */
public record CallerScope(Role role, @Nullable Participant participant) {

    /**
     * The scope a resolved principal grants.
     *
     * <p>A {@link UserPrincipal} has no user-search scope at all; the {@code USER} role reads its
     * own record through {@code GET /users/me}. The routes using this are {@code @Secured} against
     * {@code USER}, so that branch is a defence against a widened route rather than a reachable
     * path.
     */
    public static CallerScope of(ConsentManagerPrincipal principal) {
        return switch (principal) {
            case ParticipantPrincipal caller ->
                    new CallerScope(Role.PARTICIPANT, caller.participant());
            case CatalogPrincipal ignored -> new CallerScope(Role.CATALOG, null);
            case UserPrincipal ignored ->
                    throw new ForbiddenException("This operation is not open to the USER role.");
        };
    }
}
