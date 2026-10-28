package com.seamware.consentmanager.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.error.ConflictException;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.support.PostgresTestResource;
import com.seamware.consentmanager.support.Race;
import com.seamware.consentmanager.support.RawSql;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Races many grants on one (user, notice) pair, fresh or already granted, and asserts at the
 * database that one consent is in force and every other one was superseded exactly once.
 */
@MicronautTest(transactional = false)
@DisplayName("Concurrent re-grant")
class ConcurrentRegrantIT extends PostgresTestResource {

    /** Grants racing for the pair; enough that losers meet both the lock and the unique index. */
    private static final int RACERS = 8;

    private static final String PROVIDER = "urn:test:participant:regrant-provider";

    private static final String CONSUMER = "urn:test:participant:regrant-consumer";

    private static final String CONTRACT_URI = "urn:test:contract:regrant";

    private static final String SUBJECT = "urn:test:user:regrant";

    /** The predicate of {@code uq_consents_granted_per_user_notice}, counted per pair. */
    private static final String COUNT_GRANTED =
            "SELECT count(*) FROM consents"
                    + " WHERE user_id = ? AND privacy_notice_id = ? AND status = 'GRANTED'";

    /** Separates event states in {@link #PAIR_HISTORY}'s aggregate. */
    private static final String HISTORY_SEPARATOR = ",";

    /** Each consent of the pair with its predecessor and its event history, oldest event first. */
    private static final String PAIR_HISTORY =
            "SELECT c.id, c.status, c.parent_consent_id,"
                    + " string_agg(e.event_state, '"
                    + HISTORY_SEPARATOR
                    + "' ORDER BY e.occurred_at, e.id) AS history"
                    + " FROM consents c LEFT JOIN consent_events e ON e.consent_id = c.id"
                    + " WHERE c.user_id = ? AND c.privacy_notice_id = ?"
                    + " GROUP BY c.id, c.status, c.parent_consent_id";

    /** The only histories a grant race may leave: in force, or given and then superseded. */
    private static final Map<ConsentStatus, List<ConsentEventState>> EXPECTED_HISTORY =
            Map.of(
                    ConsentStatus.GRANTED,
                    List.of(ConsentEventState.CONSENT_GIVEN),
                    ConsentStatus.REVOKED,
                    List.of(ConsentEventState.CONSENT_GIVEN, ConsentEventState.CONSENT_REVOKED));

    @Inject ConsentService service;

    @Inject PrivacyNoticeRepository notices;

    @Inject ParticipantRepository participants;

    @Inject UserRepository users;

    /** What the pair holds when the race starts, and how many consents that seeds. */
    enum PairState {
        NOT_YET_GRANTED(0),
        ALREADY_GRANTED(1);

        private final int seeded;

        PairState(int seeded) {
            this.seeded = seeded;
        }
    }

    /** One consent of the pair as PostgreSQL stores it. */
    private record StoredConsent(
            UUID id, ConsentStatus status, UUID parent, List<ConsentEventState> history) {}

    @ParameterizedTest(name = "{0}")
    @EnumSource(PairState.class)
    @DisplayName("racing grants leave one consent in force, each loser refused or superseded")
    void racingGrantsLeaveOneConsentInForce(PairState state) {
        User subject = users.save(new User(SUBJECT, null, null, null));
        PrivacyNotice notice = notice();
        if (state == PairState.ALREADY_GRANTED) {
            grant(subject, notice);
        }

        List<Race.Outcome<UUID>> outcomes = Race.run(RACERS, () -> grant(subject, notice));

        assertThat(outcomes)
                .as("a loser is told 409 by the service, never handed a server error")
                .filteredOn(outcome -> !outcome.succeeded())
                .allSatisfy(
                        outcome ->
                                assertThat(outcome.failure())
                                        .isInstanceOf(ConflictException.class));
        long succeeded = outcomes.stream().filter(Race.Outcome::succeeded).count();
        assertThat(succeeded).as("somebody must win the pair").isPositive();
        assertThat(RawSql.count(COUNT_GRANTED, subject.getId(), notice.getId()))
                .as("the one-grant-per-notice index holds at the database")
                .isOne();

        List<StoredConsent> stored = storedPair(subject, notice);
        assertThat(stored)
                .as("each success wrote one row and no refusal left one behind")
                .hasSize(state.seeded + (int) succeeded);
        assertThat(stored)
                .as("every row carries exactly the events its status implies")
                .allSatisfy(
                        consent ->
                                assertThat(consent.history())
                                        .isEqualTo(EXPECTED_HISTORY.get(consent.status())));
        assertThat(stored.stream().map(StoredConsent::parent).filter(Objects::isNull))
                .as("one lineage: only the first grant has no predecessor")
                .hasSize(1);
        assertThat(stored.stream().map(StoredConsent::parent).filter(Objects::nonNull))
                .as("each superseded consent was superseded exactly once")
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(
                        stored.stream()
                                .filter(consent -> consent.status() == ConsentStatus.REVOKED)
                                .map(StoredConsent::id)
                                .toList());
    }

    private UUID grant(User subject, PrivacyNotice notice) {
        return service.decide(
                        subject,
                        new ConsentDecision(notice.getId(), ConsentStatus.GRANTED, null, null))
                .consent()
                .getId();
    }

    private static List<StoredConsent> storedPair(User subject, PrivacyNotice notice) {
        return RawSql.query(
                PAIR_HISTORY,
                row ->
                        new StoredConsent(
                                row.getObject("id", UUID.class),
                                ConsentStatus.valueOf(row.getString("status")),
                                row.getObject("parent_consent_id", UUID.class),
                                historyOf(row.getString("history"))),
                subject.getId(),
                notice.getId());
    }

    private static List<ConsentEventState> historyOf(String aggregated) {
        if (aggregated == null) {
            return List.of();
        }
        return Arrays.stream(aggregated.split(HISTORY_SEPARATOR))
                .map(ConsentEventState::valueOf)
                .toList();
    }

    private PrivacyNotice notice() {
        return notices.save(
                new PrivacyNotice(
                        CONTRACT_URI,
                        null,
                        participant(PROVIDER).getId(),
                        participant(CONSUMER).getId(),
                        new PrivacyNoticePayload(
                                null, null, null, null, null, null, null, null, null)));
    }

    private Participant participant(String identifier) {
        return participants
                .findByIdentifier(identifier)
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
}
