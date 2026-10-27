package com.seamware.consentmanager.security;

import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.error.ForbiddenException;
import io.micronaut.core.annotation.Nullable;
import java.util.Objects;

/**
 * A dataspace participant: a machine client acting for an organisation.
 *
 * <p>{@code participant} is {@code null} for an unregistered identifier; only {@code POST
 * /participants} accepts that shape. Every other route uses {@link #requireRegistered()} or, when
 * acting in the dataspace, {@link #requireActive()}, and refuses with {@code 403}.
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
     * @throws ForbiddenException {@code 403} when not registered yet
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
     * Also refuses a deregistered row, so it cannot re-create what deregistration removed.
     *
     * @throws ForbiddenException {@code 403} when unregistered or deregistered
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
