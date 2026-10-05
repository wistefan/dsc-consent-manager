package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.model.User;
import com.seamware.consentmanager.service.UserService;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Renders a stored user as the API's {@code User} representation.
 *
 * <p>Shared by every operation that returns a user, so the participant links and the attribute set
 * a caller sees do not drift between them. {@code User} here is the generated API model; the stored
 * row is named in full to keep the two apart.
 */
@Singleton
public class UserMapper {

    private final UserService users;

    public UserMapper(UserService users) {
        this.users = users;
    }

    /** The representation of a stored user, with its participant links resolved and sorted. */
    public User toRepresentation(com.seamware.consentmanager.domain.User stored) {
        List<String> participants = users.participantIdentifiersFor(stored);
        return new User(stored.getIdentifier(), participants)
                .email(stored.getEmail())
                .firstName(stored.getFirstName())
                .lastName(stored.getLastName())
                .createdAt(atUtc(stored.getCreatedAt()))
                .updatedAt(atUtc(stored.getUpdatedAt()));
    }

    /** Timestamps are stored as instants and published at UTC, never at the server's zone. */
    private static OffsetDateTime atUtc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
