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
 * @param participant the participant whose links bound the result, {@code null} only for a {@code
 *     CATALOG} caller, which reads dataspace-wide. A {@code PARTICIPANT} always carries its row, so
 *     {@code null} never stands for "participant unknown".
 */
public record CallerScope(Role role, @Nullable Participant participant) {

    /**
     * The scope a resolved principal grants.
     *
     * <p>A {@link UserPrincipal} has no user-search scope at all; the {@code USER} role reads its
     * own record through {@code GET /users/me}. The routes using this are {@code @Secured} against
     * {@code USER}, so that branch is a defence against a widened route rather than a reachable
     * path.
     *
     * <p>An unregistered participant is refused here rather than scoped: a {@code null} participant
     * means dataspace-wide downstream, so admitting one would widen the caller instead of stopping
     * it.
     */
    public static CallerScope of(ConsentManagerPrincipal principal) {
        return switch (principal) {
            case ParticipantPrincipal caller ->
                    new CallerScope(Role.PARTICIPANT, caller.requireRegistered());
            case CatalogPrincipal ignored -> new CallerScope(Role.CATALOG, null);
            case UserPrincipal ignored ->
                    throw new ForbiddenException("This operation is not open to the USER role.");
        };
    }
}
