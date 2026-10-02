package com.seamware.consentmanager.security;

import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.WwwAuthenticateChallengeProvider;
import jakarta.inject.Singleton;

/**
 * Supplies the {@code WWW-Authenticate} challenge every {@code 401} must carry, naming the only
 * scheme this service accepts.
 *
 * <p>Without a provider bean the framework sends a {@code 401} with no challenge at all: the
 * challenge providers it ships are for HTTP Basic and for OAuth2 protected-resource metadata, and
 * this service enables neither. RFC 6750 §3 requires a bearer resource server to answer a failed
 * authentication with {@code WWW-Authenticate: Bearer}, so the bean is supplied here.
 *
 * <p>The challenge is the bare scheme. A {@code realm}, a {@code resource_metadata} URI or an
 * {@code error} code would each tell an unauthenticated caller something about the trust list or
 * about which check its token failed, and a refusal says neither (US-ID-008).
 */
@Singleton
public class BearerChallengeProvider implements WwwAuthenticateChallengeProvider<HttpRequest<?>> {

    /** The sole authentication scheme this resource server accepts (RFC 6750). */
    public static final String CHALLENGE = "Bearer";

    /**
     * Returns the challenge, which is the same for every request.
     *
     * @param request the refused request, which deliberately does not influence the challenge
     * @return {@value #CHALLENGE}
     */
    @Override
    public String getWwwAuthenticateChallenge(HttpRequest<?> request) {
        return CHALLENGE;
    }
}
