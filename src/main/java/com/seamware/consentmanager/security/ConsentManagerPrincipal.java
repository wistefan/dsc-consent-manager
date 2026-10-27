package com.seamware.consentmanager.security;

/**
 * The typed caller identity, resolved once per request by {@link PrincipalResolutionFilter}. Sealed
 * over the three {@link Role}s so a {@code switch} is exhaustive. Every field comes from
 * signature-verified claims, never from a path variable, query parameter or body.
 */
public sealed interface ConsentManagerPrincipal
        permits UserPrincipal, ParticipantPrincipal, CatalogPrincipal {

    /** The verified {@code iss}; key a caller on it with {@link #subject()}, unique per issuer. */
    String issuer();

    /** The verified {@code sub} of the token, which is also {@code Authentication.getName()}. */
    String subject();

    /** The role this principal was resolved for, which is the role it acts as. */
    Role role();
}
