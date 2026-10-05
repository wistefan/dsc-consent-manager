package com.seamware.consentmanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.domain.UserParticipant;
import com.seamware.consentmanager.domain.UserParticipant.UserParticipantId;
import com.seamware.consentmanager.error.BadRequestException;
import com.seamware.consentmanager.error.ForbiddenException;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.UserParticipantRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.security.Role;
import com.seamware.consentmanager.security.UserPrincipal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.exceptions.DataAccessException;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.Sort;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Covers the rule {@link UserService} implements, plus what {@link UserServiceIT} cannot force
 * deterministically: the duplicate-key branch of either write, both when the winner's row is there
 * to adopt and when the failure was never the race.
 */
@DisplayName("User service")
class UserServiceTest {

    /** Identifier every token and registration in this test carries. */
    private static final String IDENTIFIER = "urn:test:user:ada";

    /** Issuer every token in this test carries; provisioning does not read it. */
    private static final String ISSUER = "https://idp.example/realms/test";

    /** Display claims of the row already in the database. */
    private static final String STORED_EMAIL = "ada@participant.example";

    private static final String STORED_GIVEN_NAME = "Ada";

    private static final String STORED_FAMILY_NAME = "Lovelace";

    /** Attributes a registration supplies, which must never land on a pre-existing row. */
    private static final String REGISTERED_EMAIL = "ada@other.example";

    private static final String REGISTERED_FIRST_NAME = "Augusta";

    private static final String REGISTERED_LAST_NAME = "King";

    /** Local identifier a participant already holds against an existing link. */
    private static final String STORED_LOCAL_IDENTIFIER = "customer-1";

    /** Local identifier a later registration supplies instead. */
    private static final String CHANGED_LOCAL_IDENTIFIER = "customer-2";

    /** A cap below the number of users the seeded directory matches, so truncation shows. */
    private static final int SEARCH_CAP = 2;

    private static final String PARTICIPANT_ALPHA = "urn:test:participant:alpha";

    private static final String PARTICIPANT_BETA = "urn:test:participant:beta";

    /** Seeded user identifiers, chosen so their natural order is a, b, c. */
    private static final String USER_ON_ALPHA = "urn:test:user:a";

    private static final String USER_ON_BOTH = "urn:test:user:b";

    private static final String USER_ON_BETA = "urn:test:user:c";

    /** A caller reading across participants; it carries no participant row of its own. */
    private static final CallerScope CATALOG = new CallerScope(Role.CATALOG, null);

    private final StubUserRepository users = new StubUserRepository();

    private final StubUserParticipantRepository links = new StubUserParticipantRepository();

    private final StubParticipantRepository participants = new StubParticipantRepository();

    private final ConsentManagerConfiguration.Users limits =
            new ConsentManagerConfiguration.Users();

    private final UserService service = new UserService(users, links, participants, limits);

    /** An identifier no row exists for is inserted from the claims, and reported as created. */
    @Test
    @DisplayName("provisioning inserts a row built from the claims on first sight")
    void insertsOnFirstSight() {
        ProvisionedUser provisioned =
                service.provisionFromToken(principal(STORED_EMAIL, "Ada", "Lovelace"));

        assertThat(users.saved).hasSize(1);
        assertThat(users.updated).isEmpty();
        assertThat(provisioned.created()).isTrue();
        assertThat(provisioned.user().getIdentifier()).isEqualTo(IDENTIFIER);
        assertThat(provisioned.user().getEmail()).isEqualTo(STORED_EMAIL);
        assertThat(provisioned.user().getFirstName()).isEqualTo("Ada");
        assertThat(provisioned.user().getLastName()).isEqualTo("Lovelace");
    }

    /** Only the call whose own insert landed reports {@code created}; a later one adopts. */
    @Test
    @DisplayName("provisioning reports created only for the call that inserted the row")
    void reportsCreatedOnlyOnce() {
        UserPrincipal principal = principal(STORED_EMAIL, "Ada", "Lovelace");

        ProvisionedUser first = service.provisionFromToken(principal);
        ProvisionedUser second = service.provisionFromToken(principal);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.user()).isSameAs(first.user());
    }

    /**
     * The row a concurrent request committed between this one's read and its insert is adopted, and
     * the claims this token carries are written onto it.
     */
    @Test
    @DisplayName("provisioning adopts and refreshes the row a concurrent request committed")
    void adoptsTheWinnersRow() {
        User winner = stored();
        users.commitDuringSave(winner, new DataAccessException("duplicate key"));

        ProvisionedUser provisioned =
                service.provisionFromToken(
                        principal("ada.lovelace@participant.example", "Ada", "Lovelace"));

        assertThat(provisioned.created()).isFalse();
        assertThat(provisioned.user()).isSameAs(winner);
        assertThat(provisioned.user().getEmail()).isEqualTo("ada.lovelace@participant.example");
        assertThat(users.updated).containsExactly(winner);
    }

    /** A save failure that was not the race is not swallowed, and not turned into another type. */
    @Test
    @DisplayName("provisioning rethrows a save failure that left no row behind")
    void rethrowsAFailureThatWasNotTheRace() {
        DataAccessException failure = new DataAccessException("connection reset");
        users.commitDuringSave(null, failure);

        assertThatThrownBy(
                        () ->
                                service.provisionFromToken(
                                        principal(STORED_EMAIL, "Ada", "Lovelace")))
                .isSameAs(failure);
        assertThat(users.updated).isEmpty();
    }

    /**
     * Only a claim the token actually asserts, and that differs, causes a write.
     *
     * @param description what the token carries, for the test name
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("refreshCases")
    @DisplayName("provisioning writes the row only when an asserted claim differs")
    void refreshesOnlyChangedClaims(
            String description,
            @Nullable String email,
            @Nullable String givenName,
            @Nullable String familyName,
            boolean expectWrite,
            String expectedEmail,
            String expectedGivenName,
            String expectedFamilyName) {
        User existing = users.put(stored());

        ProvisionedUser provisioned =
                service.provisionFromToken(principal(email, givenName, familyName));

        assertThat(provisioned.created()).isFalse();
        assertThat(provisioned.user()).isSameAs(existing);
        assertThat(users.saved).isEmpty();
        assertThat(users.updated).hasSize(expectWrite ? 1 : 0);
        assertThat(provisioned.user().getEmail()).isEqualTo(expectedEmail);
        assertThat(provisioned.user().getFirstName()).isEqualTo(expectedGivenName);
        assertThat(provisioned.user().getLastName()).isEqualTo(expectedFamilyName);
    }

    /** One claim set per case, against the stored {@link #stored()} row. */
    static Stream<Arguments> refreshCases() {
        return Stream.of(
                Arguments.of(
                        "the same claims again",
                        STORED_EMAIL,
                        STORED_GIVEN_NAME,
                        STORED_FAMILY_NAME,
                        false,
                        STORED_EMAIL,
                        STORED_GIVEN_NAME,
                        STORED_FAMILY_NAME),
                Arguments.of(
                        "a changed email",
                        "ada.lovelace@participant.example",
                        STORED_GIVEN_NAME,
                        STORED_FAMILY_NAME,
                        true,
                        "ada.lovelace@participant.example",
                        STORED_GIVEN_NAME,
                        STORED_FAMILY_NAME),
                Arguments.of(
                        "a changed given name",
                        STORED_EMAIL,
                        "Augusta",
                        STORED_FAMILY_NAME,
                        true,
                        STORED_EMAIL,
                        "Augusta",
                        STORED_FAMILY_NAME),
                Arguments.of(
                        "a changed family name",
                        STORED_EMAIL,
                        STORED_GIVEN_NAME,
                        "King",
                        true,
                        STORED_EMAIL,
                        STORED_GIVEN_NAME,
                        "King"),
                Arguments.of(
                        "no profile claims at all",
                        null,
                        null,
                        null,
                        false,
                        STORED_EMAIL,
                        STORED_GIVEN_NAME,
                        STORED_FAMILY_NAME),
                Arguments.of(
                        "a changed email and no name claims",
                        "ada.lovelace@participant.example",
                        null,
                        null,
                        true,
                        "ada.lovelace@participant.example",
                        STORED_GIVEN_NAME,
                        STORED_FAMILY_NAME));
    }

    /**
     * The four states a registration can find, each settling on one outcome and never on a second
     * user row. A pre-existing row keeps the attributes it was created with, whoever asserted them.
     *
     * @param description the entry state, for the test name
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("registrationCases")
    @DisplayName("registration creates, links or adopts according to the entry state")
    void registersAccordingToTheEntryState(
            String description,
            boolean userExists,
            boolean linkExists,
            @Nullable String requestedLocalIdentifier,
            RegistrationOutcome expectedOutcome,
            @Nullable String expectedLocalIdentifier) {
        Participant participant = participants.put(participant());
        if (userExists) {
            User existing = users.put(stored());
            if (linkExists) {
                links.put(
                        new UserParticipant(
                                existing.getId(), participant.getId(), STORED_LOCAL_IDENTIFIER));
            }
        }

        RegistrationResult result =
                service.registerForParticipant(participant, registration(requestedLocalIdentifier));

        assertThat(result.outcome()).isEqualTo(expectedOutcome);
        assertThat(users.rows()).hasSize(1);
        assertThat(links.rows()).hasSize(1);
        assertThat(links.find(result.user().getId(), participant.getId()).getLocalIdentifier())
                .isEqualTo(expectedLocalIdentifier);
        assertThat(result.user().getIdentifier()).isEqualTo(IDENTIFIER);
        if (userExists) {
            assertThat(result.user().getEmail()).isEqualTo(STORED_EMAIL);
            assertThat(result.user().getFirstName()).isEqualTo(STORED_GIVEN_NAME);
            assertThat(result.user().getLastName()).isEqualTo(STORED_FAMILY_NAME);
            assertThat(users.saved).isEmpty();
        } else {
            assertThat(result.user().getEmail()).isEqualTo(REGISTERED_EMAIL);
            assertThat(result.user().getFirstName()).isEqualTo(REGISTERED_FIRST_NAME);
            assertThat(result.user().getLastName()).isEqualTo(REGISTERED_LAST_NAME);
        }
    }

    /** One entry state per case, all for the same identifier and participant. */
    static Stream<Arguments> registrationCases() {
        return Stream.of(
                Arguments.of(
                        "an unknown identifier",
                        false,
                        false,
                        STORED_LOCAL_IDENTIFIER,
                        RegistrationOutcome.CREATED,
                        STORED_LOCAL_IDENTIFIER),
                Arguments.of(
                        "a known identifier this participant is not linked to",
                        true,
                        false,
                        STORED_LOCAL_IDENTIFIER,
                        RegistrationOutcome.LINKED,
                        STORED_LOCAL_IDENTIFIER),
                Arguments.of(
                        "an identifier already linked to this participant",
                        true,
                        true,
                        null,
                        RegistrationOutcome.ALREADY_LINKED,
                        STORED_LOCAL_IDENTIFIER),
                Arguments.of(
                        "an existing link and a changed local identifier",
                        true,
                        true,
                        CHANGED_LOCAL_IDENTIFIER,
                        RegistrationOutcome.ALREADY_LINKED,
                        CHANGED_LOCAL_IDENTIFIER));
    }

    /** Losing the user insert still links, because existence is the constraint's answer. */
    @Test
    @DisplayName("registration adopts the user row a concurrent registration committed")
    void registrationAdoptsTheWinnersUser() {
        Participant participant = participants.put(participant());
        User winner = stored();
        users.commitDuringSave(winner, new DataAccessException("duplicate key"));

        RegistrationResult result =
                service.registerForParticipant(participant, registration(STORED_LOCAL_IDENTIFIER));

        assertThat(result.outcome()).isEqualTo(RegistrationOutcome.LINKED);
        assertThat(result.user()).isSameAs(winner);
        assertThat(result.user().getEmail()).isEqualTo(STORED_EMAIL);
        assertThat(users.updated).isEmpty();
    }

    /** Losing the link insert is reported as an existing link, and still applies the local id. */
    @Test
    @DisplayName("registration adopts the link a concurrent registration committed")
    void registrationAdoptsTheWinnersLink() {
        Participant participant = participants.put(participant());
        User existing = users.put(stored());
        links.commitDuringSave(
                new UserParticipant(existing.getId(), participant.getId(), STORED_LOCAL_IDENTIFIER),
                new DataAccessException("duplicate key"));

        RegistrationResult result =
                service.registerForParticipant(participant, registration(CHANGED_LOCAL_IDENTIFIER));

        assertThat(result.outcome()).isEqualTo(RegistrationOutcome.ALREADY_LINKED);
        assertThat(links.find(existing.getId(), participant.getId()).getLocalIdentifier())
                .isEqualTo(CHANGED_LOCAL_IDENTIFIER);
    }

    /** A link failure that was not the race is not swallowed either. */
    @Test
    @DisplayName("registration rethrows a link failure that left no link behind")
    void registrationRethrowsALinkFailureThatWasNotTheRace() {
        Participant participant = participants.put(participant());
        users.put(stored());
        DataAccessException failure = new DataAccessException("connection reset");
        links.commitDuringSave(null, failure);

        assertThatThrownBy(
                        () ->
                                service.registerForParticipant(
                                        participant, registration(STORED_LOCAL_IDENTIFIER)))
                .isSameAs(failure);
    }

    /** The links of a user resolve to participant identifiers, sorted so responses are stable. */
    @Test
    @DisplayName("a user's participant identifiers come back sorted")
    void participantIdentifiersAreSorted() {
        User user = users.put(stored());
        Participant beta = participants.put(participant(PARTICIPANT_BETA));
        Participant alpha = participants.put(participant(PARTICIPANT_ALPHA));
        links.put(new UserParticipant(user.getId(), beta.getId(), null));
        links.put(new UserParticipant(user.getId(), alpha.getId(), null));

        assertThat(service.participantIdentifiersFor(user))
                .containsExactly(PARTICIPANT_ALPHA, PARTICIPANT_BETA);
    }

    /** A user with no links needs no participant query at all. */
    @Test
    @DisplayName("a user with no links has no participant identifiers")
    void participantIdentifiersAreEmptyWithoutLinks() {
        assertThat(service.participantIdentifiersFor(users.put(stored()))).isEmpty();
    }

    /** A search naming nothing is refused outright rather than answered with the whole table. */
    @ParameterizedTest(name = "criteria {0} are no criteria at all")
    @MethodSource("emptyCriteria")
    @DisplayName("a search naming no criterion is rejected")
    void rejectsASearchNamingNothing(UserSearchCriteria criteria) {
        assertThatThrownBy(() -> service.search(criteria, CATALOG))
                .isInstanceOf(BadRequestException.class);
    }

    /** Email matching is case-insensitive and may name several users; scope decides which. */
    @ParameterizedTest(name = "{0} searching the shared address sees {1}")
    @MethodSource("emailSearches")
    @DisplayName("an email search is case-insensitive and confined to the caller's scope")
    void emailSearchIsScoped(Caller caller, List<String> expected) {
        Directory directory = directory();

        assertThat(identifiers(service.search(byEmail(), caller.scope(directory))))
                .containsExactlyElementsOf(expected);
    }

    /**
     * A participant naming somebody else's participant intersects rather than replaces its scope.
     */
    @ParameterizedTest(name = "{0} naming beta sees {1}")
    @MethodSource("participantSearches")
    @DisplayName("a participant criterion narrows the caller's scope, never widens it")
    void participantCriterionOnlyNarrows(Caller caller, List<String> expected) {
        Directory directory = directory();
        UserSearchCriteria criteria = new UserSearchCriteria(null, null, PARTICIPANT_BETA);

        assertThat(identifiers(service.search(criteria, caller.scope(directory))))
                .containsExactlyElementsOf(expected);
    }

    /**
     * Criteria combine conjunctively, which only a case naming two of them at once can prove.
     *
     * <p>{@code identifier} short-circuits the candidate query, so a regression that dropped the
     * email filter afterwards would still satisfy every single-criterion case.
     */
    @ParameterizedTest(name = "{0} together with {1} finds {2}")
    @MethodSource("conjunctiveSearches")
    @DisplayName("criteria narrow one another rather than being taken in turn")
    void criteriaCombineConjunctively(
            @Nullable String identifier,
            @Nullable String email,
            @Nullable String participantIdentifier,
            List<String> expected) {
        Directory directory = directory();
        UserSearchCriteria criteria =
                new UserSearchCriteria(identifier, email, participantIdentifier);

        assertThat(identifiers(service.search(criteria, Caller.ALPHA.scope(directory))))
                .containsExactlyElementsOf(expected);
    }

    /** The cap is what keeps an unpaginated listing from serialising a whole user base. */
    @Test
    @DisplayName("a search returns at most the configured number of users, lowest identifier first")
    void aSearchIsCappedAtTheConfiguredMaximum() {
        directory();
        limits.setSearchMaxResults(SEARCH_CAP);

        assertThat(identifiers(service.search(byEmail(), CATALOG)))
                .containsExactly(USER_ON_ALPHA, USER_ON_BOTH);
    }

    /**
     * A participant learns about its own link and no other, so a search cannot be used to discover
     * which competitors a data subject is also registered with.
     */
    @ParameterizedTest(name = "{0} reading the shared user sees {1}")
    @MethodSource("disclosedLinks")
    @DisplayName("the participant links a caller is told about are scoped to that caller")
    void participantLinksAreScopedToTheCaller(Caller caller, List<String> expected) {
        Directory directory = directory();
        User onBoth = users.findByIdentifier(USER_ON_BOTH).orElseThrow();

        assertThat(service.participantIdentifiersFor(onBoth, caller.scope(directory)))
                .containsExactlyElementsOf(expected);
    }

    static Stream<Arguments> conjunctiveSearches() {
        return Stream.of(
                Arguments.of(USER_ON_ALPHA, STORED_EMAIL, null, List.of(USER_ON_ALPHA)),
                Arguments.of(USER_ON_ALPHA, REGISTERED_EMAIL, null, List.of()),
                Arguments.of(USER_ON_ALPHA, null, PARTICIPANT_BETA, List.of()),
                Arguments.of(USER_ON_BOTH, STORED_EMAIL, PARTICIPANT_BETA, List.of(USER_ON_BOTH)),
                Arguments.of(null, STORED_EMAIL, PARTICIPANT_BETA, List.of(USER_ON_BOTH)));
    }

    static Stream<Arguments> disclosedLinks() {
        return Stream.of(
                Arguments.of(Caller.ALPHA, List.of(PARTICIPANT_ALPHA)),
                Arguments.of(Caller.CATALOG, List.of(PARTICIPANT_ALPHA, PARTICIPANT_BETA)));
    }

    /** Whether an identifier names a registered participant is not a search's to reveal. */
    @Test
    @DisplayName("a search for an unregistered participant finds nobody rather than failing")
    void anUnregisteredParticipantFindsNobody() {
        directory();
        UserSearchCriteria criteria =
                new UserSearchCriteria(null, null, "urn:test:participant:unregistered");

        assertThat(service.search(criteria, CATALOG)).isEmpty();
    }

    /** Lookup answers empty for an unscoped user exactly as for an absent one. */
    @ParameterizedTest(name = "{0} looking up {1} finds it: {2}")
    @MethodSource("lookups")
    @DisplayName("a lookup sees only what its caller is entitled to")
    void lookupIsScoped(Caller caller, String identifier, boolean found) {
        Directory directory = directory();

        assertThat(service.lookup(identifier, caller.scope(directory)).isPresent())
                .isEqualTo(found);
    }

    /** The scoped reads are closed to the {@code USER} role even if a route were widened to it. */
    @Test
    @DisplayName("a USER principal has no search scope at all")
    void aUserPrincipalHasNoScope() {
        assertThatThrownBy(() -> CallerScope.of(principal(STORED_EMAIL, "Ada", "Lovelace")))
                .isInstanceOf(ForbiddenException.class);
    }

    static Stream<Arguments> emptyCriteria() {
        return Stream.of(
                Arguments.of(new UserSearchCriteria(null, null, null)),
                Arguments.of(new UserSearchCriteria(" ", "", "\t")));
    }

    static Stream<Arguments> emailSearches() {
        return Stream.of(
                Arguments.of(Caller.ALPHA, List.of(USER_ON_ALPHA, USER_ON_BOTH)),
                Arguments.of(Caller.CATALOG, List.of(USER_ON_ALPHA, USER_ON_BOTH, USER_ON_BETA)));
    }

    static Stream<Arguments> participantSearches() {
        return Stream.of(
                Arguments.of(Caller.ALPHA, List.of(USER_ON_BOTH)),
                Arguments.of(Caller.CATALOG, List.of(USER_ON_BOTH, USER_ON_BETA)));
    }

    static Stream<Arguments> lookups() {
        return Stream.of(
                Arguments.of(Caller.ALPHA, USER_ON_ALPHA, true),
                Arguments.of(Caller.ALPHA, USER_ON_BOTH, true),
                Arguments.of(Caller.ALPHA, USER_ON_BETA, false),
                Arguments.of(Caller.ALPHA, "urn:test:user:absent", false),
                Arguments.of(Caller.CATALOG, USER_ON_BETA, true),
                Arguments.of(Caller.CATALOG, "urn:test:user:absent", false));
    }

    /** Who is reading, resolved against the seeded directory because scope carries a row. */
    enum Caller {
        /** Acts as the participant the shared user is also linked to. */
        ALPHA {
            @Override
            CallerScope scope(Directory directory) {
                return new CallerScope(Role.PARTICIPANT, directory.alpha());
            }
        },

        /** Reads across participants. */
        CATALOG {
            @Override
            CallerScope scope(Directory directory) {
                return UserServiceTest.CATALOG;
            }
        };

        abstract CallerScope scope(Directory directory);
    }

    /** The participants a seeded directory holds; the users are addressed by identifier. */
    private record Directory(Participant alpha, Participant beta) {}

    /**
     * Two participants and three users: one linked to each alone and one linked to both, their
     * addresses differing in case so a match proves the comparison ignores it.
     */
    private Directory directory() {
        Participant alpha = participants.put(participant(PARTICIPANT_ALPHA));
        Participant beta = participants.put(participant(PARTICIPANT_BETA));
        link(users.put(new User(USER_ON_ALPHA, STORED_EMAIL, null, null)), alpha);
        User onBoth =
                users.put(
                        new User(USER_ON_BOTH, STORED_EMAIL.toUpperCase(Locale.ROOT), null, null));
        link(onBoth, alpha);
        link(onBoth, beta);
        link(users.put(new User(USER_ON_BETA, STORED_EMAIL, null, null)), beta);
        return new Directory(alpha, beta);
    }

    private void link(User user, Participant participant) {
        links.put(new UserParticipant(user.getId(), participant.getId(), null));
    }

    /** The shared address, written in a case no stored row holds. */
    private static UserSearchCriteria byEmail() {
        return new UserSearchCriteria(null, STORED_EMAIL.toUpperCase(Locale.ROOT), null);
    }

    private static List<String> identifiers(List<User> found) {
        return found.stream().map(User::getIdentifier).toList();
    }

    /** A {@code USER} principal carrying the given display claims. */
    private static UserPrincipal principal(
            @Nullable String email, @Nullable String givenName, @Nullable String familyName) {
        return new UserPrincipal(
                ISSUER,
                IDENTIFIER,
                IDENTIFIER,
                email,
                false,
                null,
                givenName,
                familyName,
                null,
                false);
    }

    /** The row a previous request already provisioned. */
    private static User stored() {
        return new User(IDENTIFIER, STORED_EMAIL, STORED_GIVEN_NAME, STORED_FAMILY_NAME);
    }

    /** A registration carrying attributes that differ from every stored one. */
    private static UserRegistration registration(@Nullable String localIdentifier) {
        return new UserRegistration(
                IDENTIFIER,
                localIdentifier,
                REGISTERED_EMAIL,
                REGISTERED_FIRST_NAME,
                REGISTERED_LAST_NAME);
    }

    /** The participant a registration is made for. */
    private static Participant participant() {
        return participant(PARTICIPANT_ALPHA);
    }

    private static Participant participant(String identifier) {
        return new Participant(identifier, "Alpha GmbH", null, null, Map.of(), null);
    }

    /**
     * An in-memory {@link UserRepository} that records what it was asked to write and can be told
     * to fail a save the way the unique constraint does.
     */
    private static final class StubUserRepository extends StubRepository<String, User>
            implements UserRepository {

        private final List<User> saved = new ArrayList<>();

        private final List<User> updated = new ArrayList<>();

        @Override
        String keyOf(User row) {
            return row.getIdentifier();
        }

        @Override
        public Optional<User> findByIdentifier(String identifier) {
            return Optional.ofNullable(rows().get(identifier));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <S extends User> S save(S entity) {
            failIfTold();
            saved.add(entity);
            put(entity);
            return entity;
        }

        @Override
        public <S extends User> S update(S entity) {
            updated.add(entity);
            put(entity);
            return entity;
        }

        @Override
        public List<User> findAllByEmailIgnoreCase(String email) {
            return rows().values().stream()
                    .filter(u -> u.getEmail() != null && u.getEmail().equalsIgnoreCase(email))
                    .toList();
        }

        @Override
        public List<User> findByIdIn(Collection<UUID> ids) {
            return rows().values().stream().filter(u -> ids.contains(u.getId())).toList();
        }

        @Override
        public boolean existsByIdentifier(String identifier) {
            throw unsupported();
        }

        @Override
        public Optional<User> findById(UUID id) {
            throw unsupported();
        }

        @Override
        public boolean existsById(UUID id) {
            throw unsupported();
        }

        @Override
        public void deleteById(UUID id) {
            throw unsupported();
        }
    }

    /** An in-memory {@link UserParticipantRepository} with the same duplicate-key behaviour. */
    private static final class StubUserParticipantRepository
            extends StubRepository<UserParticipantId, UserParticipant>
            implements UserParticipantRepository {

        @Override
        UserParticipantId keyOf(UserParticipant row) {
            return row.getId();
        }

        /** The link between a user and a participant, failing the test when there is none. */
        UserParticipant find(UUID userId, UUID participantId) {
            UserParticipant link = rows().get(new UserParticipantId(userId, participantId));
            assertThat(link).as("the link between %s and %s", userId, participantId).isNotNull();
            return link;
        }

        @Override
        public List<UserParticipant> findByIdUserId(UUID userId) {
            return rows().values().stream().filter(l -> l.getUserId().equals(userId)).toList();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <S extends UserParticipant> S save(S entity) {
            failIfTold();
            put(entity);
            return entity;
        }

        @Override
        public <S extends UserParticipant> S update(S entity) {
            put(entity);
            return entity;
        }

        @Override
        public Optional<UserParticipant> findById(UserParticipantId id) {
            return Optional.ofNullable(rows().get(id));
        }

        @Override
        public boolean existsById(UserParticipantId id) {
            throw unsupported();
        }

        @Override
        public void deleteById(UserParticipantId id) {
            throw unsupported();
        }

        @Override
        public List<UserParticipant> findByIdParticipantId(UUID participantId) {
            return rows().values().stream()
                    .filter(l -> l.getParticipantId().equals(participantId))
                    .toList();
        }

        @Override
        public boolean existsByIdUserIdAndIdParticipantId(UUID userId, UUID participantId) {
            return rows().containsKey(new UserParticipantId(userId, participantId));
        }

        @Override
        public void deleteByIdUserIdAndIdParticipantId(UUID userId, UUID participantId) {
            throw unsupported();
        }
    }

    /** An in-memory {@link ParticipantRepository}; registration only ever reads from it. */
    private static final class StubParticipantRepository extends StubRepository<UUID, Participant>
            implements ParticipantRepository {

        @Override
        UUID keyOf(Participant row) {
            return row.getId();
        }

        @Override
        public List<Participant> findByIdIn(Collection<UUID> ids) {
            return rows().values().stream().filter(p -> ids.contains(p.getId())).toList();
        }

        @Override
        public Optional<Participant> findById(UUID id) {
            return Optional.ofNullable(rows().get(id));
        }

        @Override
        public Optional<Participant> findByIdentifier(String identifier) {
            return rows().values().stream()
                    .filter(p -> p.getIdentifier().equals(identifier))
                    .findFirst();
        }

        @Override
        public boolean existsByIdentifier(String identifier) {
            throw unsupported();
        }

        @Override
        public <S extends Participant> S save(S entity) {
            throw unsupported();
        }

        @Override
        public <S extends Participant> S update(S entity) {
            throw unsupported();
        }

        @Override
        public boolean existsById(UUID id) {
            throw unsupported();
        }

        @Override
        public void deleteById(UUID id) {
            throw unsupported();
        }

        @Override
        public Page<Participant> findAll(Pageable pageable) {
            throw unsupported();
        }

        @Override
        public List<Participant> findAll(Sort sort) {
            throw unsupported();
        }
    }

    /**
     * Shared state and the {@code CrudRepository} methods none of these tests reach, so each stub
     * carries only what it actually answers.
     *
     * @param <K> the key rows are held under, which is not always the entity's own primary key
     */
    private abstract static class StubRepository<K, E> {

        private final Map<K, E> rows = new LinkedHashMap<>();

        @Nullable private DataAccessException saveFailure;

        @Nullable private E committedByTheWinner;

        /** The key a row is held under. */
        abstract K keyOf(E row);

        /** Seeds a row as if a previous call had written it. */
        E put(E row) {
            rows.put(keyOf(row), row);
            return row;
        }

        Map<K, E> rows() {
            return rows;
        }

        /**
         * Makes the next save throw, after committing {@code winner} - {@code null} models a
         * failure that is not the duplicate-key race.
         */
        void commitDuringSave(@Nullable E winner, DataAccessException failure) {
            this.committedByTheWinner = winner;
            this.saveFailure = failure;
        }

        /** Replays the race the stub was armed with, exactly once. */
        void failIfTold() {
            if (saveFailure == null) {
                return;
            }
            DataAccessException failure = saveFailure;
            saveFailure = null;
            if (committedByTheWinner != null) {
                put(committedByTheWinner);
            }
            throw failure;
        }

        public <S extends E> S insert(S entity) {
            throw unsupported();
        }

        public <S extends E> List<S> saveAll(Iterable<S> entities) {
            throw unsupported();
        }

        public <S extends E> List<S> insertAll(Iterable<S> entities) {
            throw unsupported();
        }

        public <S extends E> List<S> updateAll(Iterable<S> entities) {
            throw unsupported();
        }

        public List<E> findAll() {
            throw unsupported();
        }

        public long count() {
            throw unsupported();
        }

        public void delete(E entity) {
            throw unsupported();
        }

        public void deleteAll(Iterable<? extends E> entities) {
            throw unsupported();
        }

        public void deleteAll() {
            throw unsupported();
        }

        /** Guards the operations this service must never reach. */
        static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used by the user service");
        }
    }
}
