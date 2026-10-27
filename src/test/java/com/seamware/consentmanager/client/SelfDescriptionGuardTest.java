package com.seamware.consentmanager.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.client.dto.SelfDescription;
import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.support.CatalogStub;
import io.micronaut.cache.CacheManager;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.http.client.HttpClient;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.json.JsonMapper;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins which URLs a self-description is read from. Refusals assert the stub saw no request, since
 * only "did not connect" is a guard; the admitting cases show the guard does not refuse everything.
 */
@DisplayName("Self-description guard")
class SelfDescriptionGuardTest {

    /** Path the stub serves a readable document under. */
    private static final String DOCUMENT_PATH = "/catalog/dataresources/guarded";

    private static final String DOCUMENT_NAME = "Guarded resource";

    private static final String DOCUMENT_DESCRIPTION = "A document behind the guard.";

    /** A host an allow-list entry names, which is what lets it be admitted without a lookup. */
    private static final String PUBLIC_HOST = "catalog.example.org";

    private static final String PUBLIC_URL =
            "https://" + PUBLIC_HOST + "/v1/catalog/dataresources/trip-records";

    /** A literal in a range this deployment's own network uses, named by no entry. */
    private static final String PRIVATE_RANGE_URL =
            "https://10.1.2.3/v1/catalog/dataresources/trip-records";

    /** A unique-local IPv6 literal ({@code fc00::/7}); {@code isSiteLocalAddress} misses it. */
    private static final String UNIQUE_LOCAL_V6_URL =
            "https://[fd00::1234]/v1/catalog/dataresources/trip-records";

    /** A shared-address-space literal, {@code 100.64.0.0/10}, as a managed cluster hands out. */
    private static final String SHARED_RANGE_URL =
            "https://100.64.7.9/v1/catalog/dataresources/trip-records";

    /** A public literal named by no entry; a literal, so the case needs no DNS. */
    private static final String PUBLIC_RANGE_URL =
            "https://203.0.113.10/v1/catalog/dataresources/trip-records";

    /** Path the stub answers with a bounce, to a URL nothing is supposed to connect to. */
    private static final String REDIRECT_PATH = "/catalog/dataresources/bouncing";

    /** A well-formed public URL outside a non-empty allow-list. */
    private static final String OUTSIDE_URL =
            "https://other.example.org/v1/catalog/dataresources/trip-records";

    /** Stands in for the Contract Service, which no case here reads; only the URL has to bind. */
    private static final String UNUSED_CONTRACT_SERVICE_URL = "http://localhost:1";

    private static ApplicationContext context;

    private static CatalogStub catalog;

    /** Absolute URL of the stubbed document, loopback and plain http as WireMock serves it. */
    private static String documentUrl;

    /** Host the stub is reachable under, which is the loopback name the cases name or omit. */
    private static String loopbackHost;

    @BeforeAll
    static void start() {
        catalog = new CatalogStub();
        documentUrl = catalog.url() + DOCUMENT_PATH;
        loopbackHost = URI.create(documentUrl).getHost();
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
                                        UNUSED_CONTRACT_SERVICE_URL))
                        .start();
    }

    /** Re-registers the document every case may read, since each case ends with a reset. */
    @BeforeEach
    void serveDocument() {
        catalog.serveResource(DOCUMENT_PATH, DOCUMENT_NAME, DOCUMENT_DESCRIPTION);
    }

    @AfterEach
    void resetCatalog() {
        catalog.reset();
    }

    @AfterAll
    static void stop() {
        context.close();
        catalog.close();
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of(
                        "plain http while plaintext is not allowed",
                        List.of(loopbackHost),
                        false,
                        documentUrl),
                Arguments.of("a loopback host no entry names", List.of(), true, documentUrl),
                Arguments.of(
                        "a private-range literal no entry names",
                        List.of(),
                        false,
                        PRIVATE_RANGE_URL),
                Arguments.of(
                        "a unique-local IPv6 literal no entry names",
                        List.of(),
                        false,
                        UNIQUE_LOCAL_V6_URL),
                Arguments.of(
                        "a shared-address-space literal no entry names",
                        List.of(),
                        false,
                        SHARED_RANGE_URL),
                Arguments.of(
                        "a host outside a non-empty allow-list",
                        List.of(PUBLIC_HOST),
                        false,
                        OUTSIDE_URL));
    }

    @ParameterizedTest(name = "{0} is refused")
    @MethodSource("refusals")
    @DisplayName("a URL this deployment does not read from is refused before anything is sent")
    void refusesWhatTheGuardDoesNotAdmit(
            String name, List<String> allowedHosts, boolean plaintext, String url) {
        int reads = catalog.requestCount();
        SelfDescriptionClient client = client(allowedHosts, plaintext);

        assertThat(client.permits(url)).isFalse();
        assertThat(client.fetch(url))
                .as("a refusal is the same empty result as a failed read, never an exception")
                .isEmpty();

        assertThat(catalog.requestCount())
                .as("refusing after connecting would be no guard at all")
                .isEqualTo(reads);
    }

    @Test
    @DisplayName("a public https host an entry names is admitted without an address lookup")
    void admitsAnAllowedPublicHost() {
        assertThat(client(List.of(PUBLIC_HOST), false).permits(PUBLIC_URL)).isTrue();
    }

    @Test
    @DisplayName("an entry waives the private-address refusal for the host it names")
    void admitsALoopbackHostAnEntryNames() {
        Optional<SelfDescription> document = client(List.of(loopbackHost), true).fetch(documentUrl);

        assertThat(document)
                .as("naming a host is an operator's decision and outranks the heuristic")
                .hasValueSatisfying(read -> assertThat(read.name()).isEqualTo(DOCUMENT_NAME));
    }

    @Test
    @DisplayName("a redirect off an admitted host is not followed")
    void refusesToFollowARedirect() {
        try (CatalogStub target = new CatalogStub()) {
            String reached = target.serveResource(DOCUMENT_PATH, DOCUMENT_NAME, "Behind a bounce.");
            String bouncing = catalog.serveRedirect(REDIRECT_PATH, reached);

            Optional<SelfDescription> document =
                    client(List.of(loopbackHost), true).fetch(bouncing);

            assertThat(document)
                    .as("the guard vetted the URL this service was handed, not where it points")
                    .isEmpty();
            assertThat(target.requestCount())
                    .as("a followed redirect would take the connection somewhere nothing vetted")
                    .isZero();
        }
    }

    @Test
    @DisplayName("an unset allow-list binds to no entries rather than to one blank one")
    void bindsAnUnsetAllowListToNothing() {
        try (ApplicationContext unset =
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
                                        UNUSED_CONTRACT_SERVICE_URL,
                                        // ${SELF_DESCRIPTION_ALLOWED_HOSTS:}, unset.
                                        "consent-manager.self-description.allowed-hosts",
                                        ""))
                        .start()) {
            ConsentManagerConfiguration.SelfDescription configuration =
                    unset.getBean(ConsentManagerConfiguration.SelfDescription.class);

            assertThat(configuration.getAllowedHosts())
                    .as("one blank entry would be a non-empty list no real host is named in")
                    .isEmpty();
            assertThat(client(configuration).permits(PUBLIC_RANGE_URL))
                    .as("the default configuration has to read public catalogs, not refuse them")
                    .isTrue();
        }
    }

    /** A client configured as the case names, over the context's own HTTP client. */
    private static SelfDescriptionClient client(List<String> allowedHosts, boolean plaintext) {
        ConsentManagerConfiguration.SelfDescription configuration =
                new ConsentManagerConfiguration.SelfDescription();
        configuration.setAllowedHosts(allowedHosts);
        configuration.setAllowPlaintextHttp(plaintext);
        return client(configuration);
    }

    /** A client over that configuration and the shared, redirect-refusing client bean. */
    private static SelfDescriptionClient client(
            ConsentManagerConfiguration.SelfDescription configuration) {
        return new SelfDescriptionClient(
                configuration,
                context.getBean(
                        HttpClient.class, Qualifiers.byName(SelfDescriptionHttpClientFactory.NAME)),
                context.getBean(CacheManager.class),
                context.getBean(JsonMapper.class));
    }
}
