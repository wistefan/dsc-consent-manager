package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.model.ParticipantUserLink;
import com.seamware.consentmanager.api.generated.model.User;
import com.seamware.consentmanager.domain.UserParticipant;
import com.seamware.consentmanager.service.CallerScope;
import com.seamware.consentmanager.service.UserService;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Renders a stored user as the generated API {@code User}, shared so operations cannot drift; the
 * stored row is named in full to keep the two apart.
 */
@Singleton
public class UserMapper {

    private final UserService users;

    public UserMapper(UserService users) {
        this.users = users;
    }

    /** The representation of a stored user, disclosing every participant link it has. */
    public User toRepresentation(com.seamware.consentmanager.domain.User stored) {
        return toRepresentation(stored, users.participantIdentifiersFor(stored));
    }

    /**
     * The representation a caller may read: a {@code PARTICIPANT} sees only its own link in {@code
     * participants}, a dataspace-wide caller sees all.
     */
    public User toRepresentation(
            com.seamware.consentmanager.domain.User stored, CallerScope scope) {
        return toRepresentation(stored, users.participantIdentifiersFor(stored, scope));
    }

    /**
     * One participant's link to a user; the identifier comes from the request, since a global
     * identifier never changes.
     */
    public ParticipantUserLink toLinkRepresentation(String identifier, UserParticipant link) {
        return new ParticipantUserLink(identifier, atUtc(link.getCreatedAt()))
                .localIdentifier(link.getLocalIdentifier());
    }

    private User toRepresentation(
            com.seamware.consentmanager.domain.User stored, List<String> participants) {
        return new User(stored.getIdentifier(), participants)
                .email(stored.getEmail())
                .firstName(stored.getFirstName())
                .lastName(stored.getLastName())
                .createdAt(atUtc(stored.getCreatedAt()))
                .updatedAt(atUtc(stored.getUpdatedAt()));
    }

    /** Published at UTC, never at the server's zone. */
    private static OffsetDateTime atUtc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
