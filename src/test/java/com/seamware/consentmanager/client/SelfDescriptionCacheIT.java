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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins what the self-description cache stores and, more importantly, what it refuses to store.
 *
 * <p>The client's own doc comment argues that {@code @Cacheable} is wrong here because it cannot
 * decline to store a result and would pin a momentarily unreachable catalog to {@code UNAVAILABLE}
 * for a whole lifetime. These cases are what makes that argument checkable: a hit is served from
 * the cache, and an unreadable, an unparseable and a refused read each leave nothing behind.
 *
 * <p>A resolution IT cannot stand in for this. One retrieval reads each URL once by construction,
 * so the walk would look identical if the {@code put} moved out of the successful branch.
 */
@DisplayName("Self-description caching")
class SelfDescriptionCacheIT {

    /** Path the caching cases read twice. */
    private static final String DOCUMENT_PATH = "/catalog/dataresources/cached";

    private static final String DOCUMENT_NAME = "Cached resource";

    private static final String DOCUMENT_DESCRIPTION = "A document read more than once.";

    /** Status standing in for a catalog that is reachable but not answering documents. */
    private static final int CATALOG_FAILURE = 500;

    /** A body that is not a document, which is the failure the parse rather than the transport. */
    private static final String MALFORMED_BODY = "{\"name\": ";

    /** Stands in for the Contract Service, which no case here reads; only the URL has to bind. */
    private static final String UNUSED_CONTRACT_SERVICE_URL = "http://localhost:1";

    @Test
    @DisplayName("a document read once is served from the cache on the next read")
    void repeatedReadIsServedFromTheCache() {
        try (CatalogStub catalog = new CatalogStub();
                ApplicationContext context = startContext()) {
            String url = catalog.serveResource(DOCUMENT_PATH, DOCUMENT_NAME, DOCUMENT_DESCRIPTION);
            SelfDescriptionClient client = context.getBean(SelfDescriptionClient.class);

            assertThat(client.fetch(url)).isPresent();
            Optional<SelfDescription> second = client.fetch(url);

            assertThat(second)
                    .hasValueSatisfying(read -> assertThat(read.name()).isEqualTo(DOCUMENT_NAME));

            assertThat(catalog.requestCount(DOCUMENT_PATH))
                    .as("two notices naming one resource cost the catalog one read, not two")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("an unreadable document is not cached, so a recovered catalog is reached again")
    void failedReadIsNotCached() {
        try (CatalogStub catalog = new CatalogStub();
                ApplicationContext context = startContext()) {
            String url = catalog.serveStatus(DOCUMENT_PATH, CATALOG_FAILURE);
            SelfDescriptionClient client = context.getBean(SelfDescriptionClient.class);

            assertThat(client.fetch(url)).isEmpty();
            catalog.serveResource(DOCUMENT_PATH, DOCUMENT_NAME, DOCUMENT_DESCRIPTION);

            assertThat(client.fetch(url))
                    .as("a blip must not pin the section to UNAVAILABLE for a whole lifetime")
                    .isPresent();
            assertThat(catalog.requestCount(DOCUMENT_PATH)).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("an unparseable document is not cached either")
    void unparseableDocumentIsNotCached() {
        try (CatalogStub catalog = new CatalogStub();
                ApplicationContext context = startContext()) {
            String url = catalog.serveDocument(DOCUMENT_PATH, MALFORMED_BODY);
            SelfDescriptionClient client = context.getBean(SelfDescriptionClient.class);

            assertThat(client.fetch(url)).isEmpty();
            catalog.serveResource(DOCUMENT_PATH, DOCUMENT_NAME, DOCUMENT_DESCRIPTION);

            assertThat(client.fetch(url))
                    .as("the cache stores documents, and a truncated body is not one")
                    .isPresent();
        }
    }

    @Test
    @DisplayName("a refused URL leaves no entry behind")
    void refusalIsNotCached() {
        try (CatalogStub catalog = new CatalogStub();
                ApplicationContext context = startContext()) {
            String url = catalog.serveResource(DOCUMENT_PATH, DOCUMENT_NAME, DOCUMENT_DESCRIPTION);

            assertThat(refusing(context).fetch(url))
                    .as("a loopback host no entry names is refused before anything is sent")
                    .isEmpty();

            assertThat(context.getBean(SelfDescriptionClient.class).fetch(url))
                    .as("a refusal is a configuration decision, not a result worth remembering")
                    .isPresent();
            assertThat(catalog.requestCount(DOCUMENT_PATH)).isEqualTo(1);
        }
    }

    /** A client over the context's shared cache that admits nothing, to refuse without storing. */
    private static SelfDescriptionClient refusing(ApplicationContext context) {
        ConsentManagerConfiguration.SelfDescription configuration =
                new ConsentManagerConfiguration.SelfDescription();
        configuration.setAllowedHosts(List.of());
        configuration.setAllowPlaintextHttp(true);
        return new SelfDescriptionClient(
                configuration,
                context.getBean(
                        HttpClient.class, Qualifiers.byName(SelfDescriptionHttpClientFactory.NAME)),
                context.getBean(CacheManager.class),
                context.getBean(JsonMapper.class));
    }

    /** A context carrying the configured {@code self-descriptions} cache and nothing else. */
    private static ApplicationContext startContext() {
        return ApplicationContext.builder(Environment.TEST)
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
}
