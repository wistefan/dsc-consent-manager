package com.seamware.consentmanager.security;

import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.WwwAuthenticateChallengeProvider;
import jakarta.inject.Singleton;

/**
 * Supplies the {@code WWW-Authenticate: Bearer} challenge RFC 6750 &sect;3 requires of a refused
 * bearer request.
 *
 * <p>This is the module's own extension point, and it is used because neither provider
 * micronaut-security 5.4.0 ships emits that challenge here: {@code
 * BasicAuthWwwAuthenticateChallengeProvider} challenges with {@code Basic}, and {@code
 * ResourceMetadataWwwAuthenticateChallengeProvider} is switched off with the rest of the RFC 9728
 * beans (see {@code micronaut.security.oauth2.protected-resource-metadata} in {@code
 * application.yml}). The challenge is the bare scheme: a {@code realm}, a {@code resource_metadata}
 * URI or an {@code error} code would each tell an unauthenticated caller something about the trust
 * list or about which check its token failed (US-ID-008).
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
