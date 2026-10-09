package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.PrivacyNoticePayload.DataReference;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.Recipient;
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
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins that an external document this service cannot read degrades its section and never the
 * request.
 *
 * <p>Every case is a 200 with the notice still in it, which is the whole point: a notice that
 * cannot be displayed at all is worse than one displayed with a section marked unavailable. The
 * expectation is not uniformly one section - a contract that could not be read takes {@code
 * purposes} with it, because the purposes are resolved from the contract by construction.
 *
 * <p>Each case seeds its notice straight into the table under a contract URI of its own. Generating
 * it through the API would leave the contract cached, and the retrieval under test would then
 * report {@code RESOLVED} whatever the stub does - a green test asserting nothing. The request
 * count at the Contract Service is asserted for that reason rather than for its own sake.
 */
@MicronautTest(transactional = false)
@DisplayName("Privacy notice resolution against failing upstreams")
class PrivacyNoticePartialResolutionIT extends PrivacyNoticeApiTestSupport {

    private static final String PATH = "/privacy-notices/";

    /** Section states, as the API publishes them. */
    private static final String RESOLVED = "RESOLVED";

    private static final String UNAVAILABLE = "UNAVAILABLE";

    /** Read timeout the slow-upstream case outlasts, short enough to keep the suite quick. */
    private static final String READ_TIMEOUT = "500ms";

    /** How long the slow catalog holds a request, comfortably past that timeout. */
    private static final Duration CATALOG_DELAY = Duration.ofSeconds(3);

    /** Status an upstream answers with when it is there but cannot serve the document. */
    private static final int UPSTREAM_FAILURE = 500;

    /** Title the seeded rows carry, so a contract case can be seen to have fallen back to it. */
    private static final String STORED_TITLE = "Title as the contract read at generation";

    /** Purpose the seeded rows carry, for the same reason. */
    private static final String STORED_PURPOSE = "Purpose as the contract read at generation";

    /** A JSON-typed body that is not a document, so the read fails where decoding happens. */
    private static final String MALFORMED_DOCUMENT = "this is not a self-description";

    private static final String OFFERING_NAME = "Trip history";

    private static final String OFFERING_DESCRIPTION = "Trips taken by consenting riders.";

    private final CatalogStub catalog = new CatalogStub();

    private final Map<String, Supplier<Expectation>> degradations = degradations();

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new LinkedHashMap<>(super.getProperties());
        // Shared by both upstream clients, which is what keeps the timeout cases quick.
        properties.put("consent-manager.contract-service.read-timeout", READ_TIMEOUT);
        return properties;
    }

    @AfterAll
    void stopCatalog() {
        catalog.close();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("degradationCases")
    @DisplayName("an unreadable document degrades its own section and the notice is served anyway")
    void degradesOnlyTheAffectedSections(String name) {
        Expectation expected = degradations.get(name).get();
        int contractReads = contracts.requestCount();

        HttpResponse<String> response = get(PATH + expected.id());

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        Map<String, Object> body = body(response);
        assertThat(body)
                .as("the notice itself is served whatever could not be read")
                .containsEntry("id", expected.id().toString())
                .containsEntry("dataProvider", CALLER)
                .containsKey("contractUri");
        assertThat(contracts.requestCount())
                .as("the row is seeded rather than generated so this reads the contract live")
                .isGreaterThan(contractReads);
        assertThat(resolution(body)).containsExactlyInAnyOrderEntriesOf(expected.states());
        expected.assertions().accept(body);
    }

    /**
     * The case names, which is all the test method takes.
     *
     * <p>The arrangement is looked up rather than passed: Micronaut's JUnit extension resolves any
     * parameter it can make a bean of, and a {@code Supplier} argument makes it compete with the
     * parameterized runner for the slot, which fails every case before it runs.
     */
    private Stream<String> degradationCases() {
        return degradations.keySet().stream();
    }

    /** The failure modes, one per external document and one per way of failing. */
    private Map<String, Supplier<Expectation>> degradations() {
        Map<String, Supplier<Expectation>> cases = new LinkedHashMap<>();
        cases.put("the Contract Service answers a failure", this::contractFails);
        cases.put("the Contract Service drops the connection", this::contractUnreachable);
        cases.put(
                "the Contract Service answers something that is no contract",
                this::contractMalformed);
        cases.put("a service offering takes longer than the read timeout", this::offeringTimesOut);
        cases.put("a service offering refuses the connection", this::offeringRefused);
        cases.put(
                "a service offering answers something that is no document",
                this::offeringMalformed);
        cases.put("a data resource behind a resolved offering fails", this::resourceFails);
        cases.put("a recipient has no registration here", this::recipientIsUnregistered);
        return cases;
    }

    /** A 500 the retry budget does not survive: the contract is unreadable, so are its purposes. */
    private Expectation contractFails() {
        String contractId = "partial-contract-failure";
        contracts.serveStatus(contractId, UPSTREAM_FAILURE);
        return new Expectation(
                seed(uri(contractId), List.of(), callerAsRecipient()),
                states(UNAVAILABLE, RESOLVED, UNAVAILABLE, RESOLVED),
                PrivacyNoticePartialResolutionIT::assertStoredTextSurvives);
    }

    /**
     * The transport failure that would be a 502 if the resolver did not catch it.
     *
     * <p>The case {@code UpstreamServiceException} is caught for: the gateway raises it rather than
     * returning empty, and letting it out of a GET would answer the 502 resolution exists to avoid.
     */
    private Expectation contractUnreachable() {
        String contractId = "partial-contract-unreachable";
        contracts.serveFault(contractId);
        return new Expectation(
                seed(uri(contractId), List.of(), callerAsRecipient()),
                states(UNAVAILABLE, RESOLVED, UNAVAILABLE, RESOLVED),
                PrivacyNoticePartialResolutionIT::assertStoredTextSurvives);
    }

    /**
     * A body that will not decode is upstream's problem too, and degrades the same two sections.
     */
    private Expectation contractMalformed() {
        String contractId = "partial-contract-malformed";
        contracts.serveMalformedContract(contractId);
        return new Expectation(
                seed(uri(contractId), List.of(), callerAsRecipient()),
                states(UNAVAILABLE, RESOLVED, UNAVAILABLE, RESOLVED),
                PrivacyNoticePartialResolutionIT::assertStoredTextSurvives);
    }

    private Expectation offeringTimesOut() {
        String offeringUrl = catalog.serveSlowly("/catalog/serviceofferings/slow", CATALOG_DELAY);
        return new Expectation(
                seed(
                        serveResolvable("partial-offering-slow"),
                        offering(offeringUrl),
                        callerAsRecipient()),
                states(RESOLVED, UNAVAILABLE, RESOLVED, RESOLVED),
                body -> assertBareEntry(body, offeringUrl));
    }

    private Expectation offeringRefused() {
        String offeringUrl = CatalogStub.unreachableUrl("/catalog/serviceofferings/gone");
        return new Expectation(
                seed(
                        serveResolvable("partial-offering-refused"),
                        offering(offeringUrl),
                        callerAsRecipient()),
                states(RESOLVED, UNAVAILABLE, RESOLVED, RESOLVED),
                body -> assertBareEntry(body, offeringUrl));
    }

    /** A catalog body that will not parse degrades the same way an unreachable one does. */
    private Expectation offeringMalformed() {
        String offeringUrl =
                catalog.serveDocument("/catalog/serviceofferings/malformed", MALFORMED_DOCUMENT);
        return new Expectation(
                seed(
                        serveResolvable("partial-offering-malformed"),
                        offering(offeringUrl),
                        callerAsRecipient()),
                states(RESOLVED, UNAVAILABLE, RESOLVED, RESOLVED),
                body -> assertBareEntry(body, offeringUrl));
    }

    /**
     * The second hop failing while the first one succeeded.
     *
     * <p>A partially filled list reports {@code UNAVAILABLE}: an offering that resolved while the
     * resources behind it did not is not a resolved section, and claiming otherwise would let a
     * caller take the list for complete.
     */
    private Expectation resourceFails() {
        String resourceUrl =
                catalog.serveStatus("/catalog/dataresources/failing", UPSTREAM_FAILURE);
        String offeringUrl =
                catalog.serveOffering(
                        "/catalog/serviceofferings/partly-resolvable",
                        OFFERING_NAME,
                        OFFERING_DESCRIPTION,
                        List.of(resourceUrl));
        return new Expectation(
                seed(
                        serveResolvable("partial-resource-failure"),
                        offering(offeringUrl),
                        callerAsRecipient()),
                states(RESOLVED, UNAVAILABLE, RESOLVED, RESOLVED),
                body -> {
                    List<Map<String, Object>> data = entries(body, "data");
                    assertThat(data)
                            .as("what did resolve is still served, the rest bare")
                            .hasSize(2);
                    assertThat(data.getFirst()).containsEntry("title", OFFERING_NAME);
                    assertThat(data.getLast())
                            .as("a resource that could not be read still says what it belongs to")
                            .containsOnlyKeys("uri", "partOf")
                            .containsEntry("partOf", offeringUrl);
                });
    }

    /** A party named in the contract that this deployment never registered. */
    private Expectation recipientIsUnregistered() {
        return new Expectation(
                seed(
                        serveResolvable("partial-recipient-unknown"),
                        List.of(),
                        List.of(
                                PrivacyNoticeFixtures.recipient(CALLER),
                                PrivacyNoticeFixtures.recipient(UNREGISTERED))),
                states(RESOLVED, RESOLVED, RESOLVED, UNAVAILABLE),
                body -> {
                    List<Map<String, Object>> recipients = entries(body, "recipients");
                    assertThat(recipients.getFirst()).containsKey("name");
                    assertThat(recipients.getLast())
                            .as(
                                    "a recipient with no row keeps the bare identifier it was stored with")
                            .containsOnlyKeys("identifier");
                });
    }

    /** Seeds a notice whose stored title and purpose are recognisably not the contract's. */
    private UUID seed(String contractUri, List<DataReference> data, List<Recipient> recipients) {
        return seed(
                        contractUri,
                        STORED_TITLE,
                        CALLER,
                        CONSUMER,
                        PrivacyNoticeFixtures.payloadToResolve(
                                List.of(STORED_PURPOSE), data, recipients))
                .getId();
    }

    private static List<DataReference> offering(String url) {
        return List.of(PrivacyNoticeFixtures.reference(url));
    }

    private static List<Recipient> callerAsRecipient() {
        return List.of(PrivacyNoticeFixtures.recipient(CALLER));
    }

    /** What a contract case asserts: the stored text is served where the live one could not be. */
    private static void assertStoredTextSurvives(Map<String, Object> body) {
        assertThat(body.get("title")).isEqualTo(STORED_TITLE);
        assertThat(body.get("purposes"))
                .as("resolution never takes a heading away, it only fails to refresh it")
                .isEqualTo(List.of(STORED_PURPOSE));
    }

    private static void assertBareEntry(Map<String, Object> body, String uri) {
        assertThat(entries(body, "data"))
                .singleElement(InstanceOfAssertFactories.MAP)
                .containsOnlyKeys("uri")
                .containsEntry("uri", uri);
    }

    private static Map<String, String> states(
            String contract, String dataResources, String purposes, String recipients) {
        return Map.of(
                "contract", contract,
                "dataResources", dataResources,
                "purposes", purposes,
                "recipients", recipients);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Map<String, Object> body, String section) {
        return (List<Map<String, Object>>) body.get(section);
    }

    /** One case's seeded notice, the report it must produce, and what else its body must show. */
    private record Expectation(
            UUID id, Map<String, String> states, Consumer<Map<String, Object>> assertions) {}
}
