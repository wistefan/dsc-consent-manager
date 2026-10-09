package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.support.CatalogStub;
import com.seamware.consentmanager.support.ContractFixtures;
import com.seamware.consentmanager.support.PrivacyNoticeApiTestSupport;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises retrieval of a notice a regeneration has superseded.
 *
 * <p>What only an end-to-end run can show: that archiving takes a notice out of the active lookup
 * without taking it out of reach. A consent is given against the notice that was published, so the
 * published one has to stay readable - and readable in full, resolution included, rather than as a
 * stored husk.
 */
@MicronautTest(transactional = false)
@DisplayName("Archived privacy notice retrieval over HTTP")
class PrivacyNoticeArchivedRetrievalIT extends PrivacyNoticeApiTestSupport {

    private static final String PATH = "/privacy-notices";

    /** Contract identifier of this class's one case, so no other case shares its cache key. */
    private static final String CONTRACT_ID = "archived-retrieval";

    /** Offering URI the fixture names, replaced by the stub's so the catalog hop is reachable. */
    private static final String FIXTURE_OFFERING =
            "https://catalog.example.org/v1/catalog/serviceofferings/trip-history";

    private static final String OFFERING_PATH = "/catalog/serviceofferings/trip-history";

    private static final String OFFERING_NAME = "Trip history";

    private static final String OFFERING_DESCRIPTION = "Trips taken by consenting riders.";

    private static final String RESOURCE_PATH = "/catalog/dataresources/trip-records";

    private static final String RESOURCE_NAME = "Trip records";

    /** Text only the second-hop document carries, so one-hop resolution cannot pass for two. */
    private static final String RESOURCE_DESCRIPTION =
            "One record per trip: start, end and duration.";

    /** The purpose the contract states until it is edited, and what the archived notice stored. */
    private static final String ORIGINAL_PURPOSE =
            "Produce aggregated mobility insights for the city of Example.";

    /** The purpose the edit introduces, which is what archives the first notice. */
    private static final String CHANGED_PURPOSE =
            "Produce aggregated mobility insights for the region of Example.";

    private final CatalogStub catalog = new CatalogStub();

    @AfterAll
    void stopCatalog() {
        catalog.close();
    }

    @Test
    @DisplayName("a superseded notice is still retrievable, reports archivedAt and resolves")
    void servesAnArchivedNoticeInFull() {
        String resourceUrl =
                catalog.serveResource(RESOURCE_PATH, RESOURCE_NAME, RESOURCE_DESCRIPTION);
        String offeringUrl =
                catalog.serveOffering(
                        OFFERING_PATH, OFFERING_NAME, OFFERING_DESCRIPTION, List.of(resourceUrl));
        String contractUri = serveBody(CONTRACT_ID, resolvable(offeringUrl, ORIGINAL_PURPOSE));

        HttpResponse<String> first = generate(contractUri);
        assertThat(first.code()).isEqualTo(HttpStatus.CREATED.getCode());
        String archivedId = (String) body(first).get("id");

        serveBody(CONTRACT_ID, resolvable(offeringUrl, CHANGED_PURPOSE));
        invalidate(contractUri);
        HttpResponse<String> second = generate(contractUri);
        assertThat(second.code()).isEqualTo(HttpStatus.CREATED.getCode());
        assertThat(body(second).get("id")).isNotEqualTo(archivedId);

        Map<String, Object> archived = body(get(PATH + "/" + archivedId));

        assertThat(archived.get("id")).isEqualTo(archivedId);
        assertThat(archived.get("archivedAt"))
                .as("the notice reports that it was superseded rather than hiding it")
                .isNotNull();
        assertThat(entries(archived, "data"))
                .as("an archived notice walks the catalog exactly as an active one does")
                .extracting(entry -> entry.get("description"))
                .containsExactly(OFFERING_DESCRIPTION, RESOURCE_DESCRIPTION);
        assertThat(archived.get("purposes"))
                .as("resolution reads the contract as it stands now, archived notice or not")
                .isEqualTo(List.of(CHANGED_PURPOSE));
        assertThat(resolution(archived).values()).containsOnly("RESOLVED");
    }

    /** The resolvable fixture with the caller as provider, the stub's offering and that purpose. */
    private static String resolvable(String offeringUrl, String purpose) {
        return ContractFixtures.body(ContractFixtures.ECOSYSTEM_RESOLVABLE)
                .replace(FIXTURE_PROVIDER, CALLER)
                .replace(FIXTURE_OFFERING, offeringUrl)
                .replace(ORIGINAL_PURPOSE, purpose);
    }

    /** Issues a generation request for the one contract this class uses. */
    private HttpResponse<String> generate(String contractUri) {
        return post(
                PATH,
                Map.of(
                        "contractUri", contractUri,
                        "dataProvider", CALLER,
                        "dataConsumer", CONSUMER));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Map<String, Object> body, String section) {
        return (List<Map<String, Object>>) body.get(section);
    }
}
