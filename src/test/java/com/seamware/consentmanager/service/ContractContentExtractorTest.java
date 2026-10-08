package com.seamware.consentmanager.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.client.dto.ContractResponse;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.ControllerDetails;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.DataReference;
import com.seamware.consentmanager.support.ContractFixtures;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins what each contract document becomes as a privacy notice.
 *
 * <p>Every case asserts the whole content rather than the section it is named after, so a heading
 * this mapping leaves absent cannot quietly start arriving as an empty string or an empty list.
 */
@DisplayName("Contract content extraction")
class ContractContentExtractorTest {

    private static final String PROVIDER_IDENTIFIER = "did:web:provider.example.org";
    private static final String PROVIDER_LEGAL_NAME = "Example Provider GmbH";
    private static final String PROVIDER_EMAIL = "privacy@provider.example.org";
    private static final String CATALOG = "https://catalog.example.org/v1/catalog/";
    private static final String PROVIDER_URI = CATALOG + "participants/provider";
    private static final String CONSUMER_URI = CATALOG + "participants/consumer";
    private static final String ECOSYSTEM_URI = CATALOG + "ecosystems/mobility-data-space";
    private static final String TRIP_HISTORY_URI = CATALOG + "serviceofferings/trip-history";
    private static final String ROUTE_QUALITY_URI = CATALOG + "serviceofferings/route-quality";
    private static final String CHARGING_SESSIONS_URI =
            CATALOG + "serviceofferings/charging-sessions";
    private static final String METER_READINGS_URI = CATALOG + "serviceofferings/meter-readings";

    /** What a disclosure the contract declares without naming the party is reported as. */
    private static final String UNNAMED_THIRD_PARTY = "Unnamed third party";

    private static final ControllerDetails CONTROLLER =
            new ControllerDetails(
                    PROVIDER_LEGAL_NAME, PROVIDER_EMAIL, PROVIDER_IDENTIFIER, PROVIDER_URI);

    private static ApplicationContext context;
    private static ObjectMapper json;

    private final ContractContentExtractor extractor = new ContractContentExtractor();

    @BeforeAll
    static void startContext() {
        // Decoding only: no datasource, so this stays a unit test rather than a Testcontainers one.
        context =
                ApplicationContext.builder(Environment.TEST)
                        .properties(
                                Map.of(
                                        "datasources.default.enabled", false,
                                        "flyway.enabled", false))
                        .start();
        json = context.getBean(ObjectMapper.class);
    }

    @AfterAll
    static void stopContext() {
        context.close();
    }

    /** The extraction matrix: one document, one content it must yield. */
    static Stream<Arguments> extractionMatrix() {
        return Stream.of(
                Arguments.of(
                        "ecosystem contract, titled by its ecosystem",
                        ContractFixtures.ECOSYSTEM,
                        new NoticeContent(
                                ECOSYSTEM_URI,
                                payload(
                                        List.of(
                                                "Produce aggregated mobility insights for the city"
                                                        + " of Example."),
                                        List.of("Location data"),
                                        List.of(reference(TRIP_HISTORY_URI)),
                                        List.of(PROVIDER_URI, CONSUMER_URI),
                                        "Processing ends when the PII principal withdraws consent.",
                                        null,
                                        null))),
                Arguments.of(
                        "bilateral contract, titled by its offering",
                        ContractFixtures.BILATERAL,
                        new NoticeContent(
                                CHARGING_SESSIONS_URI,
                                payload(
                                        List.of(
                                                "Plan charging infrastructure from observed"
                                                        + " demand."),
                                        List.of("Energy consumption data", "Charging session data"),
                                        List.of(reference(CHARGING_SESSIONS_URI)),
                                        List.of(PROVIDER_URI, CONSUMER_URI),
                                        "P30D",
                                        "The PII principal may request access to, rectification of"
                                                + " and erasure of the data processed under this"
                                                + " contract by writing to the data provider.",
                                        List.of("Example Grid Operator")))),
                Arguments.of(
                        "ecosystem contract whose every list is empty",
                        ContractFixtures.ECOSYSTEM_MONGO_ID,
                        new NoticeContent(
                                ECOSYSTEM_URI, payload(null, null, null, null, null, null, null))),
                Arguments.of(
                        "bilateral contract with no purposes, falling back to its policies",
                        ContractFixtures.BILATERAL_NO_PURPOSE,
                        new NoticeContent(
                                METER_READINGS_URI,
                                payload(
                                        List.of(
                                                "Meter readings may be read by the data consumer"
                                                        + " only.",
                                                "Meter readings may be stored for no longer than"
                                                        + " the contract runs."),
                                        null,
                                        List.of(reference(METER_READINGS_URI)),
                                        List.of(PROVIDER_URI, CONSUMER_URI),
                                        "P14D",
                                        null,
                                        null))),
                Arguments.of(
                        "bilateral contract with neither purposes nor policies, titled by its id",
                        ContractFixtures.BILATERAL_MINIMAL,
                        new NoticeContent(
                                "66d0e2f7a1b2c3d4e5f60006",
                                payload(
                                        null,
                                        null,
                                        null,
                                        List.of(PROVIDER_URI, CONSUMER_URI),
                                        null,
                                        null,
                                        null))),
                Arguments.of(
                        "ecosystem contract whose purposes disagree on termination",
                        ContractFixtures.ECOSYSTEM_DIVERGENT_TERMINATION,
                        new NoticeContent(
                                ECOSYSTEM_URI,
                                payload(
                                        List.of(
                                                "Produce aggregated mobility insights for the city"
                                                        + " of Example.",
                                                "Schedule road maintenance from observed traffic."),
                                        List.of("Location data", "Vehicle data"),
                                        null,
                                        null,
                                        null,
                                        null,
                                        List.of("Example Statistics Office")))),
                Arguments.of(
                        "ecosystem contract with no purposes and a revoked member",
                        ContractFixtures.ECOSYSTEM_NO_PURPOSE,
                        new NoticeContent(
                                ECOSYSTEM_URI,
                                payload(
                                        List.of(
                                                "Trip history may be read by signed members of this"
                                                        + " ecosystem only."),
                                        null,
                                        List.of(
                                                reference(TRIP_HISTORY_URI),
                                                reference(ROUTE_QUALITY_URI)),
                                        List.of(PROVIDER_URI, CONSUMER_URI),
                                        null,
                                        null,
                                        null))),
                Arguments.of(
                        "ecosystem contract disclosing to a third party it does not name",
                        ContractFixtures.ECOSYSTEM_UNNAMED_DISCLOSURE,
                        new NoticeContent(
                                ECOSYSTEM_URI,
                                payload(
                                        // The first purpose states no text, so its identifier
                                        // names it rather than the section losing an entry.
                                        List.of(
                                                "purpose-fraud-screening",
                                                "Bill the data consumer for retrieved records."),
                                        List.of("Payment data"),
                                        null,
                                        null,
                                        "P90D",
                                        null,
                                        // Both purposes disclose and neither names the party, so
                                        // the disclosure is reported once under the placeholder.
                                        List.of(UNNAMED_THIRD_PARTY)))),
                Arguments.of(
                        "ecosystem contract one of whose members has not signed yet",
                        ContractFixtures.ECOSYSTEM_UNSIGNED_MEMBER,
                        new NoticeContent(
                                ECOSYSTEM_URI,
                                payload(
                                        null,
                                        null,
                                        null,
                                        // The unsigned member is a recipient-to-be and is named:
                                        // omitting it would understate who receives the data.
                                        List.of(PROVIDER_URI, CONSUMER_URI),
                                        null,
                                        null,
                                        null))),
                Arguments.of(
                        "ecosystem contract naming no ecosystem, titled by its mongo id",
                        ContractFixtures.ECOSYSTEM_UNTITLED,
                        new NoticeContent(
                                "66d0e2f7a1b2c3d4e5f60009",
                                payload(
                                        null,
                                        null,
                                        null,
                                        List.of(PROVIDER_URI),
                                        null,
                                        null,
                                        null))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("extractionMatrix")
    @DisplayName("yields the whole notice content the document supports")
    void extractsNoticeContent(String description, String fixture, NoticeContent expected) {
        assertThat(extractor.extract(contract(fixture), provider())).isEqualTo(expected);
    }

    /** The content a contract yields, with the controller every case shares filled in. */
    private static PrivacyNoticePayload payload(
            List<String> purposes,
            List<String> categoriesOfData,
            List<DataReference> data,
            List<String> recipients,
            String retentionPeriod,
            String piiPrincipalRights,
            List<String> internationalTransfers) {
        return new PrivacyNoticePayload(
                purposes,
                categoriesOfData,
                data,
                recipients,
                CONTROLLER,
                retentionPeriod,
                piiPrincipalRights,
                internationalTransfers,
                // No contract shape carries a source for it, so every case expects it absent.
                null);
    }

    /** A data reference as generation stores it: the URI, with resolution's fields still empty. */
    private static DataReference reference(String uri) {
        return new DataReference(uri, null, null);
    }

    private static ContractResponse contract(String fixture) {
        try {
            return json.readValue(ContractFixtures.body(fixture), ContractResponse.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not decode contract fixture " + fixture, e);
        }
    }

    private static Participant provider() {
        return new Participant(
                PROVIDER_IDENTIFIER,
                PROVIDER_LEGAL_NAME,
                PROVIDER_URI,
                PROVIDER_EMAIL,
                Map.of(),
                null);
    }
}
