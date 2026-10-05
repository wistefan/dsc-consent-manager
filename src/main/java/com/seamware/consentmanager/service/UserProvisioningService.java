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
 * Creates and refreshes the {@code users} row behind a {@code USER} token. Blocks on JDBC.
 *
 * <p>Users only - participants are registered explicitly (TICKET-005).
 */
@Singleton
public class UserProvisioningService {

    private static final Logger LOG = LoggerFactory.getLogger(UserProvisioningService.class);

    private final UserRepository users;

    public UserProvisioningService(UserRepository users) {
        this.users = users;
    }

    /**
     * Returns the row the principal's identifier names, inserting it on first sight.
     *
     * <p>{@code uq_users_identifier} settles concurrent first requests; the leading read only skips
     * a doomed insert. Each repository call is its own transaction.
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
            // Another request inserted first, so adopt its row; no row means this was not the race.
            return users.findByIdentifier(principal.identifier())
                    .map(row -> refresh(row, principal))
                    .orElseThrow(() -> e);
        }
    }

    /** Writes back the asserted claims that differ, leaving {@code updated_at} alone otherwise. */
    private User refresh(User stored, UserPrincipal principal) {
        String email = asserted(principal.email(), stored.getEmail());
        String firstName = asserted(principal.givenName(), stored.getFirstName());
        String lastName = asserted(principal.familyName(), stored.getLastName());
        if (Objects.equals(stored.getEmail(), email)
                && Objects.equals(stored.getFirstName(), firstName)
                && Objects.equals(stored.getLastName(), lastName)) {
            return stored;
        }
        LOG.debug("Refreshing the stored display claims of user {}", stored.getId());
        stored.setEmail(email);
        stored.setFirstName(firstName);
        stored.setLastName(lastName);
        return users.update(stored);
    }

    /** The claim where the token asserts one; an omitted claim is not a cleared value. */
    @Nullable
    private static String asserted(@Nullable String claimed, @Nullable String stored) {
        return claimed != null ? claimed : stored;
    }
}
