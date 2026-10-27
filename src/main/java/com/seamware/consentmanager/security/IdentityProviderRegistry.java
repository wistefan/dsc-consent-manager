package com.seamware.consentmanager.security;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.exceptions.DisabledBeanException;
import io.micronaut.http.client.DefaultHttpClientConfiguration;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.HttpClientConfiguration;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.TaskScheduler;
import io.micronaut.security.oauth2.client.DefaultOpenIdProviderMetadata;
import io.micronaut.security.oauth2.client.DefaultOpenIdProviderMetadataFetcher;
import io.micronaut.security.oauth2.client.OpenIdProviderMetadataFetcher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Named;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The trust list: every accepted OpenID Connect provider and what discovery has learned about it.
 * Discovery is the module's {@link DefaultOpenIdProviderMetadataFetcher}; see ADR-0003.
 *
 * <p>Security invariant: membership is fixed at construction and only per-entry metadata changes.
 * Discovery runs in the background, so startup never blocks or fails on a provider (readiness stays
 * {@code DOWN} instead). Every failure is retried with backoff except a document naming a
 * <em>different</em> issuer, which is {@link ResolutionState#FAILED} for good, since it could
 * re-point the entry at another provider's keys. A resolved entry is never re-discovered.
 * Thread-safe: each entry is replaced wholesale by the one task that owns it.
 */
@Context
public class IdentityProviderRegistry implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(IdentityProviderRegistry.class);

    /** First retry delay after a failed discovery; doubles per failure. */
    public static final Duration MIN_DISCOVERY_RETRY_DELAY = Duration.ofSeconds(2);

    /** Retry delay ceiling, so a long outage still recovers within minutes of its end. */
    public static final Duration MAX_DISCOVERY_RETRY_DELAY = Duration.ofMinutes(5);

    /** Factor the retry delay is multiplied by after each consecutive failure. */
    private static final int DISCOVERY_RETRY_BACKOFF_MULTIPLIER = 2;

    /** Connect and read timeout for a discovery request, tighter than the client default. */
    private static final Duration DISCOVERY_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** Delay used for the very first attempt, scheduled rather than run inline so boot proceeds. */
    private static final Duration INITIAL_DISCOVERY_DELAY = Duration.ZERO;

    /** URL schemes a JWK Set may be published under, mirroring the configured-URL rule. */
    private static final Set<String> SUPPORTED_JWKS_URL_SCHEMES = Set.of("http", "https");

    /** The only scheme that authenticates a JWK Set in transit. */
    private static final String SECURE_URL_SCHEME = "https";

    /** Per-entry opt-in quoted in the failure reason when a cleartext JWK Set URL is refused. */
    private static final String ALLOW_INSECURE_TRANSPORT_KEY = "allow-insecure-transport";

    /** The fixed trust list, keyed by configured issuer. Keys never change; values do. */
    private final Map<String, ResolvedIdentityProvider> entries;

    /** The configured issuers ordered by provider name, so reports read the same every time. */
    private final List<String> issuersInOrder;

    /** The outstanding retry per issuer, kept only so shutdown can cancel it. */
    private final Map<String, ScheduledFuture<?>> scheduledAttempts = new ConcurrentHashMap<>();

    /** One metadata fetcher per configured issuer; same fixed key set as {@link #entries}. */
    private final Map<String, OpenIdProviderMetadataFetcher> metadataFetchers;

    private final TaskScheduler scheduler;

    /**
     * The blocking pool each fetch runs on; the scheduler only times attempts, so an unreachable
     * provider cannot starve other scheduled tasks.
     */
    private final ExecutorService discoveryExecutor;

    private final HttpClient discoveryClient;

    /** Set on shutdown so an attempt already queued does not start fetching during teardown. */
    private volatile boolean closed;

    /**
     * Builds the registry with every entry {@link ResolutionState#PENDING}; fetches nothing. Takes
     * the validator, not a raw list, so its checks are guaranteed to have run.
     */
    public IdentityProviderRegistry(
            IdentityProviderRegistryValidator validatedProviders,
            @Named(TaskExecutors.SCHEDULED) TaskScheduler scheduler,
            @Named(TaskExecutors.BLOCKING) ExecutorService discoveryExecutor,
            HttpClientConfiguration globalClientConfiguration) {
        this.scheduler = scheduler;
        this.discoveryExecutor = discoveryExecutor;
        this.discoveryClient = createDiscoveryClient(globalClientConfiguration);
        Map<String, ResolvedIdentityProvider> initial = new LinkedHashMap<>();
        Map<String, OpenIdProviderMetadataFetcher> fetchers = new LinkedHashMap<>();
        for (IdentityProviderConfiguration configuration : validatedProviders.getProviders()) {
            initial.put(configuration.getIssuer(), ResolvedIdentityProvider.pending(configuration));
            fetchers.put(
                    configuration.getIssuer(),
                    new DefaultOpenIdProviderMetadataFetcher(
                            OpenIdClientConfigurationAdapter.forEntry(configuration),
                            discoveryClient));
        }
        this.issuersInOrder = List.copyOf(initial.keySet());
        this.entries = new ConcurrentHashMap<>(initial);
        this.metadataFetchers = Map.copyOf(fetchers);
    }

    /**
     * Creates the discovery client, inheriting the global TLS and proxy settings (copied, not
     * mutated). Timeouts are pinned and redirects refused, so a 3xx cannot move the trust anchor.
     */
    private static HttpClient createDiscoveryClient(
            HttpClientConfiguration globalClientConfiguration) {
        DefaultHttpClientConfiguration configuration = new DefaultHttpClientConfiguration();
        configuration.setSslConfiguration(globalClientConfiguration.getSslConfiguration());
        configuration.setProxyType(globalClientConfiguration.getProxyType());
        globalClientConfiguration.getProxyAddress().ifPresent(configuration::setProxyAddress);
        globalClientConfiguration.getProxySelector().ifPresent(configuration::setProxySelector);
        globalClientConfiguration.getProxyUsername().ifPresent(configuration::setProxyUsername);
        globalClientConfiguration.getProxyPassword().ifPresent(configuration::setProxyPassword);
        configuration.setConnectTimeout(DISCOVERY_REQUEST_TIMEOUT);
        configuration.setReadTimeout(DISCOVERY_REQUEST_TIMEOUT);
        configuration.setFollowRedirects(false);
        return HttpClient.create(null, configuration);
    }

    /** Schedules the first discovery attempt for every configured provider. */
    @PostConstruct
    void startDiscovery() {
        LOG.info(
                "Starting OpenID Connect discovery for {} configured identity provider(s); startup"
                        + " does not wait for it",
                issuersInOrder.size());
        issuersInOrder.forEach(issuer -> scheduleAttempt(issuer, INITIAL_DISCOVERY_DELAY));
    }

    /**
     * The {@link ResolutionState#RESOLVED} entry for an issuer; empty for {@code null}, unknown or
     * unresolved alike, so a 401 reveals nothing about the trust list.
     */
    public Optional<ResolvedIdentityProvider> findByIssuer(String issuer) {
        if (issuer == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(issuer)).filter(ResolvedIdentityProvider::isUsable);
    }

    /** Every configured entry, usable or not, by provider name; the readiness view. */
    public List<ResolvedIdentityProvider> snapshot() {
        return issuersInOrder.stream().map(entries::get).filter(Objects::nonNull).toList();
    }

    /** Whether every configured provider has been discovered successfully. */
    public boolean isFullyResolved() {
        return snapshot().stream().allMatch(ResolvedIdentityProvider::isUsable);
    }

    /** Queues one discovery attempt for one provider. */
    private void scheduleAttempt(String issuer, Duration delay) {
        if (closed) {
            return;
        }
        scheduledAttempts.put(
                issuer,
                scheduler.schedule(
                        delay, () -> discoveryExecutor.execute(() -> attemptDiscovery(issuer))));
    }

    /** Fetches one provider's metadata and advances its entry; one attempt per issuer at a time. */
    private void attemptDiscovery(String issuer) {
        ResolvedIdentityProvider current = entries.get(issuer);
        if (closed || current == null || current.state() != ResolutionState.PENDING) {
            return;
        }
        IdentityProviderConfiguration configuration = current.configuration();
        int attempt = current.attempts() + 1;
        try {
            DefaultOpenIdProviderMetadata metadata = fetchMetadata(configuration);
            // A missing issuer is an outage (e.g. an empty 200 mid-reload), so it is retried;
            // only a *different* issuer is permanent.
            if (metadata == null
                    || metadata.getIssuer() == null
                    || metadata.getIssuer().isBlank()) {
                throw new IllegalStateException(
                        "the discovery response carried no issuer, so the provider's identity could"
                                + " not be confirmed");
            }
            String discoveredIssuer = metadata.getIssuer();
            if (!issuer.equals(discoveredIssuer)) {
                rejectPermanently(configuration, attempt, discoveredIssuer);
                return;
            }
            String jwksUri = metadata.getJwksUri();
            Optional<String> problem =
                    jwksUriProblem(jwksUri, configuration.isAllowInsecureTransport());
            if (problem.isPresent()) {
                throw new IllegalStateException(problem.get());
            }
            entries.put(issuer, ResolvedIdentityProvider.resolved(configuration, jwksUri, attempt));
            LOG.info(
                    "Identity provider '{}' resolved on attempt {}: issuer '{}' confirmed, signing"
                            + " keys at {}",
                    configuration.getName(),
                    attempt,
                    issuer,
                    jwksUri);
        } catch (Exception failure) {
            retryLater(configuration, attempt, failure);
        }
    }

    /**
     * Fetches the metadata via the module; {@code null} for an empty body. The module's {@link
     * DisabledBeanException} (cause discarded) becomes a retryable failure keeping its message.
     *
     * @throws IllegalStateException if the discovery request did not complete
     */
    private DefaultOpenIdProviderMetadata fetchMetadata(
            IdentityProviderConfiguration configuration) {
        try {
            return metadataFetchers.get(configuration.getIssuer()).fetch();
        } catch (DisabledBeanException unreachable) {
            throw new IllegalStateException(
                    "the discovery request to "
                            + configuration.getDiscoveryUrl()
                            + " did not complete: "
                            + unreachable.getMessage(),
                    unreachable);
        }
    }

    /**
     * Why a discovered {@code jwks_uri} is unusable (absent, not absolute http(s), or cleartext
     * without the entry's opt-in), or empty if usable. Every reason is retryable.
     */
    static Optional<String> jwksUriProblem(String jwksUri, boolean allowInsecureTransport) {
        if (jwksUri == null || jwksUri.isBlank()) {
            return Optional.of(
                    "the discovery document declares no jwks_uri, so no signing key can be"
                            + " located");
        }
        URI parsed;
        try {
            parsed = new URI(jwksUri);
        } catch (URISyntaxException malformed) {
            return Optional.of(
                    "the discovery document declares an unparseable jwks_uri: " + quote(jwksUri));
        }
        String scheme =
                parsed.getScheme() == null ? null : parsed.getScheme().toLowerCase(Locale.ROOT);
        if (scheme == null
                || !SUPPORTED_JWKS_URL_SCHEMES.contains(scheme)
                || parsed.getHost() == null) {
            return Optional.of(
                    "the discovery document declares a jwks_uri that is not an absolute "
                            + new TreeSet<>(SUPPORTED_JWKS_URL_SCHEMES)
                            + " URL with a host: "
                            + quote(jwksUri));
        }
        if (!SECURE_URL_SCHEME.equals(scheme) && !allowInsecureTransport) {
            return Optional.of(
                    "the discovery document declares a cleartext jwks_uri "
                            + quote(jwksUri)
                            + ", so the signing keys it serves could be substituted in transit and"
                            + " every token forged with the substituted key would validate. Have"
                            + " the provider publish an https jwks_uri, or - for a local provider"
                            + " only - set '"
                            + ALLOW_INSECURE_TRANSPORT_KEY
                            + ": true' on this entry to accept that risk explicitly");
        }
        return Optional.empty();
    }

    /** Single-quotes a value so an empty or padded one is visible in a log line. */
    private static String quote(String value) {
        return "'" + value + "'";
    }

    /** Marks a provider permanently unusable: its document named a different, non-blank issuer. */
    private void rejectPermanently(
            IdentityProviderConfiguration configuration, int attempt, String discoveredIssuer) {
        String reason =
                "the discovery document at %s declares issuer '%s', but '%s' is configured"
                        .formatted(
                                configuration.getDiscoveryUrl(),
                                discoveredIssuer,
                                configuration.getIssuer());
        entries.put(
                configuration.getIssuer(),
                ResolvedIdentityProvider.failed(configuration, reason, attempt));
        LOG.error(
                "Identity provider '{}' is permanently unusable and will not be retried: {}. This"
                        + " is a misconfiguration of {}, not an outage: correct the configuration"
                        + " and restart.",
                configuration.getName(),
                reason,
                configuration.getPropertyPath());
    }

    /** Records a retryable failure and queues the next attempt with exponential backoff. */
    private void retryLater(
            IdentityProviderConfiguration configuration, int attempt, Exception failure) {
        String reason = describe(failure);
        entries.put(
                configuration.getIssuer(),
                ResolvedIdentityProvider.retrying(configuration, reason, attempt));
        Duration delay = retryDelay(attempt);
        if (closed) {
            // A failure during shutdown is teardown, not an incident, and no retry follows.
            LOG.debug(
                    "Discovery for identity provider '{}' was interrupted by shutdown ({}); no"
                            + " retry is scheduled.",
                    configuration.getName(),
                    reason);
            return;
        }
        LOG.error(
                "Discovery for identity provider '{}' failed on attempt {} ({}); retrying in {}."
                        + " The service stays up and reports readiness DOWN until it succeeds.",
                configuration.getName(),
                attempt,
                reason,
                delay,
                failure);
        scheduleAttempt(configuration.getIssuer(), delay);
    }

    /** The backoff after {@code attempt} (at least 1) consecutive failures, capped at the max. */
    static Duration retryDelay(int attempt) {
        long maxMillis = MAX_DISCOVERY_RETRY_DELAY.toMillis();
        long millis = MIN_DISCOVERY_RETRY_DELAY.toMillis();
        for (int earlierFailures = 1;
                earlierFailures < attempt && millis < maxMillis;
                earlierFailures++) {
            millis *= DISCOVERY_RETRY_BACKOFF_MULTIPLIER;
        }
        return Duration.ofMillis(Math.min(millis, maxMillis));
    }

    /** One-line reason for logs and readiness; keeps the class name, as messages need it. */
    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        String type = failure.getClass().getSimpleName();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }

    /** Cancels outstanding retries and releases the discovery client on shutdown. */
    @Override
    @PreDestroy
    public void close() {
        closed = true;
        scheduledAttempts.values().forEach(attempt -> attempt.cancel(true));
        scheduledAttempts.clear();
        discoveryClient.close();
    }
}
