package com.seamware.consentmanager.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;
import com.seamware.consentmanager.domain.ConsentEventTrigger;
import com.seamware.consentmanager.domain.ConsentSnapshot;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.IssuedReceipt;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.repository.ConsentEventRepository;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.support.RecordingParticipantNotifications;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the writer's structural issuance hook: every route into {@code GRANTED} issues exactly one
 * receipt, and no other write issues any, so a new caller cannot produce a grant without one.
 */
@DisplayName("Consent writer receipt issuance")
class ConsentWriterTest {

    private static final String SUBJECT = "urn:test:user:writer-subject";

    private static final String CONTRACT_URI = "https://contract.example.org/v1/contracts/1";

    private static final String PURPOSE = "Clinical research";

    private static final ConsentEventTrigger TRIGGER = ConsentEventTrigger.EXPLICIT;

    private final User subject = new User(SUBJECT, null, null, null);

    private final PrivacyNotice notice =
            new PrivacyNotice(CONTRACT_URI, null, UUID.randomUUID(), UUID.randomUUID(), null);

    private final ConsentSnapshot snapshot =
            new ConsentSnapshot(
                    null, List.of(PURPOSE), null, null, null, null, null, null, null, null);

    private final List<Consent> held = new ArrayList<>();

    private final List<ConsentEvent> savedEvents = new ArrayList<>();

    private final List<RecordedConsent> issued = new ArrayList<>();

    private final ConsentWriter writer =
            new ConsentWriter(
                    consents(held),
                    events(savedEvents),
                    new ConsentFactory(new ConsentStateMachine()),
                    new ConsentReceiptIssuer(null, null, null, null) {
                        @Override
                        public IssuedReceipt issue(RecordedConsent granted) {
                            issued.add(granted);
                            return null;
                        }
                    },
                    new RecordingParticipantNotifications());

    /** A write against the consent {@link #hold} seeded, or against none. */
    private interface Write extends BiConsumer<ConsentWriterTest, Consent> {}

    private static Arguments write(String name, ConsentStatus seeded, Write write) {
        return Arguments.of(name, seeded, write);
    }

    /** Every edge {@link ConsentStateMachine} lists into {@code GRANTED}, by each writer route. */
    static Stream<Arguments> grants() {
        return Stream.of(
                write(
                        "creation through decide",
                        null,
                        (test, seeded) ->
                                test.writer.decide(
                                        test.subject,
                                        test.notice,
                                        ConsentStatus.GRANTED,
                                        test.snapshot)),
                write(
                        "a grant superseding the grant in force",
                        ConsentStatus.GRANTED,
                        (test, seeded) ->
                                test.writer.decide(
                                        test.subject,
                                        test.notice,
                                        ConsentStatus.GRANTED,
                                        test.snapshot)),
                write(
                        "PENDING -> GRANTED through decide",
                        ConsentStatus.PENDING,
                        (test, seeded) ->
                                test.writer.decide(
                                        test.subject,
                                        test.notice,
                                        ConsentStatus.GRANTED,
                                        test.snapshot)),
                write(
                        "PENDING -> GRANTED through resume",
                        ConsentStatus.PENDING,
                        (test, seeded) -> test.writer.resume(seeded.getId(), SUBJECT)),
                write(
                        "PENDING -> GRANTED through move",
                        ConsentStatus.PENDING,
                        (test, seeded) ->
                                test.writer.move(
                                        seeded.getId(), ConsentStatus.GRANTED, SUBJECT, TRIGGER)),
                write(
                        "DRAFT -> GRANTED through move",
                        ConsentStatus.DRAFT,
                        (test, seeded) ->
                                test.writer.move(
                                        seeded.getId(), ConsentStatus.GRANTED, SUBJECT, TRIGGER)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("grants")
    @DisplayName("issues exactly one receipt, for the grant, after its event is saved")
    void issuesOnceForEveryGrant(String name, ConsentStatus seeded, Write write) {
        Consent target = hold(seeded);

        write.accept(this, target);

        assertThat(issued).hasSize(1);
        RecordedConsent granted = issued.getFirst();
        assertThat(granted.consent().getStatus()).isEqualTo(ConsentStatus.GRANTED);
        assertThat(savedEvents).contains(granted.event());
    }

    /** Writes that record any status but {@code GRANTED}, or record no status at all. */
    static Stream<Arguments> nonGrants() {
        return Stream.of(
                write(
                        "creation as REFUSED",
                        null,
                        (test, seeded) ->
                                test.writer.decide(
                                        test.subject,
                                        test.notice,
                                        ConsentStatus.REFUSED,
                                        test.snapshot)),
                write(
                        "creation as PENDING",
                        null,
                        (test, seeded) ->
                                test.writer.request(
                                        test.subject, test.notice, test.snapshot, SUBJECT)),
                write(
                        "PENDING -> REFUSED through decide",
                        ConsentStatus.PENDING,
                        (test, seeded) ->
                                test.writer.decide(
                                        test.subject,
                                        test.notice,
                                        ConsentStatus.REFUSED,
                                        test.snapshot)),
                write(
                        "PENDING -> TERMINATED",
                        ConsentStatus.PENDING,
                        moveTo(ConsentStatus.TERMINATED)),
                write("DRAFT -> REFUSED", ConsentStatus.DRAFT, moveTo(ConsentStatus.REFUSED)),
                write("GRANTED -> REVOKED", ConsentStatus.GRANTED, moveTo(ConsentStatus.REVOKED)),
                write(
                        "GRANTED -> TERMINATED",
                        ConsentStatus.GRANTED,
                        moveTo(ConsentStatus.TERMINATED)),
                write("GRANTED -> EXPIRED", ConsentStatus.GRANTED, moveTo(ConsentStatus.EXPIRED)),
                write(
                        "affirming a grant",
                        ConsentStatus.GRANTED,
                        (test, seeded) -> test.writer.affirm(seeded.getId(), SUBJECT)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonGrants")
    @DisplayName("issues nothing for a write that records no grant")
    void issuesNothingWithoutAGrant(String name, ConsentStatus seeded, Write write) {
        Consent target = hold(seeded);

        write.accept(this, target);

        assertThat(issued).isEmpty();
        assertThat(savedEvents).isNotEmpty();
    }

    private static Write moveTo(ConsentStatus target) {
        return (test, seeded) -> test.writer.move(seeded.getId(), target, SUBJECT, TRIGGER);
    }

    /** Seeds a consent of the subject on the notice in {@code status}; none when it is null. */
    private Consent hold(ConsentStatus status) {
        if (status == null) {
            return null;
        }
        Consent consent =
                Consent.withStatus(
                        status,
                        subject.getId(),
                        notice.getId(),
                        notice.getProviderId(),
                        notice.getConsumerId(),
                        null,
                        CONTRACT_URI,
                        snapshot);
        held.add(consent);
        return consent;
    }

    /** A {@link ConsentRepository} over {@code held}, whose writes return what they are given. */
    private static ConsentRepository consents(List<Consent> held) {
        return (ConsentRepository)
                Proxy.newProxyInstance(
                        ConsentRepository.class.getClassLoader(),
                        new Class<?>[] {ConsentRepository.class},
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "findByUserIdAndPrivacyNoticeIdForUpdate" ->
                                            List.copyOf(held);
                                    case "findById", "findByIdForUpdate" ->
                                            held.stream()
                                                    .filter(c -> c.getId().equals(args[0]))
                                                    .findFirst();
                                    case "save", "update" -> args[0];
                                    default ->
                                            throw new UnsupportedOperationException(
                                                    method.getName());
                                });
    }

    /** A {@link ConsentEventRepository} recording every event saved into {@code saved}. */
    private static ConsentEventRepository events(List<ConsentEvent> saved) {
        return (ConsentEventRepository)
                Proxy.newProxyInstance(
                        ConsentEventRepository.class.getClassLoader(),
                        new Class<?>[] {ConsentEventRepository.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("save")) {
                                saved.add((ConsentEvent) args[0]);
                                return args[0];
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }
}
