package com.seamware.consentmanager.security;

/**
 * The typed identity of an authenticated caller, resolved once per request from the validated
 * bearer token by {@link PrincipalResolutionFilter}.
 *
 * <p>Sealed on purpose: the three permitted shapes are the three roles in {@link Role}, so a {@code
 * switch} over a principal is exhaustive and a new caller kind cannot be introduced without the
 * compiler pointing at every place that has to decide what it may do.
 *
 * <p>Every field a principal carries comes from claims a verified signature covers. Handlers take
 * the caller's identity from here and never from a path variable, query parameter or request body
 * (AC 15); declaring a principal parameter is what makes that the path of least resistance.
 */
public sealed interface ConsentManagerPrincipal
        permits UserPrincipal, ParticipantPrincipal, CatalogPrincipal {

    /**
     * The verified {@code iss} of the token. Pair it with {@link #subject()} to key on a caller:
     * {@code sub} is unique per issuer only.
     *
     * @return the issuer, never {@code null}
     */
    String issuer();

    /**
     * The verified {@code sub} of the token, which is also {@code Authentication.getName()}.
     *
     * @return the subject, never {@code null}
     */
    String subject();

    /**
     * The single role this principal was resolved for.
     *
     * @return the granted role, never {@code null}
     */
    Role role();
}
