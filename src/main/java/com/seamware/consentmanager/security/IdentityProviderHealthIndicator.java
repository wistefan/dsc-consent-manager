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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.reactivestreams.Publisher;

/**
 * Readiness, not liveness: {@code DOWN} until every configured provider is {@link
 * ResolutionState#RESOLVED}, so an identity-provider outage drains the instance, never restarts it.
 *
 * <p>All-or-nothing on purpose: an instance silently rejecting one provider's tokens is worse than
 * one plainly not ready. The cost, an open policy question: with several providers one unreachable
 * or {@link ResolutionState#FAILED} provider drains every replica. Per-provider detail is shown
 * only to authenticated callers ({@code endpoints.health.details-visible}).
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

    /** Creates the indicator over the trust list whose resolution state it reports. */
    public IdentityProviderHealthIndicator(IdentityProviderRegistry registry) {
        this.registry = registry;
    }

    /**
     * Produces the current readiness result from in-memory state only, issuing no request. Status
     * and detail come from one {@link IdentityProviderRegistry#snapshot()} so they agree.
     */
    @Override
    public Publisher<HealthResult> getResult() {
        List<ResolvedIdentityProvider> providers = registry.snapshot();
        Map<String, Object> details =
                providers.stream()
                        .collect(
                                Collectors.toMap(
                                        ResolvedIdentityProvider::name,
                                        IdentityProviderHealthIndicator::describe,
                                        (earlier, later) -> later,
                                        LinkedHashMap::new));
        HealthStatus status =
                providers.stream().allMatch(ResolvedIdentityProvider::isUsable)
                        ? HealthStatus.UP
                        : HealthStatus.DOWN;
        return Publishers.just(HealthResult.builder(NAME, status).details(details).build());
    }

    /** Renders one entry as health detail, omitting the JWKS URL or error when absent. */
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
