package com.seamware.consentmanager.api;

import static com.seamware.consentmanager.support.PrivacyNoticeFixtures.emptyPayload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.seamware.consentmanager.api.generated.model.PrivacyNotice;
import com.seamware.consentmanager.api.generated.model.PrivacyNoticeResolution;
import com.seamware.consentmanager.api.generated.model.ResolutionState;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.ControllerDetails;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.DataReference;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.Recipient;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Payload cases an endpoint test cannot produce: a heading with no source in the contract must be
 * left out, not rendered empty. {@code PrivacyNotice} here is the generated API model.
 */
@DisplayName("Mapping a stored privacy notice to its representation")
class PrivacyNoticeMapperTest {

    private static final String CONTRACT_URI =
            "https://contracts.example.org/contracts/mobility-insights";

    private static final String PROVIDER = "urn:test:participant:provider";

    private static final String CONSUMER = "urn:test:participant:consumer";

    private static final String TITLE = "Mobility insights";

    private static final String OFFERING_URI = "https://catalog.example.org/offerings/trips";

    private static final String RESOURCE_URI = "https://catalog.example.org/dataresources/trips";

    private static final Instant CREATED_AT = Instant.parse("2026-01-02T03:04:05Z");

    private static final Instant UPDATED_AT = Instant.parse("2026-02-03T04:05:06Z");

    private static final Instant ARCHIVED_AT = Instant.parse("2026-03-04T05:06:07Z");

    private final PrivacyNoticeMapper mapper = new PrivacyNoticeMapper();

    @Test
    @DisplayName("every heading the payload carries reaches the representation")
    void mapsAFullPayload() {
        PrivacyNotice notice = render(fullPayload(), CONSUMER);

        assertThat(notice.getContractUri()).isEqualTo(CONTRACT_URI);
        assertThat(notice.getTitle()).isEqualTo(TITLE);
        assertThat(notice.getDataProvider()).isEqualTo(PROVIDER);
        assertThat(notice.getDataConsumer()).isEqualTo(CONSUMER);
        assertThat(notice.getPurposes()).containsExactly("Produce aggregated mobility insights.");
        assertThat(notice.getCategoriesOfData()).containsExactly("location");
        assertThat(notice.getData())
                .as("partOf travels too, so a resource is not served as if it were an offering")
                .extracting("uri", "title", "description", "partOf")
                .containsExactly(tuple(RESOURCE_URI, "Trips", "Raw trips", OFFERING_URI));
        assertThat(notice.getRecipients())
                .as("generation stores the identifier alone; the rest is resolution's to fill")
                .extracting("identifier", "name", "contact", "selfDescriptionUri")
                .containsExactly(tuple(CONSUMER, null, null, null));
        assertThat(notice.getControllerDetails().getName()).isEqualTo("Provider Ltd");
        assertThat(notice.getControllerDetails().getContact()).isEqualTo("dpo@provider.example");
        assertThat(notice.getRetentionPeriod()).isEqualTo("P1Y");
        assertThat(notice.getPiiPrincipalRights()).startsWith("The PII principal");
        assertThat(notice.getInternationalTransfers()).containsExactly("Analytics Inc");
        assertThat(notice.getWithdrawalOfConsent()).isEqualTo("Revoke the consent record.");
    }

    @ParameterizedTest(name = "{0} is absent, not empty, when the payload carries no source")
    @MethodSource("sections")
    void leavesASectionWithNoSourceOut(String section, Function<PrivacyNotice, Object> accessor) {
        assertThat(accessor.apply(render(emptyPayload(), CONSUMER))).as(section).isNull();
    }

    private static Stream<Arguments> sections() {
        return Stream.of(
                section("purposes", PrivacyNotice::getPurposes),
                section("categoriesOfData", PrivacyNotice::getCategoriesOfData),
                section("data", PrivacyNotice::getData),
                section("recipients", PrivacyNotice::getRecipients),
                section("controllerDetails", PrivacyNotice::getControllerDetails),
                section("retentionPeriod", PrivacyNotice::getRetentionPeriod),
                section("piiPrincipalRights", PrivacyNotice::getPiiPrincipalRights),
                section("internationalTransfers", PrivacyNotice::getInternationalTransfers),
                section("withdrawalOfConsent", PrivacyNotice::getWithdrawalOfConsent));
    }

    private static Arguments section(String name, Function<PrivacyNotice, Object> accessor) {
        return Arguments.of(name, accessor);
    }

    @Test
    @DisplayName("timestamps are published at UTC, and an unarchived notice carries no archivedAt")
    void mapsTimestamps() {
        com.seamware.consentmanager.domain.PrivacyNotice stored = stored(emptyPayload());

        PrivacyNotice notice =
                mapper.toApi(stored, PROVIDER, CONSUMER, PrivacyNoticeMapper.unresolved());

        assertThat(notice.getCreatedAt())
                .isEqualTo(OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC));
        assertThat(notice.getUpdatedAt())
                .isEqualTo(OffsetDateTime.ofInstant(UPDATED_AT, ZoneOffset.UTC));
        assertThat(notice.getArchivedAt()).isNull();

        stored.setArchivedAt(ARCHIVED_AT);
        assertThat(
                        mapper.toApi(stored, PROVIDER, CONSUMER, PrivacyNoticeMapper.unresolved())
                                .getArchivedAt())
                .isEqualTo(OffsetDateTime.ofInstant(ARCHIVED_AT, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a notice with no consumer publishes none rather than an empty string")
    void mapsAnAbsentConsumer() {
        assertThat(render(emptyPayload(), null).getDataConsumer()).isNull();
    }

    @Test
    @DisplayName("each unresolved block is a fresh instance, so no response aliases another's")
    void unresolvedIsNotShared() {
        PrivacyNoticeResolution first = PrivacyNoticeMapper.unresolved();
        PrivacyNoticeResolution second = PrivacyNoticeMapper.unresolved();

        first.setContract(ResolutionState.UNAVAILABLE);

        assertThat(second.getContract()).isEqualTo(ResolutionState.NOT_REQUESTED);
    }

    private PrivacyNotice render(PrivacyNoticePayload payload, String consumer) {
        return mapper.toApi(stored(payload), PROVIDER, consumer, PrivacyNoticeMapper.unresolved());
    }

    private static PrivacyNoticePayload fullPayload() {
        return new PrivacyNoticePayload(
                List.of("Produce aggregated mobility insights."),
                List.of("location"),
                List.of(new DataReference(RESOURCE_URI, "Trips", "Raw trips", OFFERING_URI)),
                List.of(new Recipient(CONSUMER, null, null, null)),
                new ControllerDetails(
                        "Provider Ltd",
                        "dpo@provider.example",
                        PROVIDER,
                        "https://catalog.example.org/participants/provider"),
                "P1Y",
                "The PII principal may request access to the data held about them.",
                List.of("Analytics Inc"),
                "Revoke the consent record.");
    }

    private static com.seamware.consentmanager.domain.PrivacyNotice stored(
            PrivacyNoticePayload payload) {
        com.seamware.consentmanager.domain.PrivacyNotice stored =
                new com.seamware.consentmanager.domain.PrivacyNotice(
                        CONTRACT_URI, TITLE, UUID.randomUUID(), UUID.randomUUID(), payload);
        stored.setCreatedAt(CREATED_AT);
        stored.setUpdatedAt(UPDATED_AT);
        return stored;
    }
}
