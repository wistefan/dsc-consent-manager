package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.support.CatalogStub;
import com.seamware.consentmanager.support.PrivacyNoticeApiTestSupport;
import com.seamware.consentmanager.support.PrivacyNoticeFixtures;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notice retrieval with both upstreams stubbed: guards a resolved read serving stored text instead
 * of the live documents, and {@code resolve=false} still sending upstream traffic.
 */
@MicronautTest(transactional = false)
@DisplayName("Privacy notice retrieval over HTTP")
class PrivacyNoticeRetrievalIT extends PrivacyNoticeApiTestSupport {

    private static final String PATH = "/privacy-notices/";

    /** An id the UUID-typed path variable cannot hold, which is a 400 and not a 404. */
    private static final String NOT_A_UUID = "not-a-uuid";

    /** Title the resolvable fixture's subject yields, which is not the title seeded below. */
    private static final String LIVE_TITLE =
            "https://catalog.example.org/v1/catalog/ecosystems/mobility-data-space";

    /** The purpose that fixture states, which is not the purpose seeded below either. */
    private static final String LIVE_PURPOSE =
            "Produce aggregated mobility insights for the city of Example.";

    /** The terms that fixture states, the contract's only source for the principal's rights. */
    private static final String LIVE_TERMS =
            "A PII principal may ask either member for access to, correction of or erasure of the"
                    + " data this contract covers.";

    /** Title the seeded row carries, so a response showing it proves nothing was consulted. */
    private static final String STORED_TITLE = "Title as the contract read at generation";

    /** Purpose the seeded row carries, for the same reason. */
    private static final String STORED_PURPOSE = "Purpose as the contract read at generation";

    private static final String OFFERING_PATH = "/catalog/serviceofferings/trip-history";

    private static final String OFFERING_NAME = "Trip history";

    private static final String OFFERING_DESCRIPTION = "Trips taken by consenting riders.";

    private static final String RESOURCE_PATH = "/catalog/dataresources/trip-records";

    private static final String RESOURCE_NAME = "Trip records";

    /** Text only the second-hop document carries, so a walk stopping one hop short shows. */
    private static final String RESOURCE_DESCRIPTION =
            "One record per trip: start, end and duration.";

    private final CatalogStub catalog = new CatalogStub();

    @AfterAll
    void stopCatalog() {
        catalog.close();
    }

    @Test
    @DisplayName("a resolved retrieval reads the contract and both catalog hops")
    void resolvesEveryExternalDocument() {
        String resourceUrl =
                catalog.serveResource(RESOURCE_PATH, RESOURCE_NAME, RESOURCE_DESCRIPTION);
        String offeringUrl =
                catalog.serveOffering(
                        OFFERING_PATH, OFFERING_NAME, OFFERING_DESCRIPTION, List.of(resourceUrl));
        PrivacyNotice stored = seedNotice("retrieve-resolved", offeringUrl);

        Map<String, Object> body = body(get(PATH + stored.getId()));

        assertThat(body.get("title"))
                .as("the title is the contract's as it reads now, not as it read at generation")
                .isEqualTo(LIVE_TITLE);
        assertThat(body.get("purposes")).isEqualTo(List.of(LIVE_PURPOSE));
        assertThat(body.get("piiPrincipalRights")).isEqualTo(LIVE_TERMS);

        List<Map<String, Object>> data = entries(body, "data");
        assertThat(data).hasSize(2);
        assertThat(data.get(0))
                .as("an offering is what the contract named, so it is part of nothing")
                .containsEntry("uri", offeringUrl)
                .containsEntry("title", OFFERING_NAME)
                .containsEntry("description", OFFERING_DESCRIPTION)
                .doesNotContainKey("partOf");
        assertThat(data.get(1))
                .as(
                        "the data resource behind the offering is a hop further and an entry of its own")
                .containsEntry("uri", resourceUrl)
                .containsEntry("title", RESOURCE_NAME)
                .containsEntry("description", RESOURCE_DESCRIPTION)
                .containsEntry("partOf", offeringUrl);

        assertThat(entries(body, "recipients"))
                .extracting(
                        recipient -> recipient.get("name"), recipient -> recipient.get("contact"))
                .as("each recipient is named and contacted from this service's own registration")
                .containsExactly(
                        tuple(legalNameOf(CALLER), emailOf(CALLER)),
                        tuple(legalNameOf(CONSUMER), emailOf(CONSUMER)));

        assertThat(resolution(body).values()).containsOnly("RESOLVED");
    }

    @Test
    @DisplayName("resolve=false serves the stored notice and reads nothing at all")
    void readsNothingWhenResolutionIsNotAsked() {
        String offeringUrl =
                catalog.serveOffering(
                        OFFERING_PATH, OFFERING_NAME, OFFERING_DESCRIPTION, List.of());
        PrivacyNotice stored = seedNotice("retrieve-unresolved", offeringUrl);
        int contractReads = contracts.requestCount();
        int catalogReads = catalog.requestCount();

        Map<String, Object> body = body(get(PATH + stored.getId() + "?resolve=false"));

        assertThat(contracts.requestCount()).isEqualTo(contractReads);
        assertThat(catalog.requestCount()).isEqualTo(catalogReads);
        assertThat(body.get("title")).isEqualTo(STORED_TITLE);
        assertThat(body.get("purposes")).isEqualTo(List.of(STORED_PURPOSE));
        assertThat(body).doesNotContainKey("piiPrincipalRights");
        assertThat(entries(body, "data"))
                .singleElement(InstanceOfAssertFactories.MAP)
                .containsOnlyKeys("uri");
        assertThat(resolution(body).values())
                .as("nothing was read, which is a different claim from everything having been read")
                .containsOnly("NOT_REQUESTED");
    }

    @Test
    @DisplayName("an id no notice is held under is a 404")
    void refusesAnUnknownNotice() {
        HttpResponse<String> response = get(PATH + UUID.randomUUID());

        assertThat(response.code()).isEqualTo(HttpStatus.NOT_FOUND.getCode());
        assertThat(response.getContentType())
                .hasValueSatisfying(type -> assertThat(type.toString()).isEqualTo(PROBLEM_JSON));
    }

    @Test
    @DisplayName("an id that is not a UUID is a 400, which the specification now says")
    void refusesAnIdThatIsNotAUuid() {
        HttpResponse<String> response = get(PATH + NOT_A_UUID);

        assertThat(response.code())
                .as("the path variable binds to a UUID, so this never reaches the service")
                .isEqualTo(HttpStatus.BAD_REQUEST.getCode());
        assertThat(response.getContentType())
                .hasValueSatisfying(type -> assertThat(type.toString()).isEqualTo(PROBLEM_JSON));
    }

    /** A notice naming one service offering, whose contract is stubbed under its own id. */
    private PrivacyNotice seedNotice(String contractId, String offeringUrl) {
        String contractUri = serveResolvable(contractId);
        return seed(
                contractUri,
                STORED_TITLE,
                CALLER,
                CONSUMER,
                PrivacyNoticeFixtures.payloadToResolve(
                        List.of(STORED_PURPOSE),
                        List.of(PrivacyNoticeFixtures.reference(offeringUrl)),
                        List.of(
                                PrivacyNoticeFixtures.recipient(CALLER),
                                PrivacyNoticeFixtures.recipient(CONSUMER))));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Map<String, Object> body, String section) {
        return (List<Map<String, Object>>) body.get(section);
    }
}
