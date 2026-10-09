package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.support.CatalogStub;
import com.seamware.consentmanager.support.PrivacyNoticeApiTestSupport;
import com.seamware.consentmanager.support.PrivacyNoticeFixtures;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the ceiling on how far one retrieval walks the catalog.
 *
 * <p>Both lists the walk follows are published by someone else - the offerings a contract names and
 * the resources each offering names - so without a ceiling a single authenticated GET fans out as
 * far as whoever wrote them decided. The budget is set to two here so that a notice naming one
 * offering with two resources behind it cannot be read to the end.
 */
@MicronautTest(transactional = false)
@DisplayName("Privacy notice resolution under a catalog budget")
class PrivacyNoticeResolutionBudgetIT extends PrivacyNoticeApiTestSupport {

    private static final String PATH = "/privacy-notices/";

    /** Documents one retrieval may read here: the offering, and the first resource behind it. */
    private static final int MAX_DOCUMENTS = 2;

    private static final String OFFERING_PATH = "/catalog/serviceofferings/trip-history";

    private static final String OFFERING_NAME = "Trip history";

    private static final String OFFERING_DESCRIPTION = "Trips taken by consenting riders.";

    private static final String FIRST_RESOURCE_PATH = "/catalog/dataresources/trip-records";

    private static final String SECOND_RESOURCE_PATH = "/catalog/dataresources/trip-stops";

    private static final String RESOURCE_NAME = "Trip records";

    private static final String RESOURCE_DESCRIPTION = "One record per trip.";

    private final CatalogStub catalog = new CatalogStub();

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new LinkedHashMap<>(super.getProperties());
        properties.put(
                "consent-manager.self-description.max-documents", String.valueOf(MAX_DOCUMENTS));
        return properties;
    }

    @AfterAll
    void stopCatalog() {
        catalog.close();
    }

    @Test
    @DisplayName("the walk stops at the budget, serves what it read and reports data unavailable")
    void stopsWalkingAtTheBudget() {
        String first =
                catalog.serveResource(FIRST_RESOURCE_PATH, RESOURCE_NAME, RESOURCE_DESCRIPTION);
        String second =
                catalog.serveResource(SECOND_RESOURCE_PATH, RESOURCE_NAME, RESOURCE_DESCRIPTION);
        String offeringUrl =
                catalog.serveOffering(
                        OFFERING_PATH, OFFERING_NAME, OFFERING_DESCRIPTION, List.of(first, second));
        PrivacyNotice stored =
                seed(
                        serveResolvable("budgeted-walk"),
                        "Title as the contract read at generation",
                        CALLER,
                        CONSUMER,
                        PrivacyNoticeFixtures.payloadToResolve(
                                List.of("Purpose as the contract read at generation"),
                                List.of(PrivacyNoticeFixtures.reference(offeringUrl)),
                                List.of(PrivacyNoticeFixtures.recipient(CALLER))));
        int catalogReads = catalog.requestCount();

        HttpResponse<String> response = get(PATH + stored.getId());

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(catalog.requestCount() - catalogReads)
                .as("the budget is a count of documents read, not of entries skipped")
                .isEqualTo(MAX_DOCUMENTS);
        assertThat(catalog.requestCount(SECOND_RESOURCE_PATH))
                .as("the document the budget ran out before is never asked for")
                .isZero();

        Map<String, Object> body = body(response);
        List<Map<String, Object>> data = entries(body);
        assertThat(data).hasSize(3);
        assertThat(data.get(0)).containsEntry("title", OFFERING_NAME);
        assertThat(data.get(1)).containsEntry("title", RESOURCE_NAME);
        assertThat(data.get(2))
                .as(
                        "an entry the walk did not reach keeps its URI and says nothing it did not read")
                .containsOnlyKeys("uri", "partOf")
                .containsEntry("uri", second);

        assertThat(resolution(body))
                .as("a list that is only partly filled in must not claim to be resolved")
                .containsEntry("dataResources", "UNAVAILABLE")
                .containsEntry("contract", "RESOLVED")
                .containsEntry("recipients", "RESOLVED");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("data");
    }
}
