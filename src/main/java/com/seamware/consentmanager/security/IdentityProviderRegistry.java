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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
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
 * <p><strong>Two kinds of failure, treated differently.</strong> A transport error, a timeout, an
 * error response, an empty or truncated body and a document that is silent about its issuer are all
 * outages: they are logged and retried with exponential backoff between {@link
 * #MIN_DISCOVERY_RETRY_DELAY} and {@link #MAX_DISCOVERY_RETRY_DELAY}. Only a document that declares
 * a <em>different</em> issuer is a misconfiguration: retrying cannot fix it, so the entry moves to
 * {@link ResolutionState#FAILED} and is never polled again. The asymmetry is deliberate - a
 * permanent failure needs a restart to clear, so nothing that a provider might recover from on its
 * own is allowed into that branch. That check is the point of doing discovery at all - without it a
 * tampered or mistaken {@code discovery-url} could silently re-point a trust-list entry at a
 * different provider's signing keys, and tokens minted by that provider would authenticate as the
 * configured one.
 *
 * <p>An error <em>status</em> is an outage too, 404 included. A 404 at a {@code .well-known} path
 * is often a mistyped {@code discovery-url}, which no amount of retrying fixes - but it is equally
 * what a gateway with no healthy backend, a provider whose realm is still being imported or a
 * half-finished deployment returns, and guessing wrong is not symmetric: a retried misconfiguration
 * costs an ERROR line per backoff interval until somebody reads it, while a condemned outage needs
 * a restart nobody may know to perform. The same reasoning covers a document whose {@code jwks_uri}
 * is missing, malformed or cleartext - the provider has not claimed to be anybody else, so the
 * entry stays retryable.
 *
 * <p><strong>The transport rule covers the whole fetch chain.</strong> {@link
 * IdentityProviderRegistryValidator} refuses a cleartext {@code issuer} or {@code discovery-url}
 * unless the entry opts in, and justifies that by what the document carries: the issuer that is
 * verified and the {@code jwks_uri} signing keys are fetched from. A provider served over https can
 * still publish an {@code http} JWK Set URL, so that second half of the chain is checked here, by
 * {@link #jwksUriProblem(String, boolean)}, under the same per-entry opt-in. It is checked at this
 * point - where the URL would become part of the trust anchor - rather than when step 4 first
 * fetches from it, because by then the entry has already been published to {@link
 * #findByIssuer(String)} callers as usable.
 *
 * <p><strong>Discovery itself is {@code micronaut-security-oauth2}'s.</strong> The request to a
 * provider's {@code discovery-url} and the parsing of the OpenID Provider Metadata document it
 * returns are performed by that module's {@link DefaultOpenIdProviderMetadataFetcher}, over its
 * {@link DefaultOpenIdProviderMetadata} model. This service therefore composes no {@code
 * .well-known} path, issues no discovery request and declares no metadata type of its own.
 *
 * <p>The module is consumed through its {@link
 * io.micronaut.security.oauth2.configuration.OpenIdClientConfiguration} interface rather than
 * through its {@code micronaut.security.oauth2.clients.*} configuration binding, because that
 * binding models a provider as a <em>login client</em>: it requires a client id and switches on
 * authorization-code login routes, which a service that issues no tokens, stores no credentials and
 * keeps no sessions must not expose. {@link OpenIdClientConfigurationAdapter} presents one
 * trust-list entry in the shape the fetcher expects, so only the discovery half of the module is
 * taken. The reasoning is recorded in {@code
 * docs/adr/0003-use-micronaut-security-oauth2-for-openid-discovery.md}, which supersedes ADR 0002.
 *
 * <p>What this class owns around that call is what the module has no opinion about: the trust list
 * and its resolution state, the byte-for-byte issuer comparison (AC 2), the retry schedule that
 * keeps an identity provider's outage from failing startup (AC 3), and the per-issuer routing the
 * token validator looks up. The JWS verification, the key cache, the rate limiter and the
 * single-flight refresh are Nimbus's (step 4), because that is where a subtle concurrency bug would
 * be a security bug.
 *
 * <p><strong>A resolved entry is not re-discovered.</strong> Once a provider reaches {@link
 * ResolutionState#RESOLVED} its {@code jwks_uri} is kept for the life of the process. Key
 * <em>rotation</em> does not need re-discovery - the keys are refetched from that same URL by the
 * JWKS cache (step 4), which is what AC 13 asks for. Only a provider that *moves* its JWK Set to a
 * different URL goes stale, and that is a deliberate trade: a periodic re-resolve would have to
 * decide what to do when the re-fetched document disagrees with the one the trust list was built
 * on, and silently re-pointing a live trust anchor from a background task is exactly the
 * substitution the byte-for-byte issuer check exists to prevent. Moving a JWK Set is a
 * configuration event on the provider's side; it is handled by restarting this service, like any
 * other change to the trust list (convention 5).
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

    /**
     * URL schemes a JWK Set may be published under.
     *
     * <p>The keys are fetched over the network like any other document, so the two HTTP schemes are
     * the only meaningful ones. Mirrors the rule {@link IdentityProviderRegistryValidator} applies
     * to the configured URLs, applied here to the one URL that is discovered rather than
     * configured.
     */
    private static final Set<String> SUPPORTED_JWKS_URL_SCHEMES = Set.of("http", "https");

    /** The only scheme that authenticates a JWK Set in transit. */
    private static final String SECURE_URL_SCHEME = "https";

    /** Per-entry opt-in quoted in the failure reason when a cleartext JWK Set URL is refused. */
    private static final String ALLOW_INSECURE_TRANSPORT_KEY = "allow-insecure-transport";

    /** The fixed trust list, keyed by configured issuer. Keys never change; values do. */
    private final Map<String, ResolvedIdentityProvider> entries;

    /**
     * The configured issuers ordered by provider name, so reports read the same way every time.
     *
     * <p>{@link IdentityProviderRegistryValidator} sorts the trust list by entry name before
     * handing it over, and that order is preserved here; it is not the order the entries happen to
     * appear in the configuration source, which for a YAML map is not meaningful anyway.
     */
    private final List<String> issuersInOrder;

    /** The outstanding retry per issuer, kept only so shutdown can cancel it. */
    private final Map<String, ScheduledFuture<?>> scheduledAttempts = new ConcurrentHashMap<>();

    /**
     * One {@code micronaut-security-oauth2} metadata fetcher per configured issuer.
     *
     * <p>Built once, in the constructor, from the same fixed trust list the entries come from, so
     * this map has exactly the key set of {@link #entries} and never changes either.
     */
    private final Map<String, OpenIdProviderMetadataFetcher> metadataFetchers;

    private final TaskScheduler scheduler;

    /**
     * Where the discovery request itself runs.
     *
     * <p>The fetch is blocking and bounded by {@link #DISCOVERY_REQUEST_TIMEOUT}, so running it on
     * the scheduled executor would park a pool Micronaut sizes from the available processors for up
     * to twenty seconds per unreachable provider. On a small container a handful of providers being
     * down would then serialise their own retries and delay every other scheduled task in the
     * process - including the JWKS refresh of step 4 - behind somebody else's outage. The scheduler
     * is therefore used for timing only and hands each attempt straight to the blocking pool, which
     * is virtual-thread-backed and expects exactly this.
     */
    private final ExecutorService discoveryExecutor;

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
     * @param scheduler the scheduled executor that times discovery attempts, off the startup thread
     * @param discoveryExecutor the blocking executor each attempt's HTTP fetch is run on, so a slow
     *     provider cannot occupy the scheduler
     * @param globalClientConfiguration the application's {@code micronaut.http.client} settings,
     *     whose TLS and proxy configuration the discovery client inherits
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
     * Creates the HTTP client discovery requests are issued with.
     *
     * <p>The client is built without a base URL because each provider's {@code discovery-url} is an
     * absolute URL of its own; requests therefore carry absolute URIs.
     *
     * <p>It starts from the application's own {@code micronaut.http.client} settings rather than
     * from a bare default, so that the things an operator can only express as client configuration
     * still apply: a provider fronted by a private certificate authority, or reachable only through
     * an HTTP proxy, is discoverable by configuring {@code micronaut.http.client.ssl.*} and the
     * proxy properties like any other client. That matters more now that https is effectively
     * mandatory for a provider URL - otherwise the only way to trust a private CA here would be a
     * JVM-wide trust store, which nothing in this service's configuration would hint at.
     *
     * <p>Two things are then pinned on top and are deliberately not configurable: the timeouts,
     * because a discovery fetch is a background probe whose worst case must stay well inside the
     * retry interval, and the redirect policy. A redirect on the discovery URL would otherwise be
     * followed silently, including to another host, quietly moving the trust anchor somewhere the
     * operator never configured; a provider that has moved its metadata is a configuration change,
     * so a 3xx is surfaced as a failure rather than chased.
     *
     * @param globalClientConfiguration the application's default client configuration, copied
     *     rather than mutated - it is a shared bean every other client in the process also uses
     * @return a client that accepts absolute request URIs
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
     * Returns every configured entry, usable or not, ordered by provider name.
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
        scheduledAttempts.put(
                issuer,
                scheduler.schedule(
                        delay, () -> discoveryExecutor.execute(() -> attemptDiscovery(issuer))));
    }

    /**
     * Fetches one provider's metadata and advances its entry.
     *
     * <p>Runs on the blocking executor, never on the scheduler. Only one attempt per issuer is ever
     * in flight, because the next one is scheduled from inside this method, so replacing the entry
     * needs no further synchronisation.
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
            DefaultOpenIdProviderMetadata metadata = fetchMetadata(configuration);
            // A document that is absent or silent about its issuer says nothing about whether the
            // provider is the configured one, so it cannot condemn the entry. An empty 200 is what
            // a reverse proxy mid-reload, a truncated response or a load balancer with no healthy
            // backend returns - all outages - and only a document that names a *different* issuer
            // is the misconfiguration that must never be retried.
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
     * Performs the discovery request, by handing the entry to {@code micronaut-security-oauth2}.
     *
     * <p>The fetcher composes the metadata URL, issues the request and deserializes the OpenID
     * Provider Metadata document; nothing of that is written here.
     *
     * <p>It reports a request that did not complete - a connection failure, a timeout, a redirect
     * this client refuses to follow, an error status - as a {@link DisabledBeanException}, named
     * for what the module would do about it: give that provider up for the life of the process.
     * That is the one outcome AC 3 rules out, so it is translated into an ordinary failure and the
     * caller retries it like any other outage. The message is replaced along with it, because the
     * module's wording describes a disabled bean, which is not something that happens here.
     *
     * @param configuration the provider whose {@code discovery-url} to fetch
     * @return the parsed metadata document, or {@code null} if the provider returned an empty body
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
                            + " did not complete",
                    unreachable);
        }
    }

    /**
     * Checks the {@code jwks_uri} a discovery document hands back, before it is kept as part of the
     * trust anchor.
     *
     * <p>Three things disqualify it. It may be absent, in which case there is nowhere to fetch
     * signing keys from. It may not be an absolute {@code http} or {@code https} URL with a host,
     * which a resource server cannot fetch at all. Or it may be cleartext {@code http} while the
     * entry has not opted into insecure transport: keys fetched over cleartext can be substituted
     * by anyone on the network path, and a substituted JWK Set makes every token the attacker mints
     * for that issuer validate - which is precisely the risk the opt-in exists to make an operator
     * acknowledge, so it must not be bypassable by an https document that points at an http URL.
     *
     * <p>All three are reported as retryable reasons rather than permanent failures: none of them
     * is the provider claiming to be somebody else, and all three are things the provider can
     * correct in its own document without this service restarting.
     *
     * <p>Package-private so the rules can be asserted directly; an end-to-end test cannot reach the
     * cleartext case, because a stub reachable over {@code http} at all requires the very opt-in
     * that suppresses it.
     *
     * @param jwksUri the {@code jwks_uri} member of the discovery document; may be {@code null}
     * @param allowInsecureTransport whether this entry has opted into cleartext, per {@link
     *     IdentityProviderConfiguration#isAllowInsecureTransport()}
     * @return the reason the URL is unusable, or {@link Optional#empty()} if keys may be fetched
     *     from it
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

    /**
     * Wraps a configured or discovered value in quotes so an empty or padded one is visible in a
     * log line.
     *
     * @param value the value to render
     * @return the value in single quotes
     */
    private static String quote(String value) {
        return "'" + value + "'";
    }

    /**
     * Marks a provider permanently unusable because its discovery document named another issuer.
     *
     * <p>Only ever called for a document that declares a non-blank issuer differing from the
     * configured one. A missing or blank issuer is an outage, not a misconfiguration, and is
     * retried by {@link #retryLater(IdentityProviderConfiguration, int, Exception)} instead.
     *
     * @param configuration the provider whose document disagreed
     * @param attempt the attempt number that produced the mismatch
     * @param discoveredIssuer the issuer the document declared, never {@code null} or blank
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
        if (closed) {
            // Shutdown cancels in-flight attempts and closes the client, so an attempt that was
            // already running fails here on the way down. That is teardown, not an incident, and
            // `scheduleAttempt` will not queue anything either - so logging it at ERROR with a
            // "retrying in ..." that is not going to happen would be both alarming and untrue. A
            // test suite that builds a context per class would print a stream of them.
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
