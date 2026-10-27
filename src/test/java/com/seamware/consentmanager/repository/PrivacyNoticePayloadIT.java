package com.seamware.consentmanager.repository;

import static com.seamware.consentmanager.support.PrivacyNoticeFixtures.emptyPayload;
import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.ControllerDetails;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.DataReference;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.Recipient;
import com.seamware.consentmanager.support.PostgresTestResource;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves a notice payload survives the JSONB column unchanged, nested records and absences
 * included; regeneration compares payloads, so a lossy round-trip would archive on every run.
 */
@MicronautTest(transactional = false)
@DisplayName("Privacy notice payload persistence")
class PrivacyNoticePayloadIT extends PostgresTestResource {

    private static final String PROVIDER_PARTICIPANT = "urn:test:participant:payload-provider";
    private static final String CONSUMER_PARTICIPANT = "urn:test:participant:payload-consumer";
    private static final String CONTRACT_URI = "https://contract.example.org/v1/contracts/payload";

    @Inject ParticipantRepository participants;

    @Inject PrivacyNoticeRepository notices;

    /** The payloads a stored notice must come back as, byte for byte in meaning. */
    static Stream<Arguments> payloads() {
        return Stream.of(
                Arguments.of("every section populated", populatedPayload()),
                Arguments.of("every section absent", emptyPayload()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("payloads")
    @DisplayName("reads back as it was written")
    void roundTripsThroughJsonb(String description, PrivacyNoticePayload payload) {
        Participant provider = seeded(PROVIDER_PARTICIPANT);
        Participant consumer = seeded(CONSUMER_PARTICIPANT);

        PrivacyNotice saved =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI + "/" + UUID.randomUUID(),
                                "Payload round trip",
                                provider.getId(),
                                consumer.getId(),
                                payload));

        PrivacyNotice reread = notices.findById(saved.getId()).orElseThrow();
        assertThat(reread.getPayload()).isEqualTo(payload);
    }

    /** A payload with every heading filled, including both nested record types. */
    private static PrivacyNoticePayload populatedPayload() {
        return new PrivacyNoticePayload(
                List.of("Produce aggregated mobility insights.", "Schedule road maintenance."),
                List.of("Location data", "Vehicle data"),
                List.of(
                        new DataReference(
                                "https://catalog.example.org/v1/catalog/dataresources/trips",
                                "Trip history",
                                "Trips taken, per vehicle and day.",
                                "https://catalog.example.org/v1/catalog/serviceofferings/trips"),
                        DataReference.offering(
                                "https://catalog.example.org/v1/catalog/serviceofferings/routes")),
                List.of(
                        new Recipient(PROVIDER_PARTICIPANT, null, null, null),
                        new Recipient(
                                CONSUMER_PARTICIPANT,
                                "Example Research Lab",
                                "privacy@lab.example.org",
                                "https://catalog.example.org/v1/catalog/participants/lab")),
                new ControllerDetails(
                        "Example Provider GmbH",
                        "privacy@provider.example.org",
                        PROVIDER_PARTICIPANT,
                        "https://catalog.example.org/v1/catalog/participants/provider"),
                "P30D",
                "The PII principal may request access to the data processed under this contract.",
                List.of("Example Grid Operator"),
                "Consent is withdrawn through this service.");
    }

    private Participant seeded(String identifier) {
        return participant(identifier)
                .orElseGet(
                        () ->
                                participants.save(
                                        new Participant(
                                                identifier,
                                                identifier,
                                                null,
                                                null,
                                                Map.of(),
                                                null)));
    }

    private Optional<Participant> participant(String identifier) {
        return participants.findByIdentifier(identifier);
    }
}
