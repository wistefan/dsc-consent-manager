package com.seamware.consentmanager.service;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentEventTrigger;
import com.seamware.consentmanager.domain.ConsentSnapshot;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.UuidGenerator;
import com.seamware.consentmanager.error.BadRequestException;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Pins what the single creation path derives: the consented flag, the event, and the actor. */
@DisplayName("Consent factory")
class ConsentFactoryTest {

    private static final String USER_ACTOR = "did:example:user";

    private static final String PARTICIPANT_ACTOR = "did:example:participant";

    private static final String SYSTEM_ACTOR = "consent-manager/expiry";

    private static final String CONTRACT_URI = "https://contracts.example/offer/1";

    private static final UUID USER_ID = UuidGenerator.uuidV7();

    private static final UUID PRIVACY_NOTICE_ID = UuidGenerator.uuidV7();

    private static final UUID PROVIDER_ID = UuidGenerator.uuidV7();

    private static final UUID CONSUMER_ID = UuidGenerator.uuidV7();

    private static final UUID PARENT_CONSENT_ID = UuidGenerator.uuidV7();

    private static final ConsentSnapshot SNAPSHOT = noTerms();

    private static final Map<String, Object> TERMINATION_DETAILS =
            Map.of("reason", "participant-deregistered");

    private final ConsentFactory factory = new ConsentFactory(new ConsentStateMachine());

    @ParameterizedTest(name = "a consent created {0} is recorded as {1}")
    @MethodSource("creations")
    @DisplayName("a created consent carries the event its status maps to")
    void aCreatedConsentCarriesTheEventItsStatusMapsTo(
            ConsentStatus target, ConsentEventState expected) {
        RecordedConsent recorded = create(target);

        assertThat(recorded.consent().getStatus()).isEqualTo(target);
        assertThat(recorded.event().getEventState()).isEqualTo(expected);
        assertThat(recorded.event().getConsentId()).isEqualTo(recorded.consent().getId());
        assertThat(recorded.event().getActor()).isEqualTo(USER_ACTOR);
        assertThat(recorded.created())
                .as("a creation is an insert, which is what the API answers 201 for")
                .isTrue();
    }

    @ParameterizedTest(name = "a consent created {0} is consented only when granted")
    @EnumSource(
            value = ConsentStatus.class,
            names = {"GRANTED", "REFUSED", "PENDING"})
    @DisplayName("consented is derived from the status, true for a grant alone")
    void consentedIsDerivedFromTheStatus(ConsentStatus target) {
        assertThat(create(target).consent().isConsented())
                .isEqualTo(target == ConsentStatus.GRANTED);
    }

    @ParameterizedTest(name = "a consent cannot be created in {0}")
    @EnumSource(
            value = ConsentStatus.class,
            names = {"DRAFT", "REVOKED", "EXPIRED", "TERMINATED"})
    @DisplayName("a status this service does not create is refused")
    void aStatusThisServiceDoesNotCreateIsRefused(ConsentStatus target) {
        assertThatThrownBy(() -> create(target))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(target.name());
    }

    @Test
    @DisplayName("every argument reaches the field it names, so no two identifiers transpose")
    void everyArgumentReachesTheFieldItNames() {
        Consent consent = create(ConsentStatus.GRANTED).consent();

        assertThat(consent.getUserId()).isEqualTo(USER_ID);
        assertThat(consent.getPrivacyNoticeId()).isEqualTo(PRIVACY_NOTICE_ID);
        assertThat(consent.getProviderId()).isEqualTo(PROVIDER_ID);
        assertThat(consent.getConsumerId()).isEqualTo(CONSUMER_ID);
        assertThat(consent.getParentConsentId()).isEqualTo(PARENT_CONSENT_ID);
        assertThat(consent.getContractUri()).isEqualTo(CONTRACT_URI);
        assertThat(consent.getSnapshot()).isSameAs(SNAPSHOT);
    }

    @Test
    @DisplayName("a permitted move re-statuses the consent and records who made it")
    void aPermittedMoveReStatusesTheConsentAndRecordsWhoMadeIt() {
        RecordedConsent granted = create(ConsentStatus.GRANTED);

        RecordedConsent revoked =
                factory.transition(
                        granted.consent(),
                        ConsentStatus.REVOKED,
                        PARTICIPANT_ACTOR,
                        ConsentEventTrigger.EXPLICIT);

        assertThat(revoked.consent().getStatus()).isEqualTo(ConsentStatus.REVOKED);
        assertThat(revoked.consent().isConsented()).isFalse();
        assertThat(revoked.event().getEventState()).isEqualTo(ConsentEventState.CONSENT_REVOKED);
        assertThat(revoked.event().getActor()).isEqualTo(PARTICIPANT_ACTOR);
        assertThat(revoked.created())
                .as("a move writes no new row, so the API owes no 201 for it")
                .isFalse();
    }

    @ParameterizedTest(name = "a {0} move is recorded as event type {1}")
    @CsvSource({"EXPLICIT,explicit", "SYSTEM,system"})
    @DisplayName("the trigger decides the event type, so a job's expiry is not filed as explicit")
    void theTriggerDecidesTheEventType(ConsentEventTrigger trigger, String expected) {
        RecordedConsent granted = create(ConsentStatus.GRANTED);

        RecordedConsent expired =
                factory.transition(granted.consent(), ConsentStatus.EXPIRED, SYSTEM_ACTOR, trigger);

        assertThat(expired.event().getEventType()).isEqualTo(expected);
    }

    @Test
    @DisplayName("no two triggers share an event type, so the distinction survives the write")
    void noTwoTriggersShareAnEventType() {
        assertThat(Stream.of(ConsentEventTrigger.values()).map(ConsentEventTrigger::eventType))
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a move carrying a reason records it on the event")
    void aMoveCarryingAReasonRecordsItOnTheEvent() {
        RecordedConsent pending = create(ConsentStatus.PENDING);

        RecordedConsent terminated =
                factory.transition(
                        pending.consent(),
                        ConsentStatus.TERMINATED,
                        SYSTEM_ACTOR,
                        ConsentEventTrigger.SYSTEM,
                        TERMINATION_DETAILS);

        assertThat(terminated.event().getDetails()).isEqualTo(TERMINATION_DETAILS);
        assertThat(terminated.event().getEventState())
                .isEqualTo(ConsentEventState.CONSENT_TERMINATED);
    }

    @Test
    @DisplayName("a move carrying no reason leaves the details absent")
    void aMoveCarryingNoReasonLeavesTheDetailsAbsent() {
        RecordedConsent granted = create(ConsentStatus.GRANTED);

        RecordedConsent revoked =
                factory.transition(
                        granted.consent(),
                        ConsentStatus.REVOKED,
                        USER_ACTOR,
                        ConsentEventTrigger.EXPLICIT);

        assertThat(revoked.event().getDetails()).isNull();
    }

    @Test
    @DisplayName("a created consent is always explicit, since creation is never automated")
    void aCreatedConsentIsAlwaysExplicit() {
        assertThat(create(ConsentStatus.GRANTED).event().getEventType())
                .isEqualTo(ConsentEventTrigger.EXPLICIT.eventType());
    }

    @Test
    @DisplayName("a refused move leaves the consent as it was")
    void aRefusedMoveLeavesTheConsentAsItWas() {
        RecordedConsent refused = create(ConsentStatus.REFUSED);

        assertThatThrownBy(
                        () ->
                                factory.transition(
                                        refused.consent(),
                                        ConsentStatus.GRANTED,
                                        USER_ACTOR,
                                        ConsentEventTrigger.EXPLICIT))
                .isInstanceOf(BadRequestException.class);
        assertThat(refused.consent().getStatus()).isEqualTo(ConsentStatus.REFUSED);
        assertThat(refused.consent().isConsented()).isFalse();
    }

    @Test
    @DisplayName("an affirmation appends an event and leaves the status alone")
    void anAffirmationAppendsAnEventAndLeavesTheStatusAlone() {
        RecordedConsent granted = create(ConsentStatus.GRANTED);

        RecordedConsent affirmed = factory.affirm(granted.consent(), USER_ACTOR);

        assertThat(affirmed.consent())
                .as("re-confirmation changes nothing about the record it affirms")
                .isSameAs(granted.consent());
        assertThat(affirmed.consent().getStatus()).isEqualTo(ConsentStatus.GRANTED);
        assertThat(affirmed.consent().isConsented()).isTrue();
        assertThat(affirmed.event().getEventState())
                .isEqualTo(ConsentEventState.CONSENT_RE_CONFIRMED);
        assertThat(affirmed.event().getActor()).isEqualTo(USER_ACTOR);
        assertThat(affirmed.event().getEventType())
                .isEqualTo(ConsentEventTrigger.EXPLICIT.eventType());
        assertThat(affirmed.created()).isFalse();
    }

    @ParameterizedTest(name = "a consent in {0} cannot be affirmed unless it is in force")
    @EnumSource(
            value = ConsentStatus.class,
            names = {"GRANTED"},
            mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("only a consent in force may be affirmed")
    void onlyAConsentInForceMayBeAffirmed(ConsentStatus held) {
        Consent consent =
                Consent.withStatus(
                        held,
                        USER_ID,
                        PRIVACY_NOTICE_ID,
                        PROVIDER_ID,
                        CONSUMER_ID,
                        PARENT_CONSENT_ID,
                        CONTRACT_URI,
                        SNAPSHOT);

        assertThatThrownBy(() -> factory.affirm(consent, USER_ACTOR))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(held.name());
    }

    @Test
    @DisplayName("every status states which events it admits, so a new one cannot slip through")
    void everyStatusStatesWhichEventsItAdmits() {
        assertThat(ConsentFactory.ADMISSIBLE_EVENTS.keySet())
                .as("a missing key is a null lookup, which no per-entry check would catch")
                .isEqualTo(EnumSet.allOf(ConsentStatus.class));
    }

    @ParameterizedTest(name = "the event a move to {0} defaults to is admissible there")
    @MethodSource("defaultedEvents")
    @DisplayName("the two tables cannot drift apart")
    void theTwoTablesCannotDriftApart(ConsentStatus status, ConsentEventState defaulted) {
        assertThat(ConsentFactory.ADMISSIBLE_EVENTS.get(status)).contains(defaulted);
    }

    private RecordedConsent create(ConsentStatus target) {
        return factory.create(
                target,
                USER_ID,
                PRIVACY_NOTICE_ID,
                PROVIDER_ID,
                CONSUMER_ID,
                PARENT_CONSENT_ID,
                CONTRACT_URI,
                SNAPSHOT,
                USER_ACTOR);
    }

    static Stream<Arguments> defaultedEvents() {
        return ConsentFactory.EVENT_STATES.entrySet().stream()
                .map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    static Stream<Arguments> creations() {
        return Stream.of(
                Arguments.of(ConsentStatus.GRANTED, ConsentEventState.CONSENT_GIVEN),
                Arguments.of(ConsentStatus.REFUSED, ConsentEventState.CONSENT_REFUSED),
                Arguments.of(ConsentStatus.PENDING, ConsentEventState.CONSENT_REQUESTED));
    }
}
