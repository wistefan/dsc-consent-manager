package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.support.CatalogStub;
import com.seamware.consentmanager.support.PrivacyNoticeApiTestSupport;
import com.seamware.consentmanager.support.PrivacyNoticeFixtures;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the deadline on one retrieval's catalog walk: guards a slow catalog that never times out
 * holding a request for the document ceiling times the read timeout.
 */
@MicronautTest(transactional = false)
@DisplayName("Privacy notice resolution under a catalog deadline")
class PrivacyNoticeResolutionDeadlineIT extends PrivacyNoticeApiTestSupport {

    private static final String PATH = "/privacy-notices/";

    /** Time one retrieval may spend reading; the first document alone already outlasts it. */
    private static final Duration MAX_DURATION = Duration.ofSeconds(1);

    /** Longer than the deadline, shorter than the read timeout, so the offering still parses. */
    private static final Duration OFFERING_DELAY = Duration.ofSeconds(2);

    private static final String OFFERING_PATH = "/catalog/serviceofferings/trip-history";

    private static final String OFFERING_NAME = "Trip history";

    private static final String OFFERING_DESCRIPTION = "Trips taken by consenting riders.";

    private static final String RESOURCE_PATH = "/catalog/dataresources/trip-records";

    private static final String RESOURCE_NAME = "Trip records";

    private static final String RESOURCE_DESCRIPTION = "One record per trip.";

    private final CatalogStub catalog = new CatalogStub();

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new LinkedHashMap<>(super.getProperties());
        properties.put("consent-manager.self-description.max-duration", MAX_DURATION.toString());
        return properties;
    }

    @AfterAll
    void stopCatalog() {
        catalog.close();
    }

    @Test
    @DisplayName("the walk stops at the deadline and reports data unavailable")
    void stopsWalkingAtTheDeadline() {
        String resource = catalog.serveResource(RESOURCE_PATH, RESOURCE_NAME, RESOURCE_DESCRIPTION);
        String offeringUrl =
                catalog.serveOffering(
                        OFFERING_PATH,
                        OFFERING_NAME,
                        OFFERING_DESCRIPTION,
                        List.of(resource),
                        OFFERING_DELAY);
        PrivacyNotice stored =
                seed(
                        serveResolvable("deadlined-walk"),
                        "Title as the contract read at generation",
                        CALLER,
                        CONSUMER,
                        PrivacyNoticeFixtures.payloadToResolve(
                                List.of("Purpose as the contract read at generation"),
                                List.of(PrivacyNoticeFixtures.reference(offeringUrl)),
                                List.of(PrivacyNoticeFixtures.recipient(CALLER))));

        HttpResponse<String> response = get(PATH + stored.getId());

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(catalog.requestCount(RESOURCE_PATH))
                .as("the document the deadline ran out before is never asked for")
                .isZero();

        Map<String, Object> body = body(response);
        assertThat(entries(body))
                .as("what the walk read before the deadline is still served")
                .hasSize(2);
        assertThat(entries(body).get(1))
                .as(
                        "an entry the walk did not reach keeps its URI and says nothing it did not read")
                .containsOnlyKeys("uri", "partOf")
                .containsEntry("uri", resource);

        assertThat(resolution(body))
                .as("a walk cut short by the deadline must not claim to be resolved")
                .containsEntry("dataResources", "UNAVAILABLE")
                .containsEntry("contract", "RESOLVED")
                .containsEntry("recipients", "RESOLVED");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("data");
    }
}
