package com.seamware.consentmanager.security;

import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.WwwAuthenticateChallengeProvider;
import jakarta.inject.Singleton;

/**
 * Supplies the {@code WWW-Authenticate: Bearer} challenge RFC 6750 &sect;3 requires on refusal.
 * Deliberately the bare scheme: a {@code realm}, {@code resource_metadata} or {@code error} would
 * tell an unauthenticated caller about the trust list or which check its token failed.
 */
@Singleton
public class BearerChallengeProvider implements WwwAuthenticateChallengeProvider<HttpRequest<?>> {

    /** The sole authentication scheme this resource server accepts (RFC 6750). */
    public static final String CHALLENGE = "Bearer";

    /** Returns {@value #CHALLENGE}, the same for every request. */
    @Override
    public String getWwwAuthenticateChallenge(HttpRequest<?> request) {
        return CHALLENGE;
    }
}
