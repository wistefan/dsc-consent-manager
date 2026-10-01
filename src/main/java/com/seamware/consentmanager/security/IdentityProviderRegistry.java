package com.seamware.consentmanager.security;

import io.micronaut.context.annotation.Context;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.DefaultHttpClientConfiguration;
import io.micronaut.http.client.HttpClient;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.TaskScheduler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Named;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The service's trust list: every OpenID Connect provider whose tokens may be accepted, together
 * with what OIDC discovery has learned about each one.
 *
 * <p><strong>Membership is fixed at construction.</strong> The entries come from {@link
 * IdentityProviderRegistryValidator#getProviders()}, which is bound from configuration at startup
 * and validated before this bean exists. No method here adds, removes or replaces an entry, and
 * there is no code path by which an issuer nobody configured becomes known at runtime. Changing the
 * trust list requires a configuration change and a restart.
 *
 * <p>What <em>does</em> change after startup is the per-entry metadata for that fixed set: the
 * {@code jwks_uri} discovered for a provider and the {@link ResolutionState} of that discovery.
 * That is what lets a provider that was unreachable at boot become usable later without a restart.
 *
 * <p><strong>Startup never blocks and never fails on an identity provider.</strong> Discovery runs
 * on the scheduled executor, not on the thread that builds the context: an identity provider being
 * down must not stop this service from starting, because the alternative - a crash loop that waits
 * for someone else's outage - takes the service out of an orchestrator's hands entirely. Instead
 * the context comes up, {@code /health/liveness} is {@code UP}, {@code /health/readiness} is {@code
 * DOWN} via {@link IdentityProviderHealthIndicator}, and the instance is drained rather than killed
 * until discovery succeeds.
 *
 * <p><strong>Two kinds of failure, treated differently.</strong> A transport error, a timeout or an
 * error response is an outage: it is logged and retried with exponential backoff between {@link
 * #MIN_DISCOVERY_RETRY_DELAY} and {@link #MAX_DISCOVERY_RETRY_DELAY}. A discovery document whose
 * {@code issuer} disagrees with the configured one is a misconfiguration: retrying cannot fix it,
 * so the entry moves to {@link ResolutionState#FAILED} and is never polled again. That check is the
 * point of doing discovery at all - without it a tampered or mistaken {@code discovery-url} could
 * silently re-point a trust-list entry at a different provider's signing keys, and tokens minted by
 * that provider would authenticate as the configured one.
 *
 * <p>This type is thread-safe. Entries live in a {@link ConcurrentHashMap} whose key set never
 * changes, and each entry is replaced wholesale by the single scheduled task that owns it, so a
 * reader always sees a self-consistent {@link ResolvedIdentityProvider} snapshot.
 */
@Context
public class IdentityProviderRegistry implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(IdentityProviderRegistry.class);

    /**
     * Delay before the first retry after a provider's discovery attempt fails.
     *
     * <p>Short enough that a provider which was merely slow to start is picked up almost at once,
     * long enough not to hammer a provider that is genuinely down.
     */
    public static final Duration MIN_DISCOVERY_RETRY_DELAY = Duration.ofSeconds(2);

    /**
     * Ceiling on the retry delay.
     *
     * <p>Backoff stops growing here rather than continuing to double, so an outage lasting hours
     * still recovers within minutes of the provider returning instead of sitting out a retry
     * interval that has grown longer than the outage itself.
     */
    public static final Duration MAX_DISCOVERY_RETRY_DELAY = Duration.ofMinutes(5);

    /** Factor the retry delay is multiplied by after each consecutive failure. */
    private static final int DISCOVERY_RETRY_BACKOFF_MULTIPLIER = 2;

    /**
     * Connect and read timeout for a discovery request.
     *
     * <p>Bounded explicitly because the default client timeout is generous and an unresponsive
     * provider would otherwise hold a scheduled thread far longer than the retry interval.
     */
    private static final Duration DISCOVERY_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** Delay used for the very first attempt, scheduled rather than run inline so boot proceeds. */
    private static final Duration INITIAL_DISCOVERY_DELAY = Duration.ZERO;

    /** The fixed trust list, keyed by configured issuer. Keys never change; values do. */
    private final Map<String, ResolvedIdentityProvider> entries;

    /** The configured issuers in configuration order, so reports read the same way every time. */
    private final List<String> issuersInOrder;

    /** The outstanding retry per issuer, kept only so shutdown can cancel it. */
    private final Map<String, ScheduledFuture<?>> scheduledAttempts = new ConcurrentHashMap<>();

    private final TaskScheduler scheduler;
    private final HttpClient discoveryClient;

    /** Set on shutdown so an attempt already queued does not start fetching during teardown. */
    private volatile boolean closed;

    /**
     * Builds the registry from the validated configuration, leaving every entry {@link
     * ResolutionState#PENDING}.
     *
     * <p>Nothing is fetched here. {@link #startDiscovery()} schedules the first attempts once the
     * bean is fully constructed.
     *
     * @param validatedProviders the startup-validated trust list; injected as the validator rather
     *     than as a raw {@code List} so that its configuration checks are guaranteed to have run
     *     before any discovery is attempted
     * @param scheduler the scheduled executor that runs discovery off the startup thread
     */
    public IdentityProviderRegistry(
            IdentityProviderRegistryValidator validatedProviders,
            @Named(TaskExecutors.SCHEDULED) TaskScheduler scheduler) {
        this.scheduler = scheduler;
        this.discoveryClient = createDiscoveryClient();
        Map<String, ResolvedIdentityProvider> initial = new LinkedHashMap<>();
        for (IdentityProviderConfiguration configuration : validatedProviders.getProviders()) {
            initial.put(configuration.getIssuer(), ResolvedIdentityProvider.pending(configuration));
        }
        this.issuersInOrder = List.copyOf(initial.keySet());
        this.entries = new ConcurrentHashMap<>(initial);
    }

    /**
     * Creates the HTTP client discovery requests are issued with.
     *
     * <p>The client is built without a base URL because each provider's {@code discovery-url} is an
     * absolute URL of its own; requests therefore carry absolute URIs. It is created here rather
     * than injected so that its timeouts cannot be widened by unrelated global client configuration
     * - a discovery fetch is a background health probe and must stay strictly bounded.
     *
     * @return a client that accepts absolute request URIs
     */
    private static HttpClient createDiscoveryClient() {
        DefaultHttpClientConfiguration configuration = new DefaultHttpClientConfiguration();
        configuration.setConnectTimeout(DISCOVERY_REQUEST_TIMEOUT);
        configuration.setReadTimeout(DISCOVERY_REQUEST_TIMEOUT);
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
     * Returns the entry a token's {@code iss} claim maps to, if that entry is usable.
     *
     * <p>Only {@link ResolutionState#RESOLVED} entries are returned. A provider that is configured
     * but still {@link ResolutionState#PENDING}, or permanently {@link ResolutionState#FAILED}, is
     * reported exactly like an issuer that was never configured, so the caller's 401 reveals
     * nothing about which issuers this deployment trusts or how far its discovery has got. It also
     * means the key lookup can never be handed an entry that has no {@code jwks_uri}.
     *
     * @param issuer the issuer identifier to look up, typically a token's {@code iss} claim; may be
     *     {@code null}
     * @return the resolved entry, or {@link Optional#empty()} if the issuer is unknown or its entry
     *     is not yet usable
     */
    public Optional<ResolvedIdentityProvider> findByIssuer(String issuer) {
        if (issuer == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(issuer)).filter(ResolvedIdentityProvider::isUsable);
    }

    /**
     * Returns every configured entry, usable or not, in configuration order.
     *
     * <p>This is the readiness view, not the validation view: it deliberately exposes providers
     * {@link #findByIssuer(String)} hides, because an operator reading {@code /health/readiness}
     * needs to see exactly which provider is holding the instance back.
     *
     * @return an unmodifiable snapshot of the trust list
     */
    public List<ResolvedIdentityProvider> snapshot() {
        List<ResolvedIdentityProvider> snapshot = new ArrayList<>(issuersInOrder.size());
        for (String issuer : issuersInOrder) {
            ResolvedIdentityProvider entry = entries.get(issuer);
            if (entry != null) {
                snapshot.add(entry);
            }
        }
        return Collections.unmodifiableList(snapshot);
    }

    /**
     * Reports whether every configured provider has been discovered successfully.
     *
     * @return {@code true} if no entry is still pending or permanently failed
     */
    public boolean isFullyResolved() {
        return snapshot().stream().allMatch(ResolvedIdentityProvider::isUsable);
    }

    /**
     * Queues one discovery attempt for one provider.
     *
     * @param issuer the configured issuer identifying the entry
     * @param delay how long to wait before attempting
     */
    private void scheduleAttempt(String issuer, Duration delay) {
        if (closed) {
            return;
        }
        scheduledAttempts.put(issuer, scheduler.schedule(delay, () -> attemptDiscovery(issuer)));
    }

    /**
     * Fetches one provider's metadata and advances its entry.
     *
     * <p>Runs on the scheduled executor. Only one attempt per issuer is ever in flight, because the
     * next one is scheduled from inside this method, so replacing the entry needs no further
     * synchronisation.
     *
     * @param issuer the configured issuer identifying the entry
     */
    private void attemptDiscovery(String issuer) {
        ResolvedIdentityProvider current = entries.get(issuer);
        if (closed || current == null || current.state() != ResolutionState.PENDING) {
            return;
        }
        IdentityProviderConfiguration configuration = current.configuration();
        int attempt = current.attempts() + 1;
        try {
            OpenIdProviderMetadata metadata = fetchMetadata(configuration);
            String discoveredIssuer = metadata == null ? null : metadata.issuer();
            if (!issuer.equals(discoveredIssuer)) {
                rejectPermanently(configuration, attempt, discoveredIssuer);
                return;
            }
            String jwksUri = metadata.jwksUri();
            if (jwksUri == null || jwksUri.isBlank()) {
                throw new IllegalStateException(
                        "the discovery document declares no jwks_uri, so no signing key can be"
                                + " located");
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
     * Performs the discovery request itself.
     *
     * @param configuration the provider whose {@code discovery-url} to fetch
     * @return the parsed metadata document, or {@code null} if the provider returned an empty body
     */
    private OpenIdProviderMetadata fetchMetadata(IdentityProviderConfiguration configuration) {
        HttpRequest<?> request =
                HttpRequest.GET(URI.create(configuration.getDiscoveryUrl()))
                        .accept(MediaType.APPLICATION_JSON_TYPE);
        return discoveryClient.toBlocking().retrieve(request, OpenIdProviderMetadata.class);
    }

    /**
     * Marks a provider permanently unusable because its discovery document named another issuer.
     *
     * @param configuration the provider whose document disagreed
     * @param attempt the attempt number that produced the mismatch
     * @param discoveredIssuer the issuer the document declared, possibly {@code null}
     */
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

    /**
     * Records a retryable failure and queues the next attempt with exponential backoff.
     *
     * @param configuration the provider whose attempt failed
     * @param attempt the attempt number that failed
     * @param failure what went wrong
     */
    private void retryLater(
            IdentityProviderConfiguration configuration, int attempt, Exception failure) {
        String reason = describe(failure);
        entries.put(
                configuration.getIssuer(),
                ResolvedIdentityProvider.retrying(configuration, reason, attempt));
        Duration delay = retryDelay(attempt);
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

    /**
     * Computes the backoff delay after a given number of consecutive failures.
     *
     * @param attempt how many attempts have failed, the latest included; always at least 1
     *     <p>Package-private rather than private so the backoff curve can be asserted directly.
     *     Driving it through the scheduler instead would mean a test that waits out real delays to
     *     observe a pure function.
     * @return {@link #MIN_DISCOVERY_RETRY_DELAY} doubled once per earlier failure, capped at {@link
     *     #MAX_DISCOVERY_RETRY_DELAY}
     */
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

    /**
     * Renders an exception as a one-line reason for logs and for the readiness report.
     *
     * <p>The class name is kept alongside the message because the most common discovery failures -
     * a connection refusal, a DNS miss, a read timeout - carry messages that are meaningless
     * without it.
     *
     * @param failure the exception to describe
     * @return a short human-readable description
     */
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
