package com.seamware.consentmanager.notification;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.seamware.consentmanager.config.ConsentManagerConfiguration.ParticipantNotifications;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.notification.DeliveryOutcome.Result;
import com.seamware.consentmanager.signing.CanonicalJsonSerializer;
import com.seamware.consentmanager.signing.ConsentSigningService;
import com.seamware.consentmanager.signing.SignatureType;
import com.seamware.consentmanager.support.Await;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.http.ByteBodyHttpResponse;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.body.CloseableByteBody;
import io.micronaut.http.client.DefaultHttpClientConfiguration;
import io.micronaut.http.client.RawHttpClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;

/**
 * One webhook delivery attempt against an in-process HTTPS stub. The stub's certificate is made per
 * run and trusted only by the client this test builds; main config never trusts it.
 */
@DisplayName("Participant webhook client")
class ParticipantWebhookClientTest {

    private static final String HOOK_PATH = "/hooks/consent";

    private static final String REDIRECT_TARGET_PATH = "/hooks/elsewhere";

    private static final String LOOPBACK_HOST = "localhost";

    /** A name only the stubbed resolver answers; the system resolver never sees it. */
    private static final String EXTERNAL_HOST = "webhooks.example.org";

    private static final String EXTERNAL_ENDPOINT = "https://" + EXTERNAL_HOST + HOOK_PATH;

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(1);

    private static final Duration RESOLVE_TIMEOUT = Duration.ofMillis(300);

    /** Also the TLS handshake timeout, so a silent peer fails the handshake within the deadline. */
    private static final Duration TEST_CONNECT_TIMEOUT = Duration.ofSeconds(1);

    private static final int BACKLOG = 1;

    private static final int FILLER_CONNECT_MILLIS = 200;

    private static final int REQUEST_READ_BYTES = 8_192;

    private static final String TLS_PROTOCOL = "TLS";

    private static final byte[] DRIP_HEAD =
            "HTTP/1.1 200 OK\r\nX-Drip: ".getBytes(StandardCharsets.US_ASCII);

    private static final int DRIP_BYTE = 'a';

    /** Well under the read timeout, so an idle limit alone never fires. */
    private static final Duration DRIP_INTERVAL = Duration.ofMillis(200);

    private static final int END_OF_STREAM = -1;

    /** How much later than its timeout a bounded attempt may return and still count as bounded. */
    private static final Duration TIMEOUT_SLACK = Duration.ofSeconds(2);

    /** Longer than the read timeout, so the response arrives only after the client gave up. */
    private static final int SLOW_RESPONSE_MILLIS = 4_000;

    private static final String LOCATION = "Location";

    private static final String CONTENT_TYPE = "Content-Type";

    private static final String APPLICATION_JSON = "application/json";

    private static final int OK = 200;

    private static final String PUBLIC_ADDRESS = "203.0.113.10";

    private static final String PUBLIC_HOOK_URL = "https://hooks.example.org/consent";

    private static final Duration LATE_CONNECT_TIMEOUT = Duration.ofMillis(100);

    private static final Duration LATE_READ_TIMEOUT = Duration.ofMillis(200);

    /** Well past {@code LATE_CONNECT_TIMEOUT + LATE_READ_TIMEOUT}, the exchange deadline. */
    private static final Duration LATE_RESPONSE_DELAY = Duration.ofMillis(600);

    private static final String PRIVATE_ADDRESS = "10.1.2.3";

    /** Resolves through the stub only; its DNS never answers. */
    private static final String STALLED_HOST = "stalled.example.org";

    /** Senders queued on the stalled host; more than the pool, so uncoalesced lookups starve it. */
    private static final int STALLED_SENDERS = 4;

    private static final int COALESCING_RESOLVER_THREADS = 2;

    /** Long enough that the stalled senders still wait when the healthy host is sent to. */
    private static final Duration COALESCING_RESOLVE_TIMEOUT = Duration.ofSeconds(2);

    /** Far beyond any response a webhook needs, to prove the body is never buffered. */
    private static final int OVERSIZED_BODY_BYTES = 16 * 1024 * 1024;

    private static WebhookStubTls stubTls;

    private static WireMockServer stub;

    private static ApplicationContext context;

    private static ConsentSigningService signing;

    private static CanonicalJsonSerializer canonical;

    private static String hookUrl;

    private final List<AutoCloseable> opened = new ArrayList<>();

    @BeforeAll
    static void start() throws IOException, InterruptedException {
        stubTls = WebhookStubTls.generate();
        stub = new WireMockServer(stubTls.serve(options()));
        stub.start();
        hookUrl = "https://" + LOOPBACK_HOST + ":" + stub.httpsPort() + HOOK_PATH;
        context =
                ApplicationContext.builder(Environment.TEST)
                        .properties(
                                Map.of(
                                        "datasources.default.enabled",
                                        false,
                                        "flyway.enabled",
                                        false,
                                        "micronaut.server.port",
                                        -1,
                                        "consent-manager.contract-service.url",
                                        "http://localhost:1"))
                        .start();
        signing = context.getBean(ConsentSigningService.class);
        canonical = context.getBean(CanonicalJsonSerializer.class);
    }

    @AfterEach
    void reset() throws Exception {
        stub.resetAll();
        for (AutoCloseable resource : opened) {
            resource.close();
        }
        opened.clear();
    }

    @AfterAll
    static void stop() throws IOException {
        context.close();
        stub.stop();
        stubTls.delete();
    }

    @ParameterizedTest(name = "HTTP {0} -> {1}")
    @CsvSource({
        "200, DELIVERED",
        "204, DELIVERED",
        "301, ABANDON",
        "400, ABANDON",
        "404, ABANDON",
        "408, RETRY",
        "409, ABANDON",
        "429, RETRY",
        "500, RETRY",
        "503, RETRY"
    })
    void classifiesStatus(int status, Result expected) {
        stub.stubFor(
                post(urlEqualTo(HOOK_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(status)
                                        .withHeader(LOCATION, REDIRECT_TARGET_PATH)));
        stub.stubFor(get(urlEqualTo(REDIRECT_TARGET_PATH)).willReturn(aResponse().withStatus(OK)));

        DeliveryOutcome outcome = client(List.of(LOOPBACK_HOST)).send(hookUrl, payload());

        assertThat(outcome.result()).isEqualTo(expected);
        if (expected != Result.DELIVERED) {
            assertThat(outcome.reason()).isEqualTo("HTTP " + status);
        }
        stub.verify(1, postRequestedFor(urlEqualTo(HOOK_PATH)));
        stub.verify(0, getRequestedFor(urlEqualTo(REDIRECT_TARGET_PATH)));
        stub.verify(0, postRequestedFor(urlEqualTo(REDIRECT_TARGET_PATH)));
    }

    @Test
    void slowResponseRetriesWithinTheReadTimeout() {
        stub.stubFor(
                post(urlEqualTo(HOOK_PATH))
                        .willReturn(
                                aResponse().withStatus(OK).withFixedDelay(SLOW_RESPONSE_MILLIS)));
        ParticipantWebhookClient client = client(List.of(LOOPBACK_HOST));

        long started = System.nanoTime();
        DeliveryOutcome outcome = client.send(hookUrl, payload());

        assertThat(outcome).isEqualTo(DeliveryOutcome.retry(ParticipantWebhookClient.READ_TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(READ_TIMEOUT.plus(TIMEOUT_SLACK));
    }

    @ParameterizedTest(name = "HTTP {0} with an oversized body -> {1}")
    @CsvSource({"200, DELIVERED", "404, ABANDON", "503, RETRY"})
    void oversizedResponseBodyIsNeverRead(int status, Result expected) {
        stub.stubFor(
                post(urlEqualTo(HOOK_PATH))
                        .willReturn(
                                aResponse()
                                        .withStatus(status)
                                        .withBody(new byte[OVERSIZED_BODY_BYTES])));

        assertThat(client(List.of(LOOPBACK_HOST)).send(hookUrl, payload()).result())
                .isEqualTo(expected);
    }

    @Test
    void refusedConnectionRetries() {
        WireMockServer dead = new WireMockServer(options().httpDisabled(true).dynamicHttpsPort());
        dead.start();
        String deadUrl = "https://" + LOOPBACK_HOST + ":" + dead.httpsPort() + HOOK_PATH;
        dead.stop();

        assertThat(client(List.of(LOOPBACK_HOST)).send(deadUrl, payload()))
                .isEqualTo(DeliveryOutcome.retry(ParticipantWebhookClient.CONNECTION_REFUSED));
    }

    @Test
    void bodyIsTheCanonicalJsonTheSignatureCovers() {
        stub.stubFor(post(urlEqualTo(HOOK_PATH)).willReturn(aResponse().withStatus(OK)));
        NotificationPayload payload = payload();

        assertThat(client(List.of(LOOPBACK_HOST)).send(hookUrl, payload).result())
                .isEqualTo(Result.DELIVERED);

        LoggedRequest received = stub.findAll(postRequestedFor(urlEqualTo(HOOK_PATH))).getFirst();
        String body = received.getBodyAsString();
        String signature = received.getHeader(ParticipantWebhookClient.SIGNATURE_HEADER);
        assertThat(received.getHeader(CONTENT_TYPE)).startsWith(APPLICATION_JSON);
        assertThat(body)
                .isEqualTo(canonical.toJson(payload))
                .isEqualTo(canonical.canonicalize(body));
        assertThat(signing.verifyCanonical(body, signature, SignatureType.NOTIFICATION)).isTrue();
    }

    @ParameterizedTest(name = "signed as {0}")
    @EnumSource(SignatureType.class)
    void signatureVerifiesOnlyAsItsOwnType(SignatureType signedAs) {
        var signed = signing.sign(payload(), signedAs);

        for (SignatureType checkedAs : SignatureType.values()) {
            assertThat(signing.verifyCanonical(signed.canonicalJson(), signed.jws(), checkedAs))
                    .as("signed as %s, verified as %s", signedAs, checkedAs)
                    .isEqualTo(checkedAs == signedAs);
        }
    }

    static Stream<Arguments> refusedEndpoints() {
        String port = ":" + stub.httpsPort();
        return Stream.of(
                Arguments.of("a loopback host no entry names", List.of(), null),
                Arguments.of(
                        "plain http, even to an allowed host",
                        List.of(LOOPBACK_HOST),
                        "http://" + LOOPBACK_HOST + port + HOOK_PATH),
                Arguments.of("a host outside a non-empty allow-list", List.of(EXTERNAL_HOST), null),
                Arguments.of(
                        "a private-range literal",
                        List.of(),
                        "https://" + PRIVATE_ADDRESS + port + HOOK_PATH),
                Arguments.of(
                        "a unique-local IPv6 literal",
                        List.of(),
                        "https://[fd00::1234]" + port + HOOK_PATH),
                Arguments.of("not a URL", List.of(), "https://bad host/x"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedEndpoints")
    void refusesBeforeConnecting(String description, List<String> allowedHosts, String endpoint) {
        stub.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(OK)));

        DeliveryOutcome outcome =
                client(allowedHosts).send(endpoint == null ? hookUrl : endpoint, payload());

        assertThat(outcome)
                .isEqualTo(
                        DeliveryOutcome.abandon(ParticipantWebhookClient.ENDPOINT_NOT_PERMITTED));
        stub.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void allowListEntryAdmitsLoopback() {
        stub.stubFor(post(urlEqualTo(HOOK_PATH)).willReturn(aResponse().withStatus(OK)));

        assertThat(client(List.of(LOOPBACK_HOST.toUpperCase())).send(hookUrl, payload()))
                .isEqualTo(DeliveryOutcome.delivered());
    }

    static Stream<Arguments> resolverOutcomes() {
        return Stream.of(
                Arguments.of(
                        "lookup fails, as for SERVFAIL or NXDOMAIN",
                        (HostResolver)
                                host -> {
                                    throw new UnknownHostException(host);
                                },
                        DeliveryOutcome.retry(ParticipantWebhookClient.NAME_RESOLUTION_FAILED)),
                Arguments.of(
                        "resolves to no address at all",
                        resolvingTo(),
                        DeliveryOutcome.abandon(ParticipantWebhookClient.ENDPOINT_NOT_PERMITTED)),
                Arguments.of(
                        "resolves to a private address",
                        resolvingTo(PRIVATE_ADDRESS),
                        DeliveryOutcome.abandon(ParticipantWebhookClient.ENDPOINT_NOT_PERMITTED)),
                Arguments.of(
                        "resolves to a public and a private address",
                        resolvingTo(PUBLIC_ADDRESS, PRIVATE_ADDRESS),
                        DeliveryOutcome.abandon(ParticipantWebhookClient.ENDPOINT_NOT_PERMITTED)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resolverOutcomes")
    void classifiesResolution(String description, HostResolver resolver, DeliveryOutcome expected) {
        assertThat(client(List.of(), resolver).send(EXTERNAL_ENDPOINT, payload()))
                .isEqualTo(expected);
    }

    @Test
    void stalledLookupRetriesWithinTheResolveTimeout() {
        CountDownLatch never = new CountDownLatch(1);
        HostResolver stalled =
                host -> {
                    try {
                        never.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new UnknownHostException(host);
                };
        ParticipantWebhookClient client = client(List.of(), stalled);

        long started = System.nanoTime();
        DeliveryOutcome outcome = client.send(EXTERNAL_ENDPOINT, payload());

        assertThat(outcome)
                .isEqualTo(DeliveryOutcome.retry(ParticipantWebhookClient.NAME_RESOLUTION_FAILED));
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(RESOLVE_TIMEOUT.plus(TIMEOUT_SLACK));
        never.countDown();
    }

    @Test
    void stalledHostHoldsOneResolverThread() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger stalledLookups = new AtomicInteger();
        HostResolver resolver =
                host -> {
                    if (!host.equals(STALLED_HOST)) {
                        return resolvingTo(PRIVATE_ADDRESS).resolve(host);
                    }
                    stalledLookups.incrementAndGet();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new UnknownHostException(host);
                };
        ParticipantWebhookClient client =
                client(
                        List.of(),
                        resolver,
                        settings -> {
                            settings.setResolverThreads(COALESCING_RESOLVER_THREADS);
                            settings.setResolveTimeout(COALESCING_RESOLVE_TIMEOUT);
                        });
        String stalledEndpoint = "https://" + STALLED_HOST + HOOK_PATH;
        List<Thread> senderThreads = new CopyOnWriteArrayList<>();
        ExecutorService senders =
                Executors.newFixedThreadPool(
                        STALLED_SENDERS,
                        runnable -> {
                            Thread thread = new Thread(runnable);
                            senderThreads.add(thread);
                            return thread;
                        });
        try {
            List<Future<DeliveryOutcome>> stalled = new ArrayList<>();
            for (int i = 0; i < STALLED_SENDERS; i++) {
                stalled.add(senders.submit(() -> client.send(stalledEndpoint, payload())));
            }
            Await.until(
                    "every stalled sender waits on its lookup",
                    TIMEOUT_SLACK,
                    () ->
                            senderThreads.size() == STALLED_SENDERS
                                    && senderThreads.stream()
                                            .allMatch(
                                                    thread ->
                                                            thread.getState()
                                                                    == Thread.State.TIMED_WAITING));

            assertThat(client.send(EXTERNAL_ENDPOINT, payload()))
                    .isEqualTo(
                            DeliveryOutcome.abandon(
                                    ParticipantWebhookClient.ENDPOINT_NOT_PERMITTED));
            for (Future<DeliveryOutcome> outcome : stalled) {
                assertThat(outcome.get())
                        .isEqualTo(
                                DeliveryOutcome.retry(
                                        ParticipantWebhookClient.NAME_RESOLUTION_FAILED));
            }
            assertThat(stalledLookups).hasValue(1);
        } finally {
            release.countDown();
            senders.shutdownNow();
        }
    }

    @Test
    void silentPeerRetriesAsConnectTimeoutAndIsHungUpOn() throws IOException {
        try (ServerSocket silent = new ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress());
                Peer peer = Peer.start(silent, ParticipantWebhookClientTest::readUntilHungUp)) {
            assertBoundedRetry(
                    urlOf(silent.getLocalPort()), ParticipantWebhookClient.CONNECT_TIMEOUT);
            peer.awaitHangUp();
        }
    }

    @Test
    void drippedResponseRetriesWithinTheSendBoundAndIsHungUpOn() throws Exception {
        try (ServerSocket tls = tlsServerSocket();
                Peer peer = Peer.start(tls, ParticipantWebhookClientTest::dripResponse)) {
            assertBoundedRetry(
                    urlOf(tls.getLocalPort()), ParticipantWebhookClient.RESPONSE_TIMEOUT);
            peer.awaitHangUp();
        }
    }

    @Test
    void unansweredConnectRetriesAsConnectTimeout() throws IOException {
        try (ServerSocket full = new ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())) {
            List<Socket> queued = new ArrayList<>();
            try {
                fillAcceptQueue(full, queued);
                assertBoundedRetry(
                        urlOf(full.getLocalPort()), ParticipantWebhookClient.CONNECT_TIMEOUT);
            } finally {
                for (Socket socket : queued) {
                    socket.close();
                }
            }
        }
    }

    @Test
    @DisplayName("a response arriving after the exchange deadline is closed, not dropped")
    void lateResponseIsClosed() {
        AtomicBoolean closed = new AtomicBoolean();
        RawHttpClient late = lateResponder(unreadResponse(closed));
        ParticipantNotifications settings = new ParticipantNotifications();
        settings.setConnectTimeout(LATE_CONNECT_TIMEOUT);
        settings.setReadTimeout(LATE_READ_TIMEOUT);
        ParticipantWebhookClient client =
                new ParticipantWebhookClient(late, signing, resolvingTo(PUBLIC_ADDRESS), settings);
        opened.add(client::shutdown);

        DeliveryOutcome outcome = client.send(PUBLIC_HOOK_URL, payload());

        assertThat(outcome)
                .isEqualTo(DeliveryOutcome.retry(ParticipantWebhookClient.RESPONSE_TIMEOUT));
        Await.until("the late response is closed", TIMEOUT_SLACK, closed::get);
    }

    /**
     * A send to {@code endpoint} retries for {@code reason} within {@code sendBound} plus slack.
     */
    private void assertBoundedRetry(String endpoint, String reason) {
        ParticipantWebhookClient client = client(List.of(LOOPBACK_HOST));

        long started = System.nanoTime();
        DeliveryOutcome outcome = client.send(endpoint, payload());

        assertThat(outcome).isEqualTo(DeliveryOutcome.retry(reason));
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(TEST_CONNECT_TIMEOUT.plus(READ_TIMEOUT).plus(TIMEOUT_SLACK));
    }

    /**
     * Connects until the kernel stops completing handshakes for the never-accepting socket, so the
     * next SYN is dropped and the client's connect times out.
     */
    private static void fillAcceptQueue(ServerSocket server, List<Socket> queued)
            throws IOException {
        while (true) {
            Socket filler = new Socket();
            try {
                filler.connect(server.getLocalSocketAddress(), FILLER_CONNECT_MILLIS);
                queued.add(filler);
            } catch (SocketTimeoutException full) {
                filler.close();
                return;
            }
        }
    }

    /** Takes whatever the client sends and never answers, returning once the client hangs up. */
    private static void readUntilHungUp(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        byte[] sink = new byte[REQUEST_READ_BYTES];
        while (in.read(sink) != END_OF_STREAM) {
            // discard
        }
    }

    /**
     * Completes the TLS handshake, then answers one header byte at a time, each under the idle
     * limit.
     */
    private static void dripResponse(Socket socket) throws IOException, InterruptedException {
        socket.getInputStream().read(new byte[REQUEST_READ_BYTES]);
        OutputStream out = socket.getOutputStream();
        out.write(DRIP_HEAD);
        while (true) {
            out.write(DRIP_BYTE);
            out.flush();
            Thread.sleep(DRIP_INTERVAL);
        }
    }

    private static ServerSocket tlsServerSocket() throws Exception {
        KeyStore store = KeyStore.getInstance(WebhookStubTls.STORE_TYPE);
        try (InputStream in = Files.newInputStream(stubTls.keystore())) {
            store.load(in, WebhookStubTls.STORE_PASSWORD.toCharArray());
        }
        KeyManagerFactory keys =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, WebhookStubTls.STORE_PASSWORD.toCharArray());
        SSLContext tls = SSLContext.getInstance(TLS_PROTOCOL);
        tls.init(keys.getKeyManagers(), null, null);
        return tls.getServerSocketFactory()
                .createServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress());
    }

    /**
     * Serves one connection on a virtual thread; closing it releases the connection. A behaviour
     * ends on its own only when the client hangs up, which is how a test sees the socket freed.
     */
    private record Peer(Thread thread, List<Socket> accepted, AtomicBoolean hungUp)
            implements AutoCloseable {

        interface Behaviour {
            void serve(Socket socket) throws Exception;
        }

        static Peer start(ServerSocket server, Behaviour behaviour) {
            List<Socket> accepted = new CopyOnWriteArrayList<>();
            AtomicBoolean hungUp = new AtomicBoolean();
            Thread thread =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            Socket socket = server.accept();
                                            accepted.add(socket);
                                            behaviour.serve(socket);
                                            hungUp.set(true);
                                        } catch (IOException e) {
                                            // a write or read refused because the client closed
                                            hungUp.set(true);
                                        } catch (Exception e) {
                                            // interrupted by close
                                        }
                                    });
            return new Peer(thread, accepted, hungUp);
        }

        /** Fails unless the client closes its end before the test does. */
        void awaitHangUp() {
            Await.until("the client closes the connection", TIMEOUT_SLACK, hungUp::get);
        }

        @Override
        public void close() throws IOException {
            thread.interrupt();
            for (Socket socket : accepted) {
                socket.close();
            }
        }
    }

    private static String urlOf(int port) {
        return "https://" + LOOPBACK_HOST + ":" + port + HOOK_PATH;
    }

    private ParticipantWebhookClient client(List<String> allowedHosts) {
        return client(allowedHosts, new HostResolver.SystemHostResolver());
    }

    private ParticipantWebhookClient client(List<String> allowedHosts, HostResolver resolver) {
        return client(allowedHosts, resolver, settings -> {});
    }

    /** A client trusting only the stub's certificate, with short timeouts unless overridden. */
    private ParticipantWebhookClient client(
            List<String> allowedHosts,
            HostResolver resolver,
            Consumer<ParticipantNotifications> overrides) {
        ParticipantNotifications settings = new ParticipantNotifications();
        settings.setAllowedHosts(allowedHosts);
        settings.setReadTimeout(READ_TIMEOUT);
        settings.setResolveTimeout(RESOLVE_TIMEOUT);
        settings.setConnectTimeout(TEST_CONNECT_TIMEOUT);
        overrides.accept(settings);
        DefaultHttpClientConfiguration configuration =
                ParticipantWebhookHttpClientFactory.configuration(settings);
        configuration.getSslConfiguration().setTrustStore(stubTls.trustStore());
        RawHttpClient http = RawHttpClient.create(null, configuration);
        ParticipantWebhookClient client =
                new ParticipantWebhookClient(http, signing, resolver, settings);
        opened.add(http);
        opened.add(client::shutdown);
        return client;
    }

    /**
     * A client whose exchange emits {@code response} only after {@link #LATE_RESPONSE_DELAY} and
     * ignores cancellation, as a response racing the deadline does.
     */
    private static RawHttpClient lateResponder(HttpResponse<?> response) {
        return new RawHttpClient() {
            @Override
            public Publisher<? extends HttpResponse<?>> exchange(
                    HttpRequest<?> request, CloseableByteBody body, Thread blockedThread) {
                body.close();
                return subscriber -> {
                    subscriber.onSubscribe(
                            new Subscription() {
                                @Override
                                public void request(long n) {}

                                @Override
                                public void cancel() {}
                            });
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            Thread.sleep(LATE_RESPONSE_DELAY);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            return;
                                        }
                                        subscriber.onNext(response);
                                        subscriber.onComplete();
                                    });
                };
            }

            @Override
            public void close() {}
        };
    }

    /** A {@code 200} raw response that records being closed and supports nothing else. */
    private static ByteBodyHttpResponse<?> unreadResponse(AtomicBoolean closed) {
        return (ByteBodyHttpResponse<?>)
                Proxy.newProxyInstance(
                        ByteBodyHttpResponse.class.getClassLoader(),
                        new Class<?>[] {ByteBodyHttpResponse.class},
                        (proxy, method, arguments) ->
                                switch (method.getName()) {
                                    case "close" -> {
                                        closed.set(true);
                                        yield null;
                                    }
                                    case "code" -> OK;
                                    default ->
                                            throw new UnsupportedOperationException(
                                                    method.getName());
                                });
    }

    private static HostResolver resolvingTo(String... literals) {
        return host -> {
            InetAddress[] addresses = new InetAddress[literals.length];
            for (int i = 0; i < literals.length; i++) {
                addresses[i] = InetAddress.getByName(literals[i]);
            }
            return addresses;
        };
    }

    static NotificationPayload payload() {
        return new NotificationPayload(
                NotificationEventType.CONSENT_REVOKED,
                UUID.randomUUID(),
                Instant.parse("2026-10-29T10:15:30.123456Z"),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "did:web:subject.example.org",
                ConsentStatus.REVOKED,
                false);
    }
}
