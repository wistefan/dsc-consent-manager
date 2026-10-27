package com.seamware.consentmanager.service;

import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEventTrigger;
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
import com.seamware.consentmanager.observability.FailureSummary;
import com.seamware.consentmanager.observability.LogPseudonymizer;
import com.seamware.consentmanager.repository.ConsentEventRepository;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.IssuedReceiptRepository;
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
 * Owns the {@code users} row and its participant links: JIT provisioning, create-or-link
 * registration, scoped lookup and search. Blocks on JDBC.
 *
 * <p>Registration is deliberately not {@code @Transactional}: a concurrent first caller is resolved
 * by catching the unique violation and re-reading, which PostgreSQL forbids inside a transaction.
 * {@link #registerForParticipant} is re-entrant rather than atomic, so a failed link insert leaves
 * the just-created user (with its write-on-create attributes) behind, and a retry reports {@link
 * RegistrationOutcome#LINKED}. {@link #erase} is the one transactional write.
 */
@Singleton
public class UserService {

    private static final Logger LOG = LoggerFactory.getLogger(UserService.class);

    private static final String BULK_ENTRY_FAILED =
            "Entry {} of a bulk registration for participant {} failed: {}";

    private static final String BULK_ENTRY_SERVER_ERROR =
            "A bulk registration entry failed with server-error problem type {}: {}";

    /** Why a search naming no criterion is refused; published as the problem detail. */
    private static final String EMPTY_SEARCH_DETAIL =
            "A user search must name at least one of identifier, email or participantIdentifier.";

    /** Why a bulk entry naming no identifier is refused; published as that entry's reason. */
    private static final String BLANK_IDENTIFIER_DETAIL =
            "A registration must name a non-blank identifier.";

    /** Why a bulk entry carrying an unusable address is refused; published as its reason. */
    private static final String MALFORMED_EMAIL_DETAIL =
            "The email address is not a valid address.";

    /**
     * Bulk entry address shape, checked here so one dirty address cannot fail the whole batch. As
     * permissive as {@code @Email}, so it accepts what single registration accepts.
     */
    private static final Pattern EMAIL_SHAPE = Pattern.compile("[^\\s@]+@[^\\s@]+");

    /** Replaces an unexpected entry failure's message, which may name a column or constraint. */
    private static final String UNREGISTERABLE_ENTRY_DETAIL =
            "The entry could not be registered. Retrying it on its own will report why.";

    /** Why an over-long batch is refused whole; formatted with the sent size and the maximum. */
    private static final String OVERSIZED_BATCH_DETAIL =
            "A bulk registration carried %d entries, more than the %d this deployment permits.";

    /**
     * Published alike for an unheld link and an unknown identifier, so a participant without a link
     * cannot learn that the identifier is registered.
     */
    private static final String NO_LINK_DETAIL =
            "This participant holds no link to a user under this identifier.";

    /** Why an unlink is refused while the caller is still party to a granted consent. */
    private static final String ACTIVE_CONSENT_DETAIL =
            "This participant is still party to %d granted consent(s) for this user. "
                    + "Revoke them before unlinking.";

    /**
     * Prefix of an erased record's identifier; the suffix is a fresh UUIDv7, never a hash, which
     * enumeration could reverse. See ADR-0007.
     */
    private static final String ERASED_IDENTIFIER_PREFIX = "urn:consent-manager:erased:";

    /** Where an erasure moves each open status: grant revoked, unanswered offer terminated. */
    private static final Map<ConsentStatus, ConsentStatus> ERASURE_CLOSINGS =
            Map.of(
                    ConsentStatus.GRANTED, ConsentStatus.REVOKED,
                    ConsentStatus.PENDING, ConsentStatus.TERMINATED,
                    ConsentStatus.DRAFT, ConsentStatus.TERMINATED);

    /** Attributed to the service rather than to a person, who no longer exists by then. */
    private static final String ERASURE_ACTOR = "consent-manager";

    /** Marks erasure trail entries as automated: no one answered the consent itself. */
    private static final ConsentEventTrigger ERASURE_TRIGGER = ConsentEventTrigger.SYSTEM;

    /** Says, in the retained audit trail, why a consent was revoked without naming anyone. */
    private static final String ERASURE_DETAIL_KEY = "reason";

    private static final String ERASURE_DETAIL_REASON = "erasure-requested-by-data-subject";

    private final UserRepository users;

    private final UserParticipantRepository links;

    private final ParticipantRepository participants;

    private final ConsentRepository consents;

    private final ConsentEventRepository consentEvents;

    private final IssuedReceiptRepository issuedReceipts;

    private final ConsentFactory consentFactory;

    private final ErasureVerifier erasureVerifier;

    private final LogPseudonymizer pseudonymizer;

    private final ConsentManagerConfiguration.Users configuration;

    public UserService(
            UserRepository users,
            UserParticipantRepository links,
            ParticipantRepository participants,
            ConsentRepository consents,
            ConsentEventRepository consentEvents,
            IssuedReceiptRepository issuedReceipts,
            ConsentFactory consentFactory,
            ErasureVerifier erasureVerifier,
            LogPseudonymizer pseudonymizer,
            ConsentManagerConfiguration.Users configuration) {
        this.users = users;
        this.links = links;
        this.participants = participants;
        this.consents = consents;
        this.consentEvents = consentEvents;
        this.issuedReceipts = issuedReceipts;
        this.consentFactory = consentFactory;
        this.erasureVerifier = erasureVerifier;
        this.pseudonymizer = pseudonymizer;
        this.configuration = configuration;
    }

    /**
     * The principal's user row, inserted on first sight, with asserted display claims refreshed.
     * {@code uq_users_identifier} settles concurrent first requests; {@link
     * ProvisionedUser#created()} drives the {@code 201} of {@code POST /users/register}.
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
            // Lost the insert race: adopt the winner's row; no row means it was not the race.
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
     * The user an identifier names, as far as this caller may see it. Empty alike for nobody and
     * for a user out of scope, so the route never confirms existence.
     */
    public Optional<User> lookup(String identifier, CallerScope scope) {
        return search(new UserSearchCriteria(identifier, null, null), scope).stream().findFirst();
    }

    /**
     * Users matching every criterion, intersected with a {@code PARTICIPANT} caller's own links. No
     * criterion at all is a {@link BadRequestException}. Sorted by identifier and capped at {@code
     * consent-manager.users.search-max-results}, without pagination.
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
     * Participants every match must be linked to, caller's own first; empty when none can match. An
     * unregistered {@code participantIdentifier} yields empty, never an error revealing it.
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
     * The rows the cheapest criterion narrows to; the link fall-through implies a non-empty {@code
     * participantIds}, since empty criteria were already refused.
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

    /** Candidate rows, plus the participant whose link set produced them and needs no re-check. */
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

    /** Whether the user is linked to every still-required participant; one index probe each. */
    private boolean isLinkedToAll(User user, List<UUID> participantIds) {
        return participantIds.stream()
                .allMatch(id -> links.existsByIdUserIdAndIdParticipantId(user.getId(), id));
    }

    /**
     * The participant identifiers a caller may be told about. A {@code PARTICIPANT} sees only its
     * own link, never the user's other affiliations; a dataspace-wide caller sees the full list.
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
     * Creates or adopts the user by global identifier alone and links it to the participant.
     * Attributes are write-on-create; only the link's {@code localIdentifier} may change later.
     * Deliberately not transactional; see the class note.
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
     * Applies {@link #registerForParticipant} to each entry independently, one result per entry in
     * order; an unusable entry is {@link RegistrationOutcome#REJECTED}, not a failed batch. A batch
     * over {@code consent-manager.users.bulk-max-size} is a {@link BadRequestException}.
     *
     * <p>Deliberately not transactional, not even per entry: a transaction would break the
     * catch-and-re-read, turning a lost identifier race into {@code REJECTED} instead of {@code
     * LINKED}. Autocommit keeps one entry's constraint violation out of the next.
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
     * Writes the caller's link's local identifier and returns the link as re-read. {@code null}
     * clears it (the PATCH explicit-null arm); an omitted property goes to {@link #linkFor}
     * instead. Never touches the {@code users} row.
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
     * Removes the caller's link to a user, leaving the user, its consents and other links intact. A
     * {@link ConflictException} while a {@code GRANTED} consent names the caller as a party.
     *
     * <p>The check is advisory: a grant landing between the select and the delete slips through, as
     * nothing serialises them. TODO(consent-lifecycle): granting must also refuse an unlinked
     * participant; see docs/user-identifiers.md.
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
     * Erases the data subject in one all-or-nothing transaction and reports what it closed. Mutates
     * {@code user} in place when pseudonymised. See ADR-0007.
     *
     * <p>{@code @Transactional} only here: erasure must be atomic, and unlike registration it has
     * no insert-and-catch, since the UUIDv7 pseudonym cannot collide.
     */
    @Transactional
    public ErasureResult erase(User user) {
        UUID userId = user.getId();
        List<ConsentMove> closed = close(userId);
        // A signed receipt names the subject and cannot be pseudonymised without breaking it.
        issuedReceipts.deleteByUserId(userId);
        long linksRemoved = links.deleteByIdUserId(userId);
        String pseudonym = pseudonymiseOrDelete(user);

        ErasureResult result = new ErasureResult(pseudonym, closed, Math.toIntExact(linksRemoved));
        LOG.info(
                "Erased user {}: revoked {}, terminated {}, unlinked {}, record {}",
                userId,
                result.consentsRevoked(),
                result.consentsTerminated(),
                linksRemoved,
                pseudonym == null ? "deleted" : "pseudonymised");
        return result;
    }

    /**
     * Closes every open consent per {@link #ERASURE_CLOSINGS}, returning each move with its held
     * status. One locked read, so a concurrently granted request is revoked, not terminated stale.
     */
    private List<ConsentMove> close(UUID userId) {
        List<ConsentMove> closed =
                consents
                        .findByUserIdAndStatusInForUpdate(userId, ERASURE_CLOSINGS.keySet())
                        .stream()
                        .map(
                                consent -> {
                                    ConsentStatus held = consent.getStatus();
                                    return new ConsentMove(
                                            held, terminate(consent, ERASURE_CLOSINGS.get(held)));
                                })
                        .toList();
        if (!closed.isEmpty()) {
            consents.updateAll(closed.stream().map(move -> move.recorded().consent()).toList());
            consentEvents.saveAll(closed.stream().map(move -> move.recorded().event()).toList());
        }
        return closed;
    }

    /** The consent moved to {@code to}, with a trail entry attributing it to erasure. */
    private RecordedConsent terminate(Consent consent, ConsentStatus to) {
        return consentFactory.transition(
                consent,
                to,
                ERASURE_ACTOR,
                ERASURE_TRIGGER,
                Map.of(ERASURE_DETAIL_KEY, ERASURE_DETAIL_REASON));
    }

    /**
     * Deletes the record, or, when consents still name it ({@code ON DELETE RESTRICT}), stores an
     * {@link ErasureVerifier} value, pseudonymises it and returns the pseudonym. See ADR-0007.
     */
    @Nullable
    private String pseudonymiseOrDelete(User user) {
        if (!consents.existsByUserId(user.getId())) {
            users.delete(user);
            return null;
        }
        String pseudonym = ERASED_IDENTIFIER_PREFIX + UuidGenerator.uuidV7();
        user.setErasureVerifier(erasureVerifier.verifierFor(user.getIdentifier()));
        user.setIdentifier(pseudonym);
        user.setEmail(null);
        user.setFirstName(null);
        user.setLastName(null);
        users.update(user);
        return pseudonym;
    }

    /**
     * The caller's own link to the user an identifier names; absence either way is a 404. Public so
     * a {@code PATCH} naming no property can answer without writing.
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

    /** One entry, isolated: any failure is reported as its result and never reaches the next. */
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
            // The identifier is personal data; above DEBUG log only its pseudonym and class chain.
            LOG.warn(
                    BULK_ENTRY_FAILED,
                    pseudonymizer.pseudonym(identifier),
                    participant.getIdentifier(),
                    FailureSummary.of(e));
            LOG.debug(
                    BULK_ENTRY_FAILED, identifier, participant.getIdentifier(), e.getMessage(), e);
            return BulkEntryResult.rejected(identifier, UNREGISTERABLE_ENTRY_DETAIL);
        }
    }

    /** An entry's reason: a 4xx message verbatim, a 5xx one logged and replaced. */
    private static String publishable(ApiException e) {
        if (e.problemType().status().getCode() >= ProblemType.LOWEST_SERVER_ERROR_STATUS) {
            LOG.warn(BULK_ENTRY_SERVER_ERROR, e.problemType(), FailureSummary.of(e));
            LOG.debug(BULK_ENTRY_SERVER_ERROR, e.problemType(), e.getMessage(), e);
            return UNREGISTERABLE_ENTRY_DETAIL;
        }
        return e.getMessage();
    }

    /**
     * The user the identifier names, inserted on first sight; {@code uq_users_identifier}, not the
     * leading read, settles existence.
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
            // Lost the insert race: adopt the winner's row and leave its attributes be.
            return users.findByIdentifier(registration.identifier())
                    .map(UserService::adopted)
                    .orElseThrow(() -> e);
        }
    }

    /**
     * Links the user to the participant, reporting whether this call inserted the link. A lost race
     * on {@code pk_user_participants} re-reads and reports an existing link.
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

    /** A blank local identifier is stored as {@code null}, so "none" has one encoding. */
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
