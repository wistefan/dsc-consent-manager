package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.security.UserPrincipal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.exceptions.DataAccessException;
import jakarta.inject.Singleton;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backs every {@code USER} token with a {@code users} row, creating it on first sight and keeping
 * its display fields in step with the token afterwards.
 *
 * <p>Users are the only identity this service creates on the strength of a token: a participant is
 * registered explicitly (TICKET-005), so a token naming an unknown participant is refused rather
 * than provisioned. The row exists so user-owned records have something to key on; the identity
 * provider stays the source of truth and {@code email}, {@code firstName} and {@code lastName} are
 * non-authoritative copies of the claims, never input to an authorization decision.
 *
 * <p>Blocks on JDBC - call it from a thread that may block.
 */
@Singleton
public class UserProvisioningService {

    private static final Logger LOG = LoggerFactory.getLogger(UserProvisioningService.class);

    private final UserRepository users;

    /**
     * Creates the service.
     *
     * @param users the repository the row is read from and written to
     */
    public UserProvisioningService(UserRepository users) {
        this.users = users;
    }

    /**
     * Returns the persisted user the principal's identifier names, creating it if this is the first
     * request that identifier has made and refreshing its display fields when the token disagrees
     * with the stored copy.
     *
     * <p>Concurrent first requests for one identifier all return the same row. What makes that hold
     * is {@code uq_users_identifier}: the losing insert is rejected by the constraint and re-reads
     * the winner's row rather than failing the request. The read below is how the steady state -
     * every request after the first - avoids a doomed insert, and is not the guard. Each repository
     * call is its own transaction, so a rejected insert rolls back alone and leaves the re-read a
     * usable connection.
     *
     * @param principal the caller resolved from the token
     * @return the row, which is already persisted when this returns
     */
    public User provision(UserPrincipal principal) {
        Optional<User> existing = users.findByIdentifier(principal.identifier());
        if (existing.isPresent()) {
            return refresh(existing.get(), principal);
        }
        try {
            return users.save(
                    new User(
                            principal.identifier(),
                            principal.email(),
                            principal.givenName(),
                            principal.familyName()));
        } catch (DataAccessException e) {
            // Another request for the same identifier inserted first. Its row is the one that
            // exists, so adopt it; a genuinely absent row means the failure was not the race.
            return users.findByIdentifier(principal.identifier())
                    .map(row -> refresh(row, principal))
                    .orElseThrow(() -> e);
        }
    }

    /**
     * Writes the token's display claims onto the stored row, but only when at least one of them
     * actually differs - an unchanged claim set must not churn {@code updated_at} on every request.
     *
     * <p>A claim the token stops carrying clears the stored value, so the row never outlives what
     * the provider says about the user.
     *
     * @param stored the row as read
     * @param principal the caller resolved from the token
     * @return the row as it now stands
     */
    private User refresh(User stored, UserPrincipal principal) {
        if (matches(stored.getEmail(), principal.email())
                && matches(stored.getFirstName(), principal.givenName())
                && matches(stored.getLastName(), principal.familyName())) {
            return stored;
        }
        LOG.debug("Refreshing the stored display claims of user {}", stored.getId());
        stored.setEmail(principal.email());
        stored.setFirstName(principal.givenName());
        stored.setLastName(principal.familyName());
        return users.update(stored);
    }

    /** Reports whether a stored value and a claim value are the same, both-absent included. */
    private static boolean matches(@Nullable String stored, @Nullable String claimed) {
        return Objects.equals(stored, claimed);
    }
}
