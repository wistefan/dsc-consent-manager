package com.seamware.consentmanager.notification;

import com.seamware.consentmanager.client.NetworkAddressPolicy;
import com.seamware.consentmanager.config.ConsentManagerConfiguration.ParticipantNotifications;
import com.seamware.consentmanager.signing.ConsentSigningService;
import com.seamware.consentmanager.signing.ReceiptSigningException;
import com.seamware.consentmanager.signing.SignatureType;
import com.seamware.consentmanager.signing.SignedReceipt;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.client.exceptions.ReadTimeoutException;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Makes one delivery attempt of a notification: vets the endpoint, signs, POSTs, classifies. Never
 * throws; every failure is a {@link DeliveryOutcome}. Blocks, so never call it on an event loop.
 *
 * <p>The body is exactly the RFC 8785 text the detached JWS in {@value #SIGNATURE_HEADER} covers,
 * typed {@link SignatureType#NOTIFICATION}, so a recipient verifies the bytes it received against
 * {@code /.well-known/jwks.json}. An endpoint is refused before any connect unless it is https and
 * either named by a non-empty {@code allowed-hosts} or resolving only to external addresses.
 */
@Singleton
public class ParticipantWebhookClient {

    /** Request header carrying the detached compact JWS over the body. */
    public static final String SIGNATURE_HEADER = "X-Consent-Signature";

    static final String ENDPOINT_NOT_PERMITTED = "endpoint not permitted";

    static final String NAME_RESOLUTION_FAILED = "name resolution failed";

    static final String READ_TIMEOUT = "read timeout";

    static final String CONNECT_TIMEOUT = "connect timeout";

    static final String CONNECTION_REFUSED = "connection refused";

    static final String IO_ERROR = "I/O error";

    static final String SIGNING_FAILED = "signing failed";

    /** Lookups run here so {@code resolveTimeout} can bound them; a full pool fails the lookup. */
    static final int RESOLVER_THREADS = 8;

    static final int RESOLVER_QUEUE_CAPACITY = 64;

    private static final String RESOLVER_THREAD_PREFIX = "webhook-resolver-";

    private static final String HTTPS_SCHEME = "https";

    /** Netty's connect-timeout exception, named so this class needs no Netty dependency. */
    private static final String CONNECT_TIMEOUT_EXCEPTION = "ConnectTimeoutException";

    private final HttpClient http;
    private final ConsentSigningService signing;
    private final HostResolver resolver;
    private final Set<String> allowedHosts;
    private final long resolveTimeoutNanos;
    private final ExecutorService resolverPool;

    public ParticipantWebhookClient(
            @Named(ParticipantWebhookHttpClientFactory.NAME) HttpClient http,
            ConsentSigningService signing,
            HostResolver resolver,
            ParticipantNotifications settings) {
        this.http = http;
        this.signing = signing;
        this.resolver = resolver;
        this.allowedHosts = lowerCased(settings.getAllowedHosts());
        this.resolveTimeoutNanos = settings.getResolveTimeout().toNanos();
        this.resolverPool =
                new ThreadPoolExecutor(
                        RESOLVER_THREADS,
                        RESOLVER_THREADS,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(RESOLVER_QUEUE_CAPACITY),
                        daemonThreads());
    }

    /** Posts {@code payload} to {@code endpoint} once and reports how that went. */
    @NonNull
    public DeliveryOutcome send(@NonNull String endpoint, @NonNull NotificationPayload payload) {
        Optional<URI> uri = vetted(endpoint);
        if (uri.isEmpty()) {
            return DeliveryOutcome.abandon(ENDPOINT_NOT_PERMITTED);
        }
        String host = uri.get().getHost().toLowerCase(Locale.ROOT);
        if (!allowedHosts.contains(host)) {
            Optional<InetAddress[]> addresses = resolve(host);
            if (addresses.isEmpty()) {
                return DeliveryOutcome.retry(NAME_RESOLUTION_FAILED);
            }
            if (!NetworkAddressPolicy.allExternal(addresses.get())) {
                return DeliveryOutcome.abandon(ENDPOINT_NOT_PERMITTED);
            }
        }
        SignedReceipt<NotificationPayload> signed;
        try {
            signed = signing.sign(payload, SignatureType.NOTIFICATION);
        } catch (ReceiptSigningException e) {
            return DeliveryOutcome.retry(SIGNING_FAILED);
        }
        return post(uri.get(), signed);
    }

    @PreDestroy
    void shutdown() {
        resolverPool.shutdownNow();
    }

    /**
     * The endpoint as a URI when its scheme is https, it has a host, and a non-empty allow-list
     * names that host; empty otherwise. The address check is left to the caller.
     */
    private Optional<URI> vetted(String endpoint) {
        URI uri;
        try {
            uri = new URI(endpoint);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null
                || !HTTPS_SCHEME.equals(scheme.toLowerCase(Locale.ROOT))
                || host == null) {
            return Optional.empty();
        }
        if (!allowedHosts.isEmpty() && !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        return Optional.of(uri);
    }

    /** The host's addresses, or empty when the lookup failed, timed out or could not start. */
    private Optional<InetAddress[]> resolve(String host) {
        Future<InetAddress[]> lookup;
        try {
            lookup = resolverPool.submit(() -> resolver.resolve(host));
        } catch (RejectedExecutionException e) {
            return Optional.empty();
        }
        try {
            return Optional.of(lookup.get(resolveTimeoutNanos, TimeUnit.NANOSECONDS));
        } catch (TimeoutException | ExecutionException e) {
            lookup.cancel(true);
            return Optional.empty();
        } catch (InterruptedException e) {
            lookup.cancel(true);
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** Sends the signed body and classifies the response or transport failure. */
    private DeliveryOutcome post(URI uri, SignedReceipt<NotificationPayload> signed) {
        HttpRequest<byte[]> request =
                HttpRequest.POST(uri, signed.canonicalJson().getBytes(StandardCharsets.UTF_8))
                        .contentType(MediaType.APPLICATION_JSON_TYPE)
                        .header(SIGNATURE_HEADER, signed.jws());
        try {
            HttpResponse<?> response = http.toBlocking().exchange(request);
            return DeliveryOutcome.forStatus(response.code());
        } catch (HttpClientResponseException e) {
            return DeliveryOutcome.forStatus(e.code());
        } catch (ReadTimeoutException e) {
            return DeliveryOutcome.retry(READ_TIMEOUT);
        } catch (HttpClientException e) {
            return DeliveryOutcome.retry(transportReason(e));
        } catch (RuntimeException e) {
            return DeliveryOutcome.retry(IO_ERROR);
        }
    }

    /** A short name for a transport failure, read from its cause chain. */
    private static String transportReason(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getClass().getSimpleName().equals(CONNECT_TIMEOUT_EXCEPTION)) {
                return CONNECT_TIMEOUT;
            }
            if (cause instanceof ConnectException) {
                return CONNECTION_REFUSED;
            }
        }
        return IO_ERROR;
    }

    private static Set<String> lowerCased(List<String> hosts) {
        return hosts.stream()
                .map(host -> host.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread =
                    new Thread(runnable, RESOLVER_THREAD_PREFIX + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
