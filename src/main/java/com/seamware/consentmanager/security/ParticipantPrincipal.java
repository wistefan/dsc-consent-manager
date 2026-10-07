package com.seamware.consentmanager.security;

import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.error.ForbiddenException;
import io.micronaut.core.annotation.Nullable;
import java.util.Objects;

/**
 * A dataspace participant - a machine client acting for an organisation.
 *
 * <p>Unlike {@link UserPrincipal}, a participant is never created on the strength of a token, so an
 * identifier the {@code participants} table does not know still becomes a principal, just one whose
 * {@code participant} row is absent. That shape exists for exactly one route - {@code POST
 * /participants}, where a participant registers itself. Every other participant-scoped caller reads
 * the row through {@link #requireRegistered()}, or through {@link #requireActive()} where it acts
 * in the dataspace rather than merely reads the record back, and so refuses an unregistered - or a
 * departed - identifier with {@code 403}.
 *
 * @param issuer the verified {@code iss}
 * @param subject the verified {@code sub}
 * @param identifier the globally unique participant identifier, from the provider's configured
 *     {@code claims.participant-identifier}; always present, registered or not
 * @param participant the registered participant that identifier resolved to, {@code null} when it
 *     is not registered yet
 */
public record ParticipantPrincipal(
        String issuer, String subject, String identifier, @Nullable Participant participant)
        implements ConsentManagerPrincipal {

    /** The identity fields are read from a verified token and are never absent. */
    public ParticipantPrincipal {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(identifier, "identifier");
    }

    /** Returns {@link Role#PARTICIPANT}. */
    @Override
    public Role role() {
        return Role.PARTICIPANT;
    }

    /** Whether the identifier resolved to a {@code participants} row. */
    public boolean registered() {
        return participant != null;
    }

    /**
     * The participant's registered row, for every operation that acts on it.
     *
     * @throws ForbiddenException {@code 403} when the identifier is not registered yet
     */
    public Participant requireRegistered() {
        if (participant == null) {
            throw new ForbiddenException(
                    "Participant '"
                            + identifier
                            + "' is not registered. Register it with POST /participants before"
                            + " using this operation.");
        }
        return participant;
    }

    /**
     * The participant's registered row, for every operation that acts in the dataspace in its name.
     *
     * <p>Stricter than {@link #requireRegistered()} by one condition: a record marked {@code
     * deregistered_at} is retained only so the consents and privacy notices naming it stay legible,
     * and the organization behind it has left. Letting its token keep writing would let it
     * re-create the very affiliations deregistration removed. Reading its own record stays open, so
     * a departed participant can still see what became of it.
     *
     * @throws ForbiddenException {@code 403} when the identifier is not registered, or its record
     *     is deregistered
     */
    public Participant requireActive() {
        Participant registered = requireRegistered();
        if (registered.getDeregisteredAt() != null) {
            throw new ForbiddenException(
                    "Participant '"
                            + identifier
                            + "' is deregistered. Register it again with POST /participants before"
                            + " using this operation.");
        }
        return registered;
    }
}
