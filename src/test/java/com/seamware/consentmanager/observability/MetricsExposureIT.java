package com.seamware.consentmanager.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jwt.JWTClaimsSet;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.security.ConsentManagerTokenValidator;
import com.seamware.consentmanager.security.IdentityProviderRegistry;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.OidcDiscoveryStub;
import com.seamware.consentmanager.support.PostgresTestResource;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins that the meters operators scrape are published on {@code /metrics} and {@code /prometheus},
 * read with security enabled and a token the stub provider signed.
 */
@MicronautTest(transactional = false)
@DisplayName("Metrics exposure")
class MetricsExposureIT extends PostgresTestResource {

    private static final String METRICS_PATH = "/metrics";
    private static final String PROMETHEUS_PATH = "/prometheus";
    private static final String NAMES_FIELD = "names";
    private static final String AUDIENCE = "consent-manager";
    private static final String FOREIGN_AUDIENCE = "another-service";
    private static final String SUBJECT = "metrics-scraper";
    private static final String PROVIDER_PREFIX = "consent-manager.identity-providers.primary.";
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(10);
    private static final Duration RESOLUTION_TIMEOUT = Duration.ofSeconds(30);

    /** Prefix every Hikari pool meter carries. */
    private static final String HIKARI_PREFIX = "hikaricp.connections";

    /** Meters any running instance publishes, whatever traffic it has served. */
    private static final List<String> REQUIRED_METERS =
            List.of(
                    "jvm.memory.used",
                    "http.server.requests",
                    ConsentMetrics.TRANSITIONS_METRIC,
                    ConsentManagerTokenValidator.CLAIM_REJECTION_METRIC);

    /** Prometheus renders a counter's name with dots as underscores and a {@code _total} suffix. */
    private static final String PROMETHEUS_TRANSITIONS_SAMPLE =
            ConsentMetrics.TRANSITIONS_METRIC.replace('.', '_') + "_total";

    private static final String PROMETHEUS_TYPE_LINE = "# TYPE ";

    private static OidcDiscoveryStub provider;

    @Inject
    @Client("/")
    HttpClient client;

    @Inject IdentityProviderRegistry registry;

    @Inject ConsentMetrics consentMetrics;

    @AfterEach
    void resetProvider() {
        provider.reset();
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    /** Starts the stub provider: the context reads its issuer before any {@code @BeforeAll}. */
    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>(super.getProperties());
        if (provider == null) {
            provider = new OidcDiscoveryStub();
        }
        properties.put("micronaut.security.enabled", "true");
        properties.put(PROVIDER_PREFIX + "issuer", provider.issuer());
        properties.put(PROVIDER_PREFIX + "discovery-url", provider.discoveryUrl());
        properties.put(PROVIDER_PREFIX + "audience", AUDIENCE);
        return properties;
    }

    @Test
    @DisplayName("/metrics names JVM, HTTP, pool, consent-transition and token-rejection meters")
    void metricsNamesEveryOperatorMeter() {
        awaitProvider();
        consentMetrics.transitioned(Optional.empty(), ConsentStatus.PENDING);
        assertThatThrownBy(
                        () ->
                                client.toBlocking()
                                        .exchange(authorized(METRICS_PATH, FOREIGN_AUDIENCE)))
                .isInstanceOfSatisfying(
                        HttpClientResponseException.class,
                        refused ->
                                assertThat(refused.getStatus().getCode())
                                        .isEqualTo(HttpStatus.UNAUTHORIZED.getCode()));

        Map<String, List<String>> body =
                client.toBlocking()
                        .retrieve(
                                authorized(METRICS_PATH, AUDIENCE),
                                Argument.mapOf(Argument.STRING, Argument.listOf(Argument.STRING)));

        assertThat(body.get(NAMES_FIELD))
                .containsAll(REQUIRED_METERS)
                .anyMatch(name -> name.startsWith(HIKARI_PREFIX));
    }

    @Test
    @DisplayName("/prometheus answers in the scrape format")
    void prometheusAnswersInScrapeFormat() {
        awaitProvider();
        consentMetrics.transitioned(Optional.empty(), ConsentStatus.PENDING);

        String scrape = client.toBlocking().retrieve(authorized(PROMETHEUS_PATH, AUDIENCE));

        assertThat(scrape).contains(PROMETHEUS_TYPE_LINE).contains(PROMETHEUS_TRANSITIONS_SAMPLE);
    }

    private void awaitProvider() {
        Await.until(
                "the stub provider to resolve",
                RESOLUTION_TIMEOUT,
                () -> registry.findByIssuer(provider.issuer()).isPresent());
    }

    private static HttpRequest<?> authorized(String path, String audience) {
        Instant now = Instant.now();
        JWTClaimsSet claims =
                new JWTClaimsSet.Builder()
                        .issuer(provider.issuer())
                        .subject(SUBJECT)
                        .audience(audience)
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plus(TOKEN_LIFETIME)))
                        .build();
        return HttpRequest.GET(path)
                .bearerAuth(provider.signToken(provider.initialKeyId(), claims).serialize());
    }
}
