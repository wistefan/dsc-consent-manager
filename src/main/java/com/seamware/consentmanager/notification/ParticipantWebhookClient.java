package com.seamware.consentmanager.notification;

import com.seamware.consentmanager.client.NetworkAddressPolicy;
import com.seamware.consentmanager.config.ConsentManagerConfiguration.ParticipantNotifications;
import com.seamware.consentmanager.signing.ConsentSigningService;
import com.seamware.consentmanager.signing.ReceiptSigningException;
import com.seamware.consentmanager.signing.SignatureType;
import com.seamware.consentmanager.signing.SignedReceipt;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.io.buffer.ByteArrayBufferFactory;
import io.micronaut.http.ByteBodyHttpResponse;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.CloseableByteBody;
import io.micronaut.http.body.stream.AvailableByteArrayBody;
import io.micronaut.http.client.RawHttpClient;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.http.client.exceptions.ReadTimeoutException;
import io.netty.channel.ConnectTimeoutException;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;

/**
 * Makes one delivery attempt of a notification: vets the endpoint, signs, POSTs, classifies. Never
 * throws; every failure is a {@link DeliveryOutcome}. Blocks, so never call it on an event loop.
 *
 * <p>The body is exactly the RFC 8785 text the detached JWS in {@value #SIGNATURE_HEADER} covers,
 * typed {@link SignatureType#NOTIFICATION}, so a recipient verifies the bytes it received against
 * {@code /.well-known/jwks.json}. An endpoint is refused before any connect unless it is https and
 * either named by a non-empty {@code allowed-hosts} or resolving only to external addresses.
 * Concurrent lookups of one host share a single resolver thread, so a host whose DNS stalls cannot
 * starve the others' lookups; the response body is never read. One attempt takes at most {@code
 * resolveTimeout + connectTimeout + readTimeout}, however the peer behaves.
 */
@Singleton
public class ParticipantWebhookClient {

    /** Request header carrying the detached compact JWS over the body. */
    public static final String SIGNATURE_HEADER = "X-Consent-Signature";

    static final String ENDPOINT_NOT_PERMITTED = "endpoint not permitted";

    static final String NAME_RESOLUTION_FAILED = "name resolution failed";

    static final String READ_TIMEOUT = "read timeout";

    static final String CONNECT_TIMEOUT = "connect timeout";

    static final String RESPONSE_TIMEOUT = "response timeout";

    static final String CONNECTION_REFUSED = "connection refused";

    static final String IO_ERROR = "I/O error";

    static final String SIGNING_FAILED = "signing failed";

    /** Distinct hosts awaiting a resolver thread; beyond it a lookup fails at once. */
    static final int RESOLVER_QUEUE_CAPACITY = 64;

    private static final String RESOLVER_THREAD_PREFIX = "webhook-resolver-";

    private static final String HTTPS_SCHEME = "https";

    private final RawHttpClient http;
    private final ConsentSigningService signing;
    private final HostResolver resolver;
    private final Set<String> allowedHosts;
    private final long resolveTimeoutNanos;

    /**
     * Total budget of one exchange, connect included. Needed because the client's read timeout is
     * an idle limit and the TLS handshake has its own, so a peer that stalls the handshake or drips
     * its response would otherwise outlast {@code connectTimeout + readTimeout}.
     */
    private final Duration exchangeDeadline;

    private final ExecutorService resolverPool;

    /** Lookups still running, by host; an entry is removed before its future completes. */
    private final ConcurrentMap<String, CompletableFuture<InetAddress[]>> inFlight =
            new ConcurrentHashMap<>();

    public ParticipantWebhookClient(
            @Named(ParticipantWebhookHttpClientFactory.NAME) RawHttpClient http,
            ConsentSigningService signing,
            HostResolver resolver,
            ParticipantNotifications settings) {
        this.http = http;
        this.signing = signing;
        this.resolver = resolver;
        this.allowedHosts = lowerCased(settings.getAllowedHosts());
        this.resolveTimeoutNanos = settings.getResolveTimeout().toNanos();
        this.exchangeDeadline = settings.getConnectTimeout().plus(settings.getReadTimeout());
        this.resolverPool =
                new ThreadPoolExecutor(
                        settings.getResolverThreads(),
                        settings.getResolverThreads(),
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

    /**
     * The host's addresses, or empty when the lookup failed, timed out or could not start. A
     * timed-out lookup is left running rather than cancelled: the system resolver cannot be
     * interrupted, and later senders to the same host keep waiting on it.
     */
    private Optional<InetAddress[]> resolve(String host) {
        Optional<CompletableFuture<InetAddress[]>> lookup = lookup(host);
        if (lookup.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(lookup.get().get(resolveTimeoutNanos, TimeUnit.NANOSECONDS));
        } catch (TimeoutException | ExecutionException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** Joins the running lookup of {@code host}, or starts one; empty when the pool is full. */
    private Optional<CompletableFuture<InetAddress[]>> lookup(String host) {
        CompletableFuture<InetAddress[]> started = new CompletableFuture<>();
        CompletableFuture<InetAddress[]> running = inFlight.putIfAbsent(host, started);
        if (running != null) {
            return Optional.of(running);
        }
        try {
            resolverPool.execute(() -> runLookup(host, started));
        } catch (RejectedExecutionException e) {
            inFlight.remove(host, started);
            started.completeExceptionally(e);
            return Optional.empty();
        }
        return Optional.of(started);
    }

    private void runLookup(String host, CompletableFuture<InetAddress[]> lookup) {
        try {
            InetAddress[] addresses = resolver.resolve(host);
            inFlight.remove(host, lookup);
            lookup.complete(addresses);
        } catch (Exception e) {
            inFlight.remove(host, lookup);
            lookup.completeExceptionally(e);
        }
    }

    /** Sends the signed body and classifies the response or transport failure. */
    private DeliveryOutcome post(URI uri, SignedReceipt<NotificationPayload> signed) {
        HttpRequest<?> request =
                HttpRequest.POST(uri, null)
                        .contentType(MediaType.APPLICATION_JSON_TYPE)
                        .header(SIGNATURE_HEADER, signed.jws());
        CloseableByteBody body =
                AvailableByteArrayBody.create(
                        ByteArrayBufferFactory.INSTANCE,
                        signed.canonicalJson().getBytes(StandardCharsets.UTF_8));
        try {
            ByteBodyHttpResponse<?> response = exchange(request, body);
            if (response == null) {
                return DeliveryOutcome.retry(IO_ERROR);
            }
            // The raw exchange yields the status without buffering the body; closing discards it.
            try (response) {
                return DeliveryOutcome.forStatus(response.code());
            }
        } catch (TimeoutException e) {
            return DeliveryOutcome.retry(RESPONSE_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DeliveryOutcome.retry(IO_ERROR);
        } catch (ReadTimeoutException e) {
            return DeliveryOutcome.retry(READ_TIMEOUT);
        } catch (HttpClientException e) {
            return DeliveryOutcome.retry(transportReason(e));
        } catch (RuntimeException e) {
            return DeliveryOutcome.retry(IO_ERROR);
        }
    }

    /**
     * Runs the exchange under {@link #exchangeDeadline}; null when it completed empty. A response
     * that loses the race with the deadline is closed, never dropped, so its connection is freed.
     */
    @Nullable
    private ByteBodyHttpResponse<?> exchange(HttpRequest<?> request, CloseableByteBody body)
            throws TimeoutException, InterruptedException {
        ExchangeSubscriber subscriber = new ExchangeSubscriber();
        http.exchange(request, body, null).subscribe(subscriber);
        try {
            return subscriber.response.get(exchangeDeadline.toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException failure
                    ? failure
                    : new IllegalStateException(e.getCause());
        } catch (TimeoutException | InterruptedException e) {
            subscriber.abandon();
            throw e;
        }
    }

    /** A short name for a transport failure, read from its cause chain. */
    private static String transportReason(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            // Before ConnectException, which Netty's connect timeout extends.
            if (cause instanceof ConnectTimeoutException) {
                return CONNECT_TIMEOUT;
            }
            if (cause instanceof ConnectException) {
                return CONNECTION_REFUSED;
            }
        }
        return IO_ERROR;
    }

    /**
     * Hands the exchange's response to whoever still wants it, else closes it. Unlike Reactor's
     * {@code timeout}/{@code block}, it never drops a response emitted after the caller gave up.
     */
    private static final class ExchangeSubscriber implements CoreSubscriber<HttpResponse<?>> {

        private final CompletableFuture<ByteBodyHttpResponse<?>> response =
                new CompletableFuture<>();
        private final AtomicReference<Subscription> subscription = new AtomicReference<>();
        private volatile boolean cancelled;

        @Override
        public void onSubscribe(Subscription s) {
            if (!subscription.compareAndSet(null, s)) {
                s.cancel();
                return;
            }
            s.request(Long.MAX_VALUE);
            if (cancelled) {
                s.cancel();
            }
        }

        @Override
        public void onNext(HttpResponse<?> item) {
            ByteBodyHttpResponse<?> raw = (ByteBodyHttpResponse<?>) item;
            if (!response.complete(raw)) {
                raw.close();
            }
        }

        @Override
        public void onError(Throwable failure) {
            response.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            response.complete(null);
        }

        /** Gives up on the exchange: closes a response already handed over and cancels it. */
        void abandon() {
            response.completeExceptionally(new CancellationException());
            response.thenAccept(
                    won -> {
                        if (won != null) {
                            won.close();
                        }
                    });
            cancelled = true;
            Subscription s = subscription.get();
            if (s != null) {
                s.cancel();
            }
        }
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
