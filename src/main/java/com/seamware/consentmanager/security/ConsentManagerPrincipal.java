package com.seamware.consentmanager.security;

/**
 * The typed identity of an authenticated caller, resolved once per request from the validated
 * bearer token by {@link PrincipalResolutionFilter}.
 *
 * <p>Sealed on purpose: the three permitted shapes are the three roles in {@link Role}, so a {@code
 * switch} over a principal is exhaustive and a new caller kind cannot be added without the compiler
 * pointing at every place that has to decide what it may do.
 *
 * <p>Every field a principal carries comes from claims a verified signature covers, so a handler
 * that declares a principal parameter takes the caller's identity from the token and never from a
 * path variable, query parameter or request body.
 */
public sealed interface ConsentManagerPrincipal
        permits UserPrincipal, ParticipantPrincipal, CatalogPrincipal {

    /**
     * The verified {@code iss} of the token. Pair it with {@link #subject()} to key on a caller:
     * {@code sub} is unique per issuer only.
     */
    String issuer();

    /** The verified {@code sub} of the token, which is also {@code Authentication.getName()}. */
    String subject();

    /** The role this principal was resolved for, which is the role it acts as. */
    Role role();
}
