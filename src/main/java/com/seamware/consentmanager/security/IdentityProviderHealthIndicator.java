package com.seamware.consentmanager.security;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.endpoint.health.HealthEndpoint;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import io.micronaut.management.health.indicator.annotation.Readiness;
import jakarta.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.Map;
import org.reactivestreams.Publisher;

/**
 * Reports whether this instance can actually validate tokens yet, as a readiness health indicator.
 *
 * <p>A Consent Manager whose identity providers are not yet discovered is running but useless: it
 * would answer every authenticated request with a 401 that looks like a client error. Readiness is
 * the mechanism that exists for exactly that state - the process is alive and must not be killed,
 * but traffic should go elsewhere until it can serve. So this indicator is {@link Readiness}, not
 * {@link io.micronaut.management.health.indicator.annotation.Liveness}: an identity provider outage
 * must drain the instance, never restart it, because a restart cannot fix someone else's downtime
 * and a crash loop only removes the instance from the orchestrator's control.
 *
 * <p>The indicator is {@code DOWN} while any configured provider is unresolved - whether it is
 * {@link ResolutionState#PENDING} because the provider is unreachable, or {@link
 * ResolutionState#FAILED} because its discovery document names a different issuer - and {@code UP}
 * once every one of them is {@link ResolutionState#RESOLVED}. It never reports partial success,
 * because a token from the one provider that did not resolve would be rejected, and an instance
 * that silently rejects a subset of valid tokens is worse than one that is plainly not ready.
 *
 * <p><strong>Known consequence with more than one provider.</strong> All-or-nothing readiness is
 * what the implementation plan for this ticket specifies, and it is exactly right for the
 * single-provider deployment and for the cold start AC 3 is about. It is not obviously right once
 * several providers are configured: every replica shares the same trust list, so one provider being
 * unreachable drains the whole deployment, and tokens from the providers that <em>did</em> resolve
 * stop being served even though this instance could validate them. A {@link ResolutionState#FAILED}
 * entry makes that permanent - a third party that re-points its issuer can hold the deployment
 * {@code DOWN} until an operator restarts without it. The alternative - {@code DOWN} only while
 * <em>no</em> provider has resolved, with the per-provider detail below plus a WARN covering the
 * partial case - trades a louder failure for a smaller blast radius. Which of the two is wanted is
 * a deployment-policy decision that outlives this step, so it is raised on the ticket rather than
 * settled here; until it is decided, the planned behaviour stands and this paragraph is the record
 * of what it costs.
 *
 * <p>Per-provider detail is attached to the result so an operator can see which provider is holding
 * readiness down and why. That detail is published under {@code endpoints.health.details-visible},
 * which this service sets to {@code AUTHENTICATED}: an anonymous probe learns only {@code UP} or
 * {@code DOWN} and never which issuers are configured.
 */
@Singleton
@Readiness
@Requires(beans = HealthEndpoint.class)
public class IdentityProviderHealthIndicator implements HealthIndicator {

    /** Key this indicator's result appears under in the health report. */
    public static final String NAME = "identityProviders";

    /** Detail key carrying an entry's {@link ResolutionState}. */
    private static final String DETAIL_STATE = "state";

    /** Detail key carrying an entry's configured issuer. */
    private static final String DETAIL_ISSUER = "issuer";

    /** Detail key carrying the discovered JWK Set URL of a resolved entry. */
    private static final String DETAIL_JWKS_URI = "jwksUri";

    /** Detail key carrying the most recent discovery failure of an unresolved entry. */
    private static final String DETAIL_ERROR = "error";

    /** Detail key carrying how many discovery attempts an entry has taken. */
    private static final String DETAIL_ATTEMPTS = "attempts";

    private final IdentityProviderRegistry registry;

    /**
     * Creates the indicator.
     *
     * @param registry the trust list whose resolution state is reported
     */
    public IdentityProviderHealthIndicator(IdentityProviderRegistry registry) {
        this.registry = registry;
    }

    /**
     * Produces the current readiness result.
     *
     * <p>Reads in-memory state only - it issues no request of its own, so a probe can never be
     * slower than the registry's own bounded discovery, and probing more often cannot add load on
     * an identity provider that is already struggling.
     *
     * @return a single-element publisher carrying the result
     */
    @Override
    public Publisher<HealthResult> getResult() {
        Map<String, Object> details = new LinkedHashMap<>();
        for (ResolvedIdentityProvider provider : registry.snapshot()) {
            details.put(provider.name(), describe(provider));
        }
        HealthStatus status = registry.isFullyResolved() ? HealthStatus.UP : HealthStatus.DOWN;
        return Publishers.just(HealthResult.builder(NAME, status).details(details).build());
    }

    /**
     * Renders one trust-list entry as health detail.
     *
     * @param provider the entry to describe
     * @return an ordered map of detail keys, omitting whichever of the JWK Set URL and the failure
     *     reason does not apply to the entry's state
     */
    private static Map<String, Object> describe(ResolvedIdentityProvider provider) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put(DETAIL_STATE, provider.state().name());
        detail.put(DETAIL_ISSUER, provider.issuer());
        detail.put(DETAIL_ATTEMPTS, provider.attempts());
        if (provider.jwksUri() != null) {
            detail.put(DETAIL_JWKS_URI, provider.jwksUri());
        }
        if (provider.failureReason() != null) {
            detail.put(DETAIL_ERROR, provider.failureReason());
        }
        return detail;
    }
}
