package com.seamware.consentmanager.security;

import com.seamware.consentmanager.domain.Participant;

/**
 * A dataspace participant - a machine client acting for an organisation.
 *
 * <p>Unlike {@link UserPrincipal}, a participant is never created on the strength of a token:
 * {@code participant} is the row the token's identifier already resolved to, so an identifier that
 * is not registered never becomes a principal at all and the request is refused with {@code 403}.
 * Participants are registered explicitly (TICKET-005).
 *
 * @param issuer the verified {@code iss}
 * @param subject the verified {@code sub}
 * @param identifier the globally unique participant identifier, from the provider's configured
 *     {@code claims.participant-identifier}
 * @param participant the registered participant that identifier resolved to, never {@code null}
 */
public record ParticipantPrincipal(
        String issuer, String subject, String identifier, Participant participant)
        implements ConsentManagerPrincipal {

    /** Returns {@link Role#PARTICIPANT}. */
    @Override
    public Role role() {
        return Role.PARTICIPANT;
    }
}
