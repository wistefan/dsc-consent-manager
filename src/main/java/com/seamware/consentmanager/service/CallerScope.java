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
 * Derived from the typed principal, never the request, so a participant cannot widen it.
 *
 * @param role the role the caller acts as on this operation
 * @param participant whose links bound the result; {@code null} only for {@code CATALOG}
 *     (dataspace-wide), never for "participant unknown"
 */
public record CallerScope(Role role, @Nullable Participant participant) {

    /**
     * Rejects a {@code PARTICIPANT} scope with no row, which downstream would read dataspace-wide.
     */
    public CallerScope {
        if (role == Role.PARTICIPANT && participant == null) {
            throw new IllegalArgumentException(
                    "A PARTICIPANT scope must carry the participant whose links bound it;"
                            + " a null participant reads as dataspace-wide.");
        }
    }

    /**
     * The scope a resolved principal grants. Fails closed: an unregistered participant is refused
     * rather than widened, as is a {@link UserPrincipal} ({@code @Secured} excludes it).
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
