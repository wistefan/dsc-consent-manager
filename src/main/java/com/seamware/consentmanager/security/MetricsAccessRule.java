package com.seamware.consentmanager.security;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.ConfigurationInterceptUrlMapRule;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.rules.SecurityRuleResult;
import jakarta.inject.Singleton;
import java.util.List;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

/**
 * Governs {@code /metrics} and {@code /prometheus} on the management port by {@code
 * consent-manager.management.metrics-authentication}: a bearer token when on, anonymous when off.
 * Answers {@code UNKNOWN} for every other request, so it is never a catch-all.
 */
@Singleton
public class MetricsAccessRule implements SecurityRule<HttpRequest<?>> {

    /** Ahead of the intercept map, so the flag decides rather than a static entry. */
    public static final int ORDER = ConfigurationInterceptUrlMapRule.ORDER - 1;

    private static final List<String> METRICS_TREES = List.of("/metrics", "/prometheus");

    private static final char SEPARATOR = '/';

    private final boolean authenticationRequired;

    @Nullable private final Integer managementPort;

    /** A null management port means the endpoints share the API port, so every port qualifies. */
    public MetricsAccessRule(
            @Value("${consent-manager.management.metrics-authentication:true}")
                    boolean authenticationRequired,
            @Property(name = "endpoints.all.port") @Nullable Integer managementPort) {
        this.authenticationRequired = authenticationRequired;
        this.managementPort = managementPort;
    }

    @Override
    public Publisher<SecurityRuleResult> check(
            HttpRequest<?> request, @Nullable Authentication authentication) {
        if (!onManagementPort(request) || !isMetricsPath(request.getPath())) {
            return Mono.just(SecurityRuleResult.UNKNOWN);
        }
        boolean allowed = !authenticationRequired || authentication != null;
        return Mono.just(allowed ? SecurityRuleResult.ALLOWED : SecurityRuleResult.REJECTED);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    private boolean onManagementPort(HttpRequest<?> request) {
        return managementPort == null || request.getServerAddress().getPort() == managementPort;
    }

    private static boolean isMetricsPath(String path) {
        return METRICS_TREES.stream()
                .anyMatch(tree -> path.equals(tree) || path.startsWith(tree + SEPARATOR));
    }
}
