package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.User;
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

    /** A stub rather than a mock: the project carries no mocking framework. */
    private static UserMapper mapperLinkedTo(List<String> participants) {
        return new UserMapper(
                new UserService(null, null, null) {
                    @Override
                    public List<String> participantIdentifiersFor(User user) {
                        return participants;
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
        List<String> linked = List.of("urn:a", "urn:b");

        assertThat(
                        mapperLinkedTo(linked)
                                .toRepresentation(storedUser(null, null, null))
                                .getParticipants())
                .isEqualTo(linked);
    }
}
