package com.seamware.consentmanager.service;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static com.seamware.consentmanager.support.ConsentFixtures.participant;
import static com.seamware.consentmanager.support.ConsentFixtures.principalFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.error.BadRequestException;
import com.seamware.consentmanager.observability.ConsentMetrics;
import com.seamware.consentmanager.repository.ConsentEventRepository;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.support.PostgresTestResource;
import com.seamware.consentmanager.support.PrivacyNoticeFixtures;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests that every successful lifecycle move reports exactly once to {@link ConsentStateChangeLog},
 * a refused one reports nothing, and a resume also reports its collateral moves. A capturing stub
 * counts the calls; what the real seam logs is not under test.
 */
@MicronautTest(transactional = false)
@Property(name = "spec.name", value = "ConsentLifecycleSeamIT")
@DisplayName("The lifecycle notification seam")
class ConsentLifecycleSeamIT extends PostgresTestResource {

    private static final String PROVIDER = "urn:test:participant:seam-provider";

    private static final String CONSUMER = "urn:test:participant:seam-consumer";

    private static final String CONTRACT_URI = "urn:test:contract:seam";

    private static final String NOTICE_TITLE = "Notice the seam cases act on";

    private static final String ISSUER = "https://issuer.test/realms/seam";

    private static final List<ConsentStatus> ALL_STATUSES = List.of(ConsentStatus.values());

    @Inject ConsentLifecycleService lifecycle;

    @Inject CapturingChangeLog changeLog;

    @Inject ConsentRepository consents;

    @Inject ConsentEventRepository events;

    @Inject PrivacyNoticeRepository notices;

    @Inject ParticipantRepository participants;

    @Inject UserRepository users;

    private final List<User> seededUsers = new ArrayList<>();

    private final List<UUID> seededNotices = new ArrayList<>();

    @BeforeEach
    void clearCaptures() {
        changeLog.reset();
    }

    @AfterEach
    void removeCommittedRows() {
        seededUsers.forEach(
                user -> {
                    consents.findByUserIdAndStatusIn(user.getId(), ALL_STATUSES)
                            .forEach(
                                    consent -> {
                                        events.findByConsentIdOrderByOccurredAtAsc(consent.getId())
                                                .forEach(events::delete);
                                        consents.delete(consent);
                                    });
                    users.delete(user);
                });
        seededUsers.clear();
        seededNotices.forEach(notices::deleteById);
        seededNotices.clear();
    }

    @AfterAll
    void removeParticipants() {
        List.of(PROVIDER, CONSUMER)
                .forEach(
                        identifier ->
                                participants
                                        .findByIdentifier(identifier)
                                        .ifPresent(participants::delete));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Move.class)
    @DisplayName("a successful move reports to the seam exactly once, naming what it moved from")
    void reportsEverySuccessfulMoveOnce(Move move) {
        User subject = user();
        Participant party = participant(participants, PROVIDER);
        UUID id = seedConsent(subject, notice(), move.from);

        move.apply(lifecycle, subject, party, id);

        assertThat(changeLog.captured())
                .singleElement()
                .satisfies(
                        report -> {
                            assertThat(report.consentId()).isEqualTo(id);
                            assertThat(report.held()).isEqualTo(move.from);
                            assertThat(report.now()).isEqualTo(move.to);
                        });
    }

    @Test
    @DisplayName("a refused move reports nothing, so status and seam cannot diverge")
    void reportsNothingOnARefusedMove() {
        User subject = user();
        UUID pending = seedConsent(subject, notice(), ConsentStatus.PENDING);

        assertThatThrownBy(() -> lifecycle.revoke(subject, pending))
                .isInstanceOf(BadRequestException.class);

        assertThat(changeLog.captured()).isEmpty();
    }

    @Test
    @DisplayName(
            "a resume reports the grant it superseded and the request it closed, not only its own")
    void reportsTheCollateralMovesAResumeMakes() {
        User subject = user();
        PrivacyNotice notice = notice();
        UUID inForce = seedConsent(subject, notice, ConsentStatus.GRANTED);
        UUID straggler = seedConsent(subject, notice, ConsentStatus.PENDING);
        UUID requested = seedConsent(subject, notice, ConsentStatus.PENDING);

        lifecycle.resume(principalFor(ISSUER, subject), requested, null);

        assertThat(changeLog.captured())
                .as("the resumed grant, the grant it revoked and the request it terminated")
                .hasSize(3)
                .anySatisfy(
                        report ->
                                assertMove(
                                        report,
                                        requested,
                                        ConsentStatus.PENDING,
                                        ConsentStatus.GRANTED))
                .anySatisfy(
                        report ->
                                assertMove(
                                        report,
                                        inForce,
                                        ConsentStatus.GRANTED,
                                        ConsentStatus.REVOKED))
                .anySatisfy(
                        report ->
                                assertMove(
                                        report,
                                        straggler,
                                        ConsentStatus.PENDING,
                                        ConsentStatus.TERMINATED));
    }

    private static void assertMove(
            Reported report, UUID consentId, ConsentStatus held, ConsentStatus now) {
        assertThat(report.consentId()).isEqualTo(consentId);
        assertThat(report.held()).isEqualTo(held);
        assertThat(report.now()).isEqualTo(now);
    }

    private UUID seedConsent(User subject, PrivacyNotice notice, ConsentStatus status) {
        return consents.save(
                        Consent.withStatus(
                                status,
                                subject.getId(),
                                notice.getId(),
                                notice.getProviderId(),
                                notice.getConsumerId(),
                                null,
                                CONTRACT_URI,
                                noTerms()))
                .getId();
    }

    private User user() {
        User subject =
                users.save(new User("urn:test:user:seam:" + UUID.randomUUID(), null, null, null));
        seededUsers.add(subject);
        return subject;
    }

    private PrivacyNotice notice() {
        PrivacyNotice notice =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI,
                                NOTICE_TITLE,
                                participant(participants, PROVIDER).getId(),
                                participant(participants, CONSUMER).getId(),
                                PrivacyNoticeFixtures.emptyPayload()));
        seededNotices.add(notice.getId());
        return notice;
    }

    /** One captured report: the consent, the status the write locked, and the one it left. */
    record Reported(UUID consentId, ConsentStatus held, ConsentStatus now) {}

    /** The moves the seam is swept over, each on a status it is permitted from. */
    private enum Move {
        REVOKE(ConsentStatus.GRANTED, ConsentStatus.REVOKED),
        REFUSE(ConsentStatus.PENDING, ConsentStatus.REFUSED),
        TERMINATE(ConsentStatus.GRANTED, ConsentStatus.TERMINATED),
        RE_CONFIRM(ConsentStatus.GRANTED, ConsentStatus.GRANTED),
        RESUME(ConsentStatus.PENDING, ConsentStatus.GRANTED);

        private final ConsentStatus from;
        private final ConsentStatus to;

        Move(ConsentStatus from, ConsentStatus to) {
            this.from = from;
            this.to = to;
        }

        void apply(ConsentLifecycleService lifecycle, User subject, Participant party, UUID id) {
            switch (this) {
                case REVOKE -> lifecycle.revoke(subject, id);
                case REFUSE -> lifecycle.refuse(subject, id);
                case TERMINATE -> lifecycle.terminate(party, id);
                case RE_CONFIRM -> lifecycle.reConfirm(subject, id);
                case RESUME -> lifecycle.resume(principalFor(ISSUER, subject), id, null);
            }
        }
    }

    /**
     * Stands in for {@link ConsentStateChangeLog} and records every reported move. Active for this
     * suite alone, keyed on {@code spec.name}.
     */
    @Singleton
    @Replaces(ConsentStateChangeLog.class)
    @Requires(property = "spec.name", value = "ConsentLifecycleSeamIT")
    static class CapturingChangeLog extends ConsentStateChangeLog {

        private final List<Reported> captured = new CopyOnWriteArrayList<>();

        CapturingChangeLog(ConsentMetrics metrics) {
            super(metrics);
        }

        @Override
        public void recorded(
                @NonNull ConsentStatus held,
                @NonNull ConsentDetails moved,
                @NonNull ConsentEvent event) {
            captured.add(new Reported(moved.consent().getId(), held, moved.consent().getStatus()));
        }

        List<Reported> captured() {
            return List.copyOf(captured);
        }

        void reset() {
            captured.clear();
        }
    }
}
