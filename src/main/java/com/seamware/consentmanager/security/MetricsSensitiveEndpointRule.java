package com.seamware.consentmanager.security;

import io.micronaut.configuration.metrics.management.endpoint.MetricsEndpoint;
import io.micronaut.configuration.metrics.micrometer.prometheus.management.PrometheusEndpoint;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.http.HttpRequest;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.management.endpoint.EndpointSensitivityProcessor;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRuleResult;
import io.micronaut.security.rules.SensitiveEndpointRule;
import jakarta.inject.Singleton;
import java.util.Set;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

/**
 * Micronaut's documented replacement of {@link SensitiveEndpointRule}: any bearer token opens a
 * sensitive metrics or Prometheus endpoint; every other sensitive endpoint stays refused.
 * Sensitivity itself is {@code endpoints.<id>.sensitive}, driven by {@code
 * METRICS_AUTHENTICATION_ENABLED}.
 */
@Singleton
@Replaces(SensitiveEndpointRule.class)
public class MetricsSensitiveEndpointRule extends SensitiveEndpointRule {

    private static final Set<Class<?>> TOKEN_READABLE_ENDPOINTS =
            Set.of(MetricsEndpoint.class, PrometheusEndpoint.class);

    /** Wraps the framework's view of which endpoint methods are sensitive. */
    public MetricsSensitiveEndpointRule(EndpointSensitivityProcessor endpointSensitivityProcessor) {
        super(endpointSensitivityProcessor);
    }

    @Override
    @NonNull
    protected Publisher<SecurityRuleResult> checkSensitiveAuthenticated(
            @NonNull HttpRequest<?> request,
            @NonNull Authentication authentication,
            @NonNull ExecutableMethod<?, ?> method) {
        boolean readable = TOKEN_READABLE_ENDPOINTS.contains(method.getDeclaringType());
        return Mono.just(readable ? SecurityRuleResult.ALLOWED : SecurityRuleResult.REJECTED);
    }
}
