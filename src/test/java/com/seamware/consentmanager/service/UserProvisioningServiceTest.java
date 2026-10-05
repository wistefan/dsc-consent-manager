package com.seamware.consentmanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.security.UserPrincipal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.exceptions.DataAccessException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Covers what {@link UserProvisioningIT} cannot force deterministically: the duplicate-key branch,
 * both when the winner's row is there to adopt and when the failure was never the race.
 */
@DisplayName("User provisioning")
class UserProvisioningServiceTest {

    /** Identifier every token in this test carries. */
    private static final String IDENTIFIER = "urn:test:user:ada";

    /** Issuer every token in this test carries; provisioning does not read it. */
    private static final String ISSUER = "https://idp.example/realms/test";

    /** Display claims of the row already in the database. */
    private static final String STORED_EMAIL = "ada@participant.example";

    private static final String STORED_GIVEN_NAME = "Ada";

    private static final String STORED_FAMILY_NAME = "Lovelace";

    private final StubUserRepository users = new StubUserRepository();

    private final UserProvisioningService service = new UserProvisioningService(users);

    /** An identifier no row exists for is inserted from the claims. */
    @Test
    @DisplayName("inserts a row built from the claims on first sight")
    void insertsOnFirstSight() {
        User provisioned = service.provision(principal(STORED_EMAIL, "Ada", "Lovelace"));

        assertThat(users.saved).hasSize(1);
        assertThat(users.updated).isEmpty();
        assertThat(provisioned.getIdentifier()).isEqualTo(IDENTIFIER);
        assertThat(provisioned.getEmail()).isEqualTo(STORED_EMAIL);
        assertThat(provisioned.getFirstName()).isEqualTo("Ada");
        assertThat(provisioned.getLastName()).isEqualTo("Lovelace");
    }

    /**
     * The row a concurrent request committed between this one's read and its insert is adopted, and
     * the claims this token carries are written onto it.
     */
    @Test
    @DisplayName("adopts and refreshes the row a concurrent request committed")
    void adoptsTheWinnersRow() {
        User winner = stored();
        users.commitDuringSave(winner, new DataAccessException("duplicate key"));

        User provisioned =
                service.provision(principal("ada.lovelace@participant.example", "Ada", "Lovelace"));

        assertThat(provisioned).isSameAs(winner);
        assertThat(provisioned.getEmail()).isEqualTo("ada.lovelace@participant.example");
        assertThat(users.updated).containsExactly(winner);
    }

    /** A save failure that was not the race is not swallowed, and not turned into another type. */
    @Test
    @DisplayName("rethrows a save failure that left no row behind")
    void rethrowsAFailureThatWasNotTheRace() {
        DataAccessException failure = new DataAccessException("connection reset");
        users.commitDuringSave(null, failure);

        assertThatThrownBy(() -> service.provision(principal(STORED_EMAIL, "Ada", "Lovelace")))
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
    @DisplayName("writes the row only when an asserted claim differs")
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

        User provisioned = service.provision(principal(email, givenName, familyName));

        assertThat(provisioned).isSameAs(existing);
        assertThat(users.saved).isEmpty();
        assertThat(users.updated).hasSize(expectWrite ? 1 : 0);
        assertThat(provisioned.getEmail()).isEqualTo(expectedEmail);
        assertThat(provisioned.getFirstName()).isEqualTo(expectedGivenName);
        assertThat(provisioned.getLastName()).isEqualTo(expectedFamilyName);
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

    /** A {@code USER} principal carrying the given display claims. */
    private static UserPrincipal principal(
            @Nullable String email, @Nullable String givenName, @Nullable String familyName) {
        return new UserPrincipal(
                ISSUER, IDENTIFIER, IDENTIFIER, email, false, null, givenName, familyName, null);
    }

    /** The row a previous request already provisioned. */
    private static User stored() {
        return new User(IDENTIFIER, STORED_EMAIL, STORED_GIVEN_NAME, STORED_FAMILY_NAME);
    }

    /**
     * An in-memory {@link UserRepository} that records what it was asked to write and can be told
     * to fail a save the way the unique constraint does.
     */
    private static final class StubUserRepository implements UserRepository {

        private final Map<String, User> rows = new LinkedHashMap<>();

        private final List<User> saved = new ArrayList<>();

        private final List<User> updated = new ArrayList<>();

        @Nullable private DataAccessException saveFailure;

        @Nullable private User committedByTheWinner;

        /** Seeds a row as if a previous request had provisioned it. */
        User put(User row) {
            rows.put(row.getIdentifier(), row);
            return row;
        }

        /**
         * Makes the next save throw, after committing {@code winner} - {@code null} models a
         * failure that is not the duplicate-key race.
         */
        void commitDuringSave(@Nullable User winner, DataAccessException failure) {
            this.committedByTheWinner = winner;
            this.saveFailure = failure;
        }

        @Override
        public Optional<User> findByIdentifier(String identifier) {
            return Optional.ofNullable(rows.get(identifier));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <S extends User> S save(S entity) {
            if (saveFailure != null) {
                DataAccessException failure = saveFailure;
                saveFailure = null;
                if (committedByTheWinner != null) {
                    put(committedByTheWinner);
                }
                throw failure;
            }
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
        public Optional<User> findByEmailIgnoreCase(String email) {
            throw unsupported();
        }

        @Override
        public boolean existsByIdentifier(String identifier) {
            throw unsupported();
        }

        @Override
        public <S extends User> S insert(S entity) {
            throw unsupported();
        }

        @Override
        public <S extends User> List<S> insertAll(Iterable<S> entities) {
            throw unsupported();
        }

        @Override
        public <S extends User> List<S> updateAll(Iterable<S> entities) {
            throw unsupported();
        }

        @Override
        public <S extends User> List<S> saveAll(Iterable<S> entities) {
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
        public List<User> findAll() {
            throw unsupported();
        }

        @Override
        public long count() {
            throw unsupported();
        }

        @Override
        public void deleteById(UUID id) {
            throw unsupported();
        }

        @Override
        public void delete(User entity) {
            throw unsupported();
        }

        @Override
        public void deleteAll(Iterable<? extends User> entities) {
            throw unsupported();
        }

        @Override
        public void deleteAll() {
            throw unsupported();
        }

        /** Guards the operations provisioning must never reach. */
        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used by user provisioning");
        }
    }
}
