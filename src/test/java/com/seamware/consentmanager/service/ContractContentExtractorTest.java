package com.seamware.consentmanager.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.ControllerDetails;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.DataReference;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.Recipient;
import com.seamware.consentmanager.support.ContractFixtures;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins what each contract document becomes as a privacy notice. Each case asserts the whole
 * content, so an absent heading cannot quietly start arriving as an empty string or list.
 */
@DisplayName("Contract content extraction")
class ContractContentExtractorTest {

    private static final String PROVIDER_IDENTIFIER = "did:web:provider.example.org";
    private static final String PROVIDER_LEGAL_NAME = "Example Provider GmbH";
    private static final String PROVIDER_EMAIL = "privacy@provider.example.org";
    private static final String CATALOG = "https://catalog.example.org/v1/catalog/";
    private static final String CONSUMER_IDENTIFIER = "did:web:consumer.example.org";
    private static final String PROVIDER_URI = CATALOG + "participants/provider";
    private static final String ECOSYSTEM_URI = CATALOG + "ecosystems/mobility-data-space";
    private static final String TRIP_HISTORY_URI = CATALOG + "serviceofferings/trip-history";
    private static final String CHARGING_SESSIONS_URI =
            CATALOG + "serviceofferings/charging-sessions";
    private static final String METER_READINGS_URI = CATALOG + "serviceofferings/meter-readings";

    /** What a disclosure the contract declares without naming the party is reported as. */
    private static final String UNNAMED_THIRD_PARTY = "urn:consent-manager:third-party:unnamed";

    private static final ControllerDetails CONTROLLER =
            new ControllerDetails(
                    PROVIDER_LEGAL_NAME, PROVIDER_EMAIL, PROVIDER_IDENTIFIER, PROVIDER_URI);

    private final ContractContentExtractor extractor = new ContractContentExtractor();

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
                                        List.of(PROVIDER_IDENTIFIER, CONSUMER_IDENTIFIER),
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
                                        List.of(PROVIDER_IDENTIFIER, CONSUMER_IDENTIFIER),
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
                                        List.of(PROVIDER_IDENTIFIER, CONSUMER_IDENTIFIER),
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
                                        List.of(PROVIDER_IDENTIFIER, CONSUMER_IDENTIFIER),
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
                                        // The consumer's route-quality offering stays out: the
                                        // notice names only what this provider offers.
                                        List.of(reference(TRIP_HISTORY_URI)),
                                        List.of(PROVIDER_IDENTIFIER, CONSUMER_IDENTIFIER),
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
                                        List.of(PROVIDER_IDENTIFIER, CONSUMER_IDENTIFIER),
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
                                        List.of(PROVIDER_IDENTIFIER),
                                        null,
                                        null,
                                        null))),
                Arguments.of(
                        "ecosystem contract stating terms, which fill the principal's rights",
                        ContractFixtures.ECOSYSTEM_TERMS,
                        new NoticeContent(
                                ECOSYSTEM_URI,
                                payload(
                                        null,
                                        null,
                                        null,
                                        List.of(PROVIDER_IDENTIFIER),
                                        null,
                                        // Read from whichever shape states it: the heading is
                                        // filled by the field's presence, not by the shape.
                                        "Members of this ecosystem answer a PII principal's access"
                                                + " and erasure requests within one month.",
                                        null))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("extractionMatrix")
    @DisplayName("yields the whole notice content the document supports")
    void extractsNoticeContent(String description, String fixture, NoticeContent expected) {
        assertThat(extractor.extract(ContractFixtures.contract(fixture), provider()))
                .isEqualTo(expected);
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
                recipients(recipients),
                CONTROLLER,
                retentionPeriod,
                piiPrincipalRights,
                internationalTransfers,
                // No contract shape carries a source for it, so every case expects it absent.
                null);
    }

    /** The recipients as generation stores them: the contract's identifiers, nothing resolved. */
    private static List<Recipient> recipients(List<String> identifiers) {
        return identifiers == null
                ? null
                : identifiers.stream()
                        .map(identifier -> new Recipient(identifier, null, null, null))
                        .toList();
    }

    /** A data reference as generation stores it: the URI, with resolution's fields still empty. */
    private static DataReference reference(String uri) {
        return DataReference.offering(uri);
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
