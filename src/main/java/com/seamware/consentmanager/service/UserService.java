package com.seamware.consentmanager.service;

import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.domain.UserParticipant;
import com.seamware.consentmanager.domain.UserParticipant.UserParticipantId;
import com.seamware.consentmanager.domain.UuidGenerator;
import com.seamware.consentmanager.error.ApiException;
import com.seamware.consentmanager.error.BadRequestException;
import com.seamware.consentmanager.error.ConflictException;
import com.seamware.consentmanager.error.NotFoundException;
import com.seamware.consentmanager.error.ProblemType;
import com.seamware.consentmanager.repository.ConsentEventRepository;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.UserParticipantRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.security.UserPrincipal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.exceptions.DataAccessException;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the {@code users} row and its participant links: just-in-time provisioning from a {@code
 * USER} token, the deterministic create-or-link registration rule, and the scoped reads behind the
 * lookup and search operations. Blocks on JDBC.
 *
 * <p>Users only - participants are registered explicitly (TICKET-005).
 *
 * <p>No registration path here is {@code @Transactional}, and that is load-bearing rather than an
 * oversight. Both writes resolve a concurrent first caller by letting the unique constraint reject
 * the duplicate and then re-reading the winner's row, which PostgreSQL permits only outside a
 * transaction: inside one the violation aborts the transaction and the re-read fails with "current
 * transaction is aborted" instead. Each repository call is therefore its own transaction, and
 * {@link #registerForParticipant} is written to be safe when re-entered rather than atomic across
 * the two tables. {@link #erase} is the one exception and explains itself there.
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

    /**
     * Why a search naming no criterion is refused; published to the caller as the problem detail.
     */
    private static final String EMPTY_SEARCH_DETAIL =
            "A user search must name at least one of identifier, email or participantIdentifier.";

    /** Why a bulk entry naming no identifier is refused; published as that entry's reason. */
    private static final String BLANK_IDENTIFIER_DETAIL =
            "A registration must name a non-blank identifier.";

    /**
     * Why a bulk entry carrying an unusable address is refused; published as that entry's reason.
     */
    private static final String MALFORMED_EMAIL_DETAIL =
            "The email address is not a valid address.";

    /**
     * The address shape a bulk entry has to satisfy, checked here rather than declared on {@code
     * BulkUserRegistrationEntry}: a schema constraint cascades into the array and would fail the
     * whole batch over one dirty address. Deliberately as permissive as bean validation's own
     * {@code @Email} - a local part, an {@code @}, a domain, no whitespace - since narrowing it
     * would reject addresses the single-registration route accepts.
     */
    private static final Pattern EMAIL_SHAPE = Pattern.compile("[^\\s@]+@[^\\s@]+");

    /**
     * Stands in for an unexpected failure of a single bulk entry. The exception's own message may
     * name a column or a constraint, which no caller is entitled to see.
     */
    private static final String UNREGISTERABLE_ENTRY_DETAIL =
            "The entry could not be registered. Retrying it on its own will report why.";

    /** Why an over-long batch is refused whole; formatted with the sent size and the maximum. */
    private static final String OVERSIZED_BATCH_DETAIL =
            "A bulk registration carried %d entries, more than the %d this deployment permits.";

    /**
     * Published for a link the caller does not hold and for an identifier naming nobody alike. A
     * {@code 403} telling the two apart would confirm that the identifier names a registered user,
     * which a participant without a link is not entitled to learn.
     */
    private static final String NO_LINK_DETAIL =
            "This participant holds no link to a user under this identifier.";

    /** Why an unlink is refused while the caller is still party to a granted consent. */
    private static final String ACTIVE_CONSENT_DETAIL =
            "This participant is still party to %d granted consent(s) for this user. "
                    + "Revoke them before unlinking.";

    /**
     * Reserved prefix of the opaque identifier an erased record carries.
     *
     * <p>The suffix is a fresh UUIDv7, not a hash of the original identifier: a hash over the space
     * of email addresses and subject identifiers is reversible by enumeration, which would defeat
     * the erasure.
     */
    private static final String ERASED_IDENTIFIER_PREFIX = "urn:consent-manager:erased:";

    /** Attributed to the service rather than to a person, who no longer exists by then. */
    private static final String ERASURE_ACTOR = "consent-manager";

    /** Says, in the retained audit trail, why a consent was revoked without naming anyone. */
    private static final String ERASURE_DETAIL_KEY = "reason";

    private static final String ERASURE_DETAIL_REASON = "erasure-requested-by-data-subject";

    private final UserRepository users;

    private final UserParticipantRepository links;

    private final ParticipantRepository participants;

    private final ConsentRepository consents;

    private final ConsentEventRepository consentEvents;

    private final ConsentManagerConfiguration.Users configuration;

    public UserService(
            UserRepository users,
            UserParticipantRepository links,
            ParticipantRepository participants,
            ConsentRepository consents,
            ConsentEventRepository consentEvents,
            ConsentManagerConfiguration.Users configuration) {
        this.users = users;
        this.links = links;
        this.participants = participants;
        this.consents = consents;
        this.consentEvents = consentEvents;
        this.configuration = configuration;
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

    /**
     * The user an identifier names, as far as this caller may see it.
     *
     * <p>Empty for an identifier naming nobody and for a user outside the caller's scope alike, so
     * the lookup route answers {@code 404} for both and never confirms that an identifier it may
     * not read is registered.
     */
    public Optional<User> lookup(String identifier, CallerScope scope) {
        return search(new UserSearchCriteria(identifier, null, null), scope).stream().findFirst();
    }

    /**
     * The users matching every supplied criterion, confined to what the caller may read.
     *
     * <p>A {@code PARTICIPANT} caller's result is intersected with its own links whatever the
     * criteria say, so a {@code participantIdentifier} naming somebody else only narrows it; a
     * {@code CATALOG} caller reads across the dataspace. Criteria naming nobody is an empty list,
     * but criteria naming <em>nothing</em> is a {@link BadRequestException} rather than a listing
     * of every registered user.
     *
     * <p>Sorted by identifier and then capped at {@code consent-manager.users.search-max-results},
     * so an unbounded population cannot be serialised in one response and what the cap drops is the
     * same set on every request. There is no pagination: a result of exactly the cap's size may be
     * truncated, which the operation description tells the caller to answer by narrowing criteria.
     */
    public List<User> search(UserSearchCriteria criteria, CallerScope scope) {
        if (criteria.isEmpty()) {
            throw new BadRequestException(EMPTY_SEARCH_DETAIL);
        }
        Optional<List<UUID>> required = requiredParticipants(criteria, scope);
        if (required.isEmpty()) {
            return List.of();
        }
        List<UUID> participantIds = required.get();
        Candidates candidates = candidates(criteria, participantIds);
        List<UUID> unproven = candidates.unproven(participantIds);
        return candidates.users().stream()
                .filter(user -> matchesEmail(user, criteria.email()))
                .filter(user -> isLinkedToAll(user, unproven))
                .sorted(Comparator.comparing(User::getIdentifier))
                .limit(configuration.getSearchMaxResults())
                .toList();
    }

    /**
     * Participants every match must be linked to, or empty when no user can possibly satisfy them.
     *
     * <p>The caller's own participant comes first so {@link #candidates} starts from its links when
     * the criteria name no user attribute. An unregistered {@code participantIdentifier} has no
     * links at all, which is an empty result rather than an error: whether that identifier exists
     * is not something a search is entitled to reveal.
     */
    private Optional<List<UUID>> requiredParticipants(
            UserSearchCriteria criteria, CallerScope scope) {
        List<UUID> required = new ArrayList<>();
        if (scope.participant() != null) {
            required.add(scope.participant().getId());
        }
        if (criteria.participantIdentifier() != null) {
            Optional<Participant> requested =
                    participants.findByIdentifier(criteria.participantIdentifier());
            if (requested.isEmpty()) {
                return Optional.empty();
            }
            UUID id = requested.get().getId();
            if (!required.contains(id)) {
                required.add(id);
            }
        }
        return Optional.of(required);
    }

    /**
     * The rows the cheapest criterion narrows to, before the remaining criteria filter them.
     *
     * <p>Falling through to the links means the criteria named no user attribute, which - the empty
     * set having already been rejected - leaves {@code participantIdentifier} as the only
     * possibility and therefore guarantees {@code participantIds} is not empty.
     */
    private Candidates candidates(UserSearchCriteria criteria, List<UUID> participantIds) {
        if (criteria.identifier() != null) {
            return new Candidates(
                    users.findByIdentifier(criteria.identifier()).map(List::of).orElseGet(List::of),
                    null);
        }
        if (criteria.email() != null) {
            return new Candidates(users.findAllByEmailIgnoreCase(criteria.email()), null);
        }
        UUID from = participantIds.get(0);
        return new Candidates(usersLinkedTo(from), from);
    }

    /**
     * Candidate rows, plus the participant whose link set produced them.
     *
     * <p>That link needs no re-checking, which on the commonest participant path - a caller listing
     * its own users - removes the link query {@link #isLinkedToAll} would otherwise issue for every
     * single candidate.
     */
    private record Candidates(List<User> users, @Nullable UUID provenLink) {

        /** The required links a candidate's provenance does not already establish. */
        List<UUID> unproven(List<UUID> participantIds) {
            return participantIds.stream().filter(id -> !id.equals(provenLink)).toList();
        }
    }

    /** The users a participant is linked to, resolved in one query per table. */
    private List<User> usersLinkedTo(UUID participantId) {
        Set<UUID> userIds =
                links.findByIdParticipantId(participantId).stream()
                        .map(UserParticipant::getUserId)
                        .collect(Collectors.toSet());
        return userIds.isEmpty() ? List.of() : users.findByIdIn(userIds);
    }

    /** A null criterion matches every user; a supplied one matches exactly, ignoring case. */
    private static boolean matchesEmail(User user, @Nullable String email) {
        return email == null
                || (user.getEmail() != null && user.getEmail().equalsIgnoreCase(email));
    }

    /**
     * Whether the user is linked to every one of the participants the search still requires.
     *
     * <p>Asks the index one membership question per participant rather than materialising the
     * user's whole link set: the list never holds more than the caller's own participant and the
     * one the criteria name, and the commonest case has been emptied by {@link Candidates}.
     */
    private boolean isLinkedToAll(User user, List<UUID> participantIds) {
        return participantIds.stream()
                .allMatch(id -> links.existsByIdUserIdAndIdParticipantId(user.getId(), id));
    }

    /**
     * The participant identifiers a caller may be told about, which is not always every one.
     *
     * <p>A {@code PARTICIPANT} is told about its own link and nothing else: which <em>other</em>
     * participants a user is affiliated with is a more sensitive disclosure than whether the
     * identifier exists at all, and the scoped reads already answer {@code 404} rather than {@code
     * 403} to withhold the latter. A caller reading dataspace-wide sees the full list, as does the
     * user's own record.
     *
     * <p>Costs one index probe rather than the whole link set for a participant caller, so a search
     * returning many users does not materialise every one of their affiliations only to discard it.
     */
    public List<String> participantIdentifiersFor(User user, CallerScope scope) {
        Participant caller = scope.participant();
        if (caller == null) {
            return participantIdentifiersFor(user);
        }
        return links.existsByIdUserIdAndIdParticipantId(user.getId(), caller.getId())
                ? List.of(caller.getIdentifier())
                : List.of();
    }

    /** Every participant a user is linked to, sorted so responses are stable. */
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
     * Applies {@link #registerForParticipant} to every entry of a batch, independently.
     *
     * <p>Results come back one per entry, in the order the entries were given. An entry that cannot
     * be applied is reported as {@link RegistrationOutcome#REJECTED} with a reason instead of
     * failing the batch, which is the whole point of the operation: a batch routinely mixes users
     * the participant has just acquired with users it registered long ago, and one unusable entry
     * is no reason to discard the rest.
     *
     * <p>A batch larger than {@code consent-manager.users.bulk-max-size} is a {@link
     * BadRequestException} and writes nothing. That is a fact about the request rather than about
     * an entry, so it is not an outcome.
     *
     * <p>Deliberately not transactional, and the isolation between entries rests on exactly that.
     * Wrapping an entry in a transaction of its own - a {@code REQUIRES_NEW} collaborator, a
     * programmatic {@code executeWrite} - would put {@link #registerForParticipant} inside one and
     * break the re-read in its catch block, so an entry that merely lost the race on an identifier
     * would be reported {@code REJECTED} where it belongs {@code LINKED}. With no ambient
     * transaction every repository call autocommits, which is what keeps one entry's constraint
     * violation out of the next entry.
     */
    public List<BulkEntryResult> registerBulkForParticipant(
            Participant participant, List<UserRegistration> registrations) {
        int maximum = configuration.getBulkMaxSize();
        if (registrations.size() > maximum) {
            throw new BadRequestException(
                    OVERSIZED_BATCH_DETAIL.formatted(registrations.size(), maximum));
        }
        return registrations.stream().map(entry -> apply(participant, entry)).toList();
    }

    /**
     * Writes the local identifier the caller asked for onto its own link and returns it as re-read.
     *
     * <p>A {@code null} clears the stored value: this is the explicit-null arm of the {@code PATCH}
     * body's tri-state, and the only way to remove a local identifier once written. A property the
     * caller omitted never reaches this method - the handler reads the link through {@link
     * #linkFor} instead, so an unmentioned property is left alone, as a merge-patch client expects.
     *
     * <p>Returning the re-read entity rather than the object just mutated is deliberate: the
     * response then reports what the database holds, including anything a concurrent write settled
     * differently, instead of echoing an optimistic in-memory copy back to the caller.
     *
     * <p>The {@code users} row is out of reach here by design; attributes belong to whoever created
     * the record and to the user's own identity provider.
     */
    public UserParticipant updateLink(
            Participant participant, String identifier, @Nullable String localIdentifier) {
        UserParticipant link = linkFor(participant, identifier);
        link.setLocalIdentifier(blankToNull(localIdentifier));
        links.update(link);
        return links.findById(link.getId())
                .orElseThrow(() -> new NotFoundException(NO_LINK_DETAIL));
    }

    /**
     * Removes the caller's link to a user, leaving the user row, its consents and every other
     * participant's link in place.
     *
     * <p>Refused with a {@link ConflictException} while any consent for that user is {@code
     * GRANTED} and names the caller as provider or consumer: unlinking would strand a consent whose
     * participant no longer knows the user. A granted consent between two other participants does
     * not block it, and neither does one in any other status - {@code PENDING} and {@code DRAFT}
     * included, deliberately: neither authorises any data flow today, so neither is worth refusing
     * an unlink over.
     *
     * <p>The check is therefore advisory in both directions, and cannot be made otherwise from this
     * side: a consent granted - or a pending one approved - between the select and the delete slips
     * through, since the two are separate statements and {@code consents} has no constraint that
     * could serialise them. TODO(consent-lifecycle): the complementary half belongs on the
     * consent-creation path, which must refuse to grant a consent for a participant holding no link
     * to the subject. Only the two together uphold "no granted consent without a link"; see
     * docs/user-identifiers.md.
     *
     * <p>Erasure is the data subject's own concern and goes through a different path; this
     * operation never touches {@link UserRepository}.
     */
    public void unlink(Participant participant, String identifier) {
        UserParticipant link = linkFor(participant, identifier);
        long blocking =
                consents.findByUserIdAndStatus(link.getUserId(), ConsentStatus.GRANTED).stream()
                        .filter(consent -> isParty(consent, participant))
                        .count();
        if (blocking > 0) {
            throw new ConflictException(ACTIVE_CONSENT_DETAIL.formatted(blocking));
        }
        links.deleteByIdUserIdAndIdParticipantId(link.getUserId(), participant.getId());
    }

    /**
     * Erases the data subject and reports what that cost, in one all-or-nothing transaction.
     *
     * <p>Every {@code GRANTED} consent is revoked with a {@code CONSENT_REVOKED} event appended,
     * every participant link is removed, the display attributes are cleared, and the global
     * identifier is replaced by a fresh opaque pseudonym. The consent and event rows themselves
     * survive: they are the evidence of what was authorised while it was authorised, which is why
     * {@code consents.user_id} is {@code ON DELETE RESTRICT}. What dies is the link between those
     * rows and the person.
     *
     * <p>{@code @Transactional} is right here and nowhere else in this class. Erasure must be
     * atomic - a half-erased subject is worse than an un-erased one - and unlike the registration
     * writes it contains no insert-and-catch to recover from: the pseudonym is a fresh UUIDv7 that
     * will not collide, so no constraint violation is expected to be caught inside the transaction.
     *
     * <p>The returned {@link User} instance is mutated in place; callers holding the pre-erasure
     * row see the erased state afterwards.
     */
    @Transactional
    public ErasureResult erase(User user) {
        UUID userId = user.getId();
        List<Consent> granted = consents.findByUserIdAndStatus(userId, ConsentStatus.GRANTED);
        for (Consent consent : granted) {
            consent.updateStatus(ConsentStatus.REVOKED);
            consents.update(consent);
            consentEvents.save(
                    new ConsentEvent(
                            consent.getId(),
                            ConsentEventState.CONSENT_REVOKED,
                            ERASURE_ACTOR,
                            Map.of(ERASURE_DETAIL_KEY, ERASURE_DETAIL_REASON)));
        }
        long linksRemoved = links.deleteByIdUserId(userId);

        String pseudonym = ERASED_IDENTIFIER_PREFIX + UuidGenerator.uuidV7();
        user.setIdentifier(pseudonym);
        user.setEmail(null);
        user.setFirstName(null);
        user.setLastName(null);
        users.update(user);

        LOG.info(
                "Erased user {}: revoked {} consent(s), removed {} link(s)",
                userId,
                granted.size(),
                linksRemoved);
        return new ErasureResult(pseudonym, granted.size(), Math.toIntExact(linksRemoved));
    }

    /**
     * The caller's own link to the user an identifier names; absence either way is a 404.
     *
     * <p>Public because a {@code PATCH} that mentions no property still has to answer with the link
     * - and still has to 404 when the caller holds none - without writing anything.
     */
    public UserParticipant linkFor(Participant participant, String identifier) {
        return users.findByIdentifier(identifier)
                .flatMap(
                        user ->
                                links.findById(
                                        new UserParticipantId(user.getId(), participant.getId())))
                .orElseThrow(() -> new NotFoundException(NO_LINK_DETAIL));
    }

    /** Whether the participant is named on either side of the consent. */
    private static boolean isParty(Consent consent, Participant participant) {
        return participant.getId().equals(consent.getProviderId())
                || participant.getId().equals(consent.getConsumerId());
    }

    /**
     * One entry, isolated: whatever goes wrong here is reported and never reaches the next one.
     *
     * <p>The per-entry shape checks live here rather than on the schema precisely so that one
     * unusable entry costs only itself; see {@code BulkUserRegistrationEntry.yaml}.
     */
    private BulkEntryResult apply(Participant participant, UserRegistration registration) {
        String identifier = registration.identifier();
        if (identifier == null || identifier.isBlank()) {
            return BulkEntryResult.rejected(identifier, BLANK_IDENTIFIER_DETAIL);
        }
        String email = registration.email();
        if (email != null && !EMAIL_SHAPE.matcher(email).matches()) {
            return BulkEntryResult.rejected(identifier, MALFORMED_EMAIL_DETAIL);
        }
        try {
            return BulkEntryResult.applied(
                    identifier, registerForParticipant(participant, registration).outcome());
        } catch (ApiException e) {
            return BulkEntryResult.rejected(identifier, publishable(e));
        } catch (RuntimeException e) {
            LOG.warn(
                    "Entry {} of a bulk registration for participant {} failed",
                    identifier,
                    participant.getIdentifier(),
                    e);
            return BulkEntryResult.rejected(identifier, UNREGISTERABLE_ENTRY_DETAIL);
        }
    }

    /**
     * An entry's reason, under the same rule {@code ApiExceptionHandler} applies to a problem
     * detail: a 4xx message reaches the caller verbatim, a 5xx one is logged and replaced.
     */
    private static String publishable(ApiException e) {
        if (e.problemType().status().getCode() >= ProblemType.LOWEST_SERVER_ERROR_STATUS) {
            LOG.warn("A bulk registration entry failed with a server-error problem type", e);
            return UNREGISTERABLE_ENTRY_DETAIL;
        }
        return e.getMessage();
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
    private boolean link(User user, Participant participant, @Nullable String supplied) {
        String localIdentifier = blankToNull(supplied);
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

    /**
     * A blank local identifier is no local identifier: it is stored as {@code null}.
     *
     * <p>Without this the column would hold two encodings of "this participant has none" - SQL
     * {@code NULL} and the empty string - which no read path distinguishes and every one has to
     * handle.
     */
    @Nullable
    private static String blankToNull(@Nullable String localIdentifier) {
        return localIdentifier == null || localIdentifier.isBlank() ? null : localIdentifier;
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
