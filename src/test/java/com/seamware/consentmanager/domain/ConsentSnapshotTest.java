package com.seamware.consentmanager.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.PrivacyNoticePayload.ControllerDetails;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.DataReference;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.Recipient;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Pins what a snapshot freezes: the elected scope, and everything else verbatim. */
@DisplayName("Consent snapshot")
class ConsentSnapshotTest {

    private static final String CONTRACT_URI = "https://contracts.example/offer/1";

    private static final String NOTICE_TITLE = "Privacy notice for offer 1";

    private static final String RETENTION_PERIOD = "P1Y";

    private static final String RIGHTS = "Contact the controller to exercise your rights.";

    private static final String WITHDRAWAL = "Revoke through the consent manager at any time.";

    private static final List<String> DECLARED_PURPOSES = List.of("analytics", "billing");

    private static final List<String> DECLARED_CATEGORIES = List.of("contact", "telemetry");

    private static final List<String> TRANSFERS = List.of("none");

    private static final DataReference REFERENCE =
            DataReference.offering("https://catalog.example/offering/1");

    private static final Recipient RECIPIENT =
            new Recipient("did:example:consumer", null, null, null);

    private static final ControllerDetails CONTROLLER =
            new ControllerDetails("Provider", "ops@example", "did:example:provider", null);

    @ParameterizedTest(name = "electing {0} freezes {1}")
    @MethodSource("elections")
    @DisplayName("an election narrows the notice's scope and never widens it")
    void anElectionNarrowsTheNoticesScope(
            List<String> electedPurposes, List<String> expectedPurposes) {
        ConsentSnapshot snapshot = ConsentSnapshot.of(notice(), electedPurposes, null);

        assertThat(snapshot.purposes()).isEqualTo(expectedPurposes);
        assertThat(snapshot.dataCategories()).isEqualTo(DECLARED_CATEGORIES);
    }

    @Test
    @DisplayName("the non-scope terms and the title are copied from the notice verbatim")
    void theNonScopeTermsAreCopiedVerbatim() {
        ConsentSnapshot snapshot = ConsentSnapshot.of(notice(), null, null);

        assertThat(snapshot.title()).isEqualTo(NOTICE_TITLE);
        assertThat(snapshot.dataReferences()).containsExactly(REFERENCE);
        assertThat(snapshot.recipients()).containsExactly(RECIPIENT);
        assertThat(snapshot.controllerDetails()).isEqualTo(CONTROLLER);
        assertThat(snapshot.retentionPeriod()).isEqualTo(RETENTION_PERIOD);
        assertThat(snapshot.piiPrincipalRights()).isEqualTo(RIGHTS);
        assertThat(snapshot.internationalTransfers()).isEqualTo(TRANSFERS);
        assertThat(snapshot.withdrawalOfConsent()).isEqualTo(WITHDRAWAL);
    }

    @Test
    @DisplayName("a notice carrying no terms freezes none, rather than empty ones")
    void aNoticeCarryingNoTermsFreezesNone() {
        PrivacyNotice bare =
                new PrivacyNotice(
                        CONTRACT_URI,
                        null,
                        UuidGenerator.uuidV7(),
                        UuidGenerator.uuidV7(),
                        new PrivacyNoticePayload(
                                null, null, null, null, null, null, null, null, null));

        ConsentSnapshot snapshot = ConsentSnapshot.of(bare, null, null);

        assertThat(snapshot.purposes()).isNull();
        assertThat(snapshot.dataCategories()).isNull();
        assertThat(snapshot.recipients()).isNull();
        assertThat(snapshot.title()).isNull();
    }

    static Stream<Arguments> elections() {
        return Stream.of(
                Arguments.of(List.of("analytics"), List.of("analytics")),
                Arguments.of(null, DECLARED_PURPOSES),
                Arguments.of(List.of(), List.of()));
    }

    private static PrivacyNotice notice() {
        UUID provider = UuidGenerator.uuidV7();
        UUID consumer = UuidGenerator.uuidV7();
        PrivacyNoticePayload payload =
                new PrivacyNoticePayload(
                        DECLARED_PURPOSES,
                        DECLARED_CATEGORIES,
                        List.of(REFERENCE),
                        List.of(RECIPIENT),
                        CONTROLLER,
                        RETENTION_PERIOD,
                        RIGHTS,
                        TRANSFERS,
                        WITHDRAWAL);
        return new PrivacyNotice(CONTRACT_URI, NOTICE_TITLE, provider, consumer, payload);
    }
}
