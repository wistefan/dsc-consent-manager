package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.domain.UserParticipant;
import com.seamware.consentmanager.domain.UserParticipant.UserParticipantId;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.UserParticipantRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.security.UserPrincipal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.exceptions.DataAccessException;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the {@code users} row and its participant links: just-in-time provisioning from a {@code
 * USER} token and the deterministic create-or-link registration rule. Blocks on JDBC.
 *
 * <p>Users only - participants are registered explicitly (TICKET-005).
 *
 * <p>Nothing here is {@code @Transactional}, and that is load-bearing rather than an oversight.
 * Both writes resolve a concurrent first caller by letting the unique constraint reject the
 * duplicate and then re-reading the winner's row, which PostgreSQL permits only outside a
 * transaction: inside one the violation aborts the transaction and the re-read fails with "current
 * transaction is aborted" instead. Each repository call is therefore its own transaction, and
 * {@link #registerForParticipant} is written to be safe when re-entered rather than atomic across
 * the two tables.
 *
 * <p>The cost of that is an orphan: <em>any</em> failure of the link insert - not only a lost race,
 * but a connection loss, a timeout or a deleted participant - leaves behind the {@code users} row
 * the same call just created, and propagates the exception. Re-entering repairs the link, but
 * because attributes are write-on-create the orphan permanently carries the first caller's {@code
 * email}, {@code firstName} and {@code lastName}, which every later participant - and the user's
 * own identity provider, for any claim it does not assert - then inherits. The retry also reports
 * {@link RegistrationOutcome#LINKED} rather than {@code CREATED}, so the caller that in fact
 * created the record never learns it did.
 */
@Singleton
public class UserService {

    private static final Logger LOG = LoggerFactory.getLogger(UserService.class);

    private final UserRepository users;

    private final UserParticipantRepository links;

    private final ParticipantRepository participants;

    public UserService(
            UserRepository users,
            UserParticipantRepository links,
            ParticipantRepository participants) {
        this.users = users;
        this.links = links;
        this.participants = participants;
    }

    /**
     * Returns the row the principal's identifier names, inserting it on first sight and refreshing
     * the display claims the token asserts.
     *
     * <p>{@code uq_users_identifier} settles concurrent first requests; the leading read only skips
     * a doomed insert. {@link ProvisionedUser#created()} is the signal {@code POST /users/register}
     * answers {@code 201} from, so it must come from here rather than be re-derived downstream.
     */
    public ProvisionedUser provisionFromToken(UserPrincipal principal) {
        Optional<User> existing = users.findByIdentifier(principal.identifier());
        if (existing.isPresent()) {
            return adopted(refresh(existing.get(), principal));
        }
        try {
            User inserted =
                    users.save(
                            new User(
                                    principal.identifier(),
                                    principal.email(),
                                    principal.givenName(),
                                    principal.familyName()));
            return new ProvisionedUser(inserted, true);
        } catch (DataAccessException e) {
            // Another request inserted first, so adopt its row; no row means this was not the race.
            return users.findByIdentifier(principal.identifier())
                    .map(row -> adopted(refresh(row, principal)))
                    .orElseThrow(() -> e);
        }
    }

    /** The user a global identifier names, or empty when no such user is registered. */
    public Optional<User> findByIdentifier(String identifier) {
        return users.findByIdentifier(identifier);
    }

    /** The identifiers of the participants a user is linked to, sorted so responses are stable. */
    public List<String> participantIdentifiersFor(User user) {
        Set<UUID> linked =
                links.findByIdUserId(user.getId()).stream()
                        .map(UserParticipant::getParticipantId)
                        .collect(Collectors.toSet());
        if (linked.isEmpty()) {
            return List.of();
        }
        return participants.findByIdIn(linked).stream()
                .map(Participant::getIdentifier)
                .sorted()
                .toList();
    }

    /**
     * Creates or adopts the user the registration names and links it to the participant.
     *
     * <p>Matching is on the global identifier alone - never on email, and two rows are never
     * merged. Attributes are written only on the insert that creates the user, so one participant
     * cannot overwrite what another participant or the user's own identity provider asserted; a
     * supplied {@code localIdentifier} is the one value a later call may still change, and it lives
     * on the link rather than on the user.
     *
     * <p>Deliberately not transactional; see the class note.
     */
    public RegistrationResult registerForParticipant(
            Participant participant, UserRegistration registration) {
        ProvisionedUser resolved = findOrCreate(registration);
        boolean freshLink = link(resolved.user(), participant, registration.localIdentifier());
        RegistrationOutcome outcome;
        if (resolved.created()) {
            outcome = RegistrationOutcome.CREATED;
        } else if (freshLink) {
            outcome = RegistrationOutcome.LINKED;
        } else {
            outcome = RegistrationOutcome.ALREADY_LINKED;
        }
        return new RegistrationResult(resolved.user(), outcome);
    }

    /**
     * The user the identifier names, inserted from the registration's attributes on first sight.
     *
     * <p>Existence is settled by {@code uq_users_identifier}, never by the leading read, which is
     * only a fast path past a doomed insert.
     */
    private ProvisionedUser findOrCreate(UserRegistration registration) {
        Optional<User> existing = users.findByIdentifier(registration.identifier());
        if (existing.isPresent()) {
            return adopted(existing.get());
        }
        try {
            User inserted =
                    users.save(
                            new User(
                                    registration.identifier(),
                                    registration.email(),
                                    registration.firstName(),
                                    registration.lastName()));
            return new ProvisionedUser(inserted, true);
        } catch (DataAccessException e) {
            // A concurrent registration inserted first; adopt its row and leave its attributes be.
            return users.findByIdentifier(registration.identifier())
                    .map(UserService::adopted)
                    .orElseThrow(() -> e);
        }
    }

    /**
     * Links the user to the participant, reporting whether this call inserted the link.
     *
     * <p>{@code pk_user_participants} settles a concurrent duplicate the same way the user insert
     * does: the loser re-reads and reports an existing link.
     */
    private boolean link(User user, Participant participant, @Nullable String localIdentifier) {
        UserParticipantId id = new UserParticipantId(user.getId(), participant.getId());
        Optional<UserParticipant> existing = links.findById(id);
        if (existing.isPresent()) {
            refreshLocalIdentifier(existing.get(), localIdentifier);
            return false;
        }
        try {
            links.save(new UserParticipant(user.getId(), participant.getId(), localIdentifier));
            return true;
        } catch (DataAccessException e) {
            return links.findById(id)
                    .map(
                            raced -> {
                                refreshLocalIdentifier(raced, localIdentifier);
                                return false;
                            })
                    .orElseThrow(() -> e);
        }
    }

    /** Writes a supplied local identifier that differs; an omitted one is not a cleared value. */
    private void refreshLocalIdentifier(UserParticipant link, @Nullable String localIdentifier) {
        if (localIdentifier == null || Objects.equals(link.getLocalIdentifier(), localIdentifier)) {
            return;
        }
        LOG.debug("Updating the local identifier of the link {}", link.getId());
        link.setLocalIdentifier(localIdentifier);
        links.update(link);
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

    /** A row this call found rather than inserted. */
    private static ProvisionedUser adopted(User row) {
        return new ProvisionedUser(row, false);
    }

    /** The claim where the token asserts one; an omitted claim is not a cleared value. */
    @Nullable
    private static String asserted(@Nullable String claimed, @Nullable String stored) {
        return claimed != null ? claimed : stored;
    }
}
