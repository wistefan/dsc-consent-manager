package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.security.Role;
import com.seamware.consentmanager.service.CallerScope;
import com.seamware.consentmanager.service.UserService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Covers the attribute and timestamp cases the endpoint tests cannot produce from a real token. */
@DisplayName("Mapping a stored user to its representation")
class UserMapperTest {

    private static final String IDENTIFIER = "urn:test:user:ada";

    private static final Instant CREATED_AT = Instant.parse("2026-01-02T03:04:05Z");

    /** The links a caller reading dataspace-wide is told about, for a user linked to two. */
    private static final List<String> ALL_LINKS = List.of("urn:a", "urn:b");

    /** What the same user's links narrow to for a participant caller: its own, never the other. */
    private static final List<String> SCOPED_LINKS = List.of("urn:a");

    /** A stub rather than a mock: the project carries no mocking framework. */
    private static UserMapper mapperLinkedTo(List<String> participants) {
        return mapperLinkedTo(participants, SCOPED_LINKS);
    }

    /** A stub whose two link reads answer differently, so which one the mapper used is visible. */
    private static UserMapper mapperLinkedTo(List<String> all, List<String> scoped) {
        return new UserMapper(
                new UserService(null, null, null, null, null) {
                    @Override
                    public List<String> participantIdentifiersFor(User user) {
                        return all;
                    }

                    @Override
                    public List<String> participantIdentifiersFor(User user, CallerScope scope) {
                        return scoped;
                    }
                });
    }

    private static User storedUser(String email, String firstName, String lastName) {
        User user = new User(IDENTIFIER, email, firstName, lastName);
        user.setCreatedAt(CREATED_AT);
        user.setUpdatedAt(CREATED_AT);
        return user;
    }

    static Stream<Arguments> attributeSets() {
        return Stream.of(
                Arguments.of("every attribute known", "ada@example.org", "Ada", "Lovelace"),
                Arguments.of("no attribute known", null, null, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("attributeSets")
    @DisplayName("carries the stored attributes through verbatim")
    void carriesTheStoredAttributes(String name, String email, String firstName, String lastName) {
        var representation =
                mapperLinkedTo(List.of()).toRepresentation(storedUser(email, firstName, lastName));

        assertThat(representation.getIdentifier()).isEqualTo(IDENTIFIER);
        assertThat(representation.getEmail()).isEqualTo(email);
        assertThat(representation.getFirstName()).isEqualTo(firstName);
        assertThat(representation.getLastName()).isEqualTo(lastName);
    }

    @Test
    @DisplayName("publishes timestamps at UTC rather than at the server's zone")
    void publishesTimestampsAtUtc() {
        var representation =
                mapperLinkedTo(List.of()).toRepresentation(storedUser(null, null, null));

        assertThat(representation.getCreatedAt())
                .isEqualTo(OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC));
        assertThat(representation.getUpdatedAt()).isEqualTo(representation.getCreatedAt());
    }

    @Test
    @DisplayName("omits timestamps a row has not been given yet")
    void omitsUnsetTimestamps() {
        var representation =
                mapperLinkedTo(List.of()).toRepresentation(new User(IDENTIFIER, null, null, null));

        assertThat(representation.getCreatedAt()).isNull();
        assertThat(representation.getUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("reports the participants the user is linked to")
    void reportsTheParticipantLinks() {
        assertThat(
                        mapperLinkedTo(ALL_LINKS)
                                .toRepresentation(storedUser(null, null, null))
                                .getParticipants())
                .isEqualTo(ALL_LINKS);
    }

    /** The scoped overload must not fall through to the unscoped read; that is the disclosure. */
    @Test
    @DisplayName("narrows the participants to what the reading caller may see")
    void narrowsTheParticipantLinksToTheCaller() {
        CallerScope scope = new CallerScope(Role.PARTICIPANT, null);

        assertThat(
                        mapperLinkedTo(ALL_LINKS, SCOPED_LINKS)
                                .toRepresentation(storedUser(null, null, null), scope)
                                .getParticipants())
                .isEqualTo(SCOPED_LINKS);
    }
}
