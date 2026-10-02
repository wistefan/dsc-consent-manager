package com.seamware.consentmanager.security;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.rules.SecurityRuleResult;
import io.micronaut.web.router.RouteAttributes;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

/**
 * Refuses any routed request that no other security rule decided, making access explicit.
 *
 * <p>Micronaut's default is the opposite one: its security filter logs "No rule provider authorized
 * or rejected the request" and lets it through, so an endpoint that simply forgets {@code @Secured}
 * is public. This rule closes that, and it is the whole of "no implicit access" - there is
 * deliberately no catch-all {@code intercept-url-map} entry.
 *
 * <p>A catch-all in configuration would be worse than nothing. {@code
 * ConfigurationInterceptUrlMapRule} is ordered <em>ahead</em> of {@code SecuredAnnotationRule}, and
 * the filter stops at the first rule that answers, so a {@code /**} entry granting {@code
 * isAuthenticated()} would answer for every route first and no {@code @Secured} role requirement
 * would ever be consulted again. This rule is ordered {@link Ordered#LOWEST_PRECEDENCE} instead, so
 * it only ever speaks when every other rule has returned {@code UNKNOWN}.
 *
 * <p>Requests that matched no route are passed over rather than refused. What an unmapped path is
 * answered with is {@code micronaut.security.reject-not-found}'s decision, and that is left at its
 * default, which refuses: a service that answered 404 there would confirm which paths exist to a
 * caller that never authenticated. Returning {@code UNKNOWN} here keeps that one decision in one
 * place instead of pre-empting it with a second, silently diverging one.
 */
@Singleton
public class DenyUnlessExplicitlyAllowedRule implements SecurityRule<HttpRequest<?>> {

    /** Last of all, so an explicit decision by any other rule always wins. */
    public static final int ORDER = Ordered.LOWEST_PRECEDENCE;

    /**
     * Rejects a matched route nobody allowed.
     *
     * @param request the request under evaluation
     * @param authentication the caller, or {@code null} when the request is anonymous
     * @return {@code REJECTED} for a matched route, {@code UNKNOWN} when no route matched
     */
    @Override
    public Publisher<SecurityRuleResult> check(
            HttpRequest<?> request, @Nullable Authentication authentication) {
        if (RouteAttributes.getRouteMatch(request).isEmpty()) {
            return Mono.just(SecurityRuleResult.UNKNOWN);
        }
        return Mono.just(SecurityRuleResult.REJECTED);
    }

    /**
     * Returns {@link #ORDER}.
     *
     * @return {@link Ordered#LOWEST_PRECEDENCE}
     */
    @Override
    public int getOrder() {
        return ORDER;
    }
}
