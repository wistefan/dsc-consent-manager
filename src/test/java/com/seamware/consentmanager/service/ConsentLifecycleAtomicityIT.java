package com.seamware.consentmanager.service;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static com.seamware.consentmanager.support.ConsentFixtures.participant;
import static com.seamware.consentmanager.support.ConsentFixtures.principalFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.error.ApiException;
import com.seamware.consentmanager.error.BadRequestException;
import com.seamware.consentmanager.error.ConflictException;
import com.seamware.consentmanager.error.ProblemType;
import com.seamware.consentmanager.repository.ConsentEventRepository;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.PostgresTestResource;
import com.seamware.consentmanager.support.PrivacyNoticeFixtures;
import io.micronaut.jdbc.DataSourceResolver;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves that two lifecycle moves sent at the same instant cannot leave a pair holding two live
 * consents, nor one consent carrying a move recorded twice.
 *
 * <p>Two mechanisms are under test. A move addressing one record is settled by {@code
 * ConsentWriter}'s {@code FOR UPDATE} re-read of that row: the loser is guarded on the status the
 * winner left, so the second revocation is refused rather than appending a second event to a
 * consent already withdrawn. A move that may land in {@code GRANTED} is settled by the {@code
 * (user, notice)} lock the writer takes before reading, which is the same lock {@code
 * ConsentService.decide} takes - so a resume and a decision for one pair serialise, and the second
 * sees what the first left rather than racing it to {@code uq_consents_granted_per_user_notice}.
 *
 * <p>That lock is why neither racing pair here produces the {@code 409} {@code
 * ConsentWriter#resume} documents: both writers take it, and a decision answers the request a
 * resume addresses in place rather than inserting beside it, so the loser is refused on the status
 * the winner left. The index is the backstop for the one interleaving no lock can cover - a grant
 * committed outside the locking read's snapshot, which that read can neither see nor lock - and
 * {@link #refusesAResumeLosingToAGrantOutsideItsSnapshot} seeds that state directly rather than
 * racing for it, so the branch answering it is exercised rather than argued.
 *
 * <p>What the racing cases pin is therefore the end state - the pair holds one consent in force,
 * and no consent records a state twice - rather than one particular status a given interleaving
 * happens to produce. Which racer loses is not fixed by anything here: where the decision reaches
 * the row first the other is refused, and where it does not both moves land legitimately, so the
 * refusal assertions in those two cases hold only of the runs that produced a refusal. The AC 16
 * property itself - a loser answered as a client error rather than a {@code 500} - is pinned where
 * exactly one racer must lose: {@link #concurrentRevocationsAppendOneEvent}, two withdrawals of one
 * consent, and {@link #refusesAResumeLosingToAGrantOutsideItsSnapshot}, whose conflict is seeded
 * rather than raced for.
 */
@MicronautTest(transactional = false)
@DisplayName("Concurrent consent lifecycle moves")
class ConsentLifecycleAtomicityIT extends PostgresTestResource {

    /** Two is the whole hazard: one move wins the row, the other must re-read it. */
    private static final int RACERS = 2;

    private static final String PROVIDER = "urn:test:participant:lifecycle-atomicity-provider";

    private static final String CONSUMER = "urn:test:participant:lifecycle-atomicity-consumer";

    private static final String CONTRACT_URI = "urn:test:contract:lifecycle-atomicity";

    /** The provider of a token this suite never validates; only its shape matters here. */
    private static final String ISSUER = "https://issuer.test/realms/lifecycle-atomicity";

    /** Long enough for a blocked statement to register as waiting, short enough to fail fast. */
    private static final Duration BLOCKED = Duration.ofSeconds(10);

    /**
     * A backend of <em>this</em> database held up by another transaction while writing a consent.
     *
     * <p>Narrow on purpose: the container behind {@link PostgresTestResource} is shared by every
     * suite in the JVM, so a cluster-wide {@code pg_locks} probe would be released by any unrelated
     * backend waiting on anything, letting the held grant commit before the resume ever reached the
     * index. The probe's own backend is excluded because this very statement names {@code
     * consents}.
     */
    private static final String WAITING_BACKENDS =
            "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                    + " AND pid <> pg_backend_pid() AND wait_event_type = 'Lock'"
                    + " AND query ILIKE '%consents%'";

    /** A grant for the pair, written outside anything the service's locking read can see. */
    private static final String INSERT_GRANT =
            "INSERT INTO consents (id, user_id, privacy_notice_id, provider_id, consumer_id,"
                    + " status, consented) VALUES (?, ?, ?, ?, ?, 'GRANTED', true)";

    @Inject ConsentLifecycleService lifecycle;

    @Inject ConsentService consentService;

    @Inject ConsentRepository consents;

    @Inject ConsentEventRepository events;

    @Inject PrivacyNoticeRepository notices;

    @Inject ParticipantRepository participants;

    @Inject UserRepository users;

    @Inject DataSource dataSource;

    @Inject DataSourceResolver dataSources;

    private User subject;

    private PrivacyNotice notice;

    /** What the racers left behind: the consents they moved, and the refusals the losers got. */
    private record Outcomes(List<UUID> moved, List<Throwable> refused) {}

    /** Rows these cases commit outlive their transactions, and a notice holds its parties. */
    @AfterEach
    void removeCommittedRows() {
        if (subject != null) {
            consents.findByUserIdAndStatusIn(subject.getId(), List.of(ConsentStatus.values()))
                    .forEach(
                            consent -> {
                                events.findByConsentIdOrderByOccurredAtAsc(consent.getId())
                                        .forEach(events::delete);
                                consents.delete(consent);
                            });
            users.delete(subject);
            subject = null;
        }
        if (notice != null) {
            notices.deleteById(notice.getId());
            notice = null;
        }
        List.of(PROVIDER, CONSUMER)
                .forEach(
                        identifier ->
                                participants
                                        .findByIdentifier(identifier)
                                        .ifPresent(participants::delete));
    }

    @Test
    @DisplayName("a revocation racing a fresh grant leaves the pair exactly one consent in force")
    void concurrentRevocationAndGrantLeaveOneConsentInForce() throws Exception {
        seedPair();
        UUID inForce = seedConsent(ConsentStatus.GRANTED);

        Outcomes outcomes = race(() -> revoke(inForce), this::grant);

        assertThat(outcomes.refused())
                .as("where this interleaving refused a racer, it refused it as a 400, not a 500")
                .allSatisfy(refusal -> assertThat(refusal).isInstanceOf(BadRequestException.class));
        assertThat(consents.findById(inForce).orElseThrow().getStatus())
                .as("whoever got to it first, the consent the revocation named ends withdrawn")
                .isEqualTo(ConsentStatus.REVOKED);
        assertThat(granted())
                .as("whichever order they land in, the pair ends with one consent in force")
                .hasSize(1);
        assertNoConsentReachedAStateTwice();
    }

    @Test
    @DisplayName("a resume racing a grant of the same pair leaves exactly one consent in force")
    void concurrentResumeAndGrantLeaveOneConsentInForce() throws Exception {
        seedPair();
        UUID requested = seedConsent(ConsentStatus.PENDING);

        Outcomes outcomes = race(() -> resume(requested), this::grant);

        assertThat(outcomes.refused())
                .as("where this interleaving refused a racer, it refused it as a 4xx, not a 500")
                .allSatisfy(ConsentLifecycleAtomicityIT::isClientError);
        assertThat(granted())
                .as("the lock makes the second move supersede the first rather than double it")
                .hasSize(1);
        assertThat(
                        consents.findByUserIdAndStatusIn(
                                subject.getId(), List.of(ConsentStatus.PENDING)))
                .as("the request is answered once rather than left stranded behind the grant")
                .isEmpty();
        assertNoConsentReachedAStateTwice();
    }

    @Test
    @DisplayName("two revocations of one consent withdraw it once and refuse the loser")
    void concurrentRevocationsAppendOneEvent() throws Exception {
        seedPair();
        UUID inForce = seedConsent(ConsentStatus.GRANTED);

        Outcomes outcomes = race(() -> revoke(inForce), () -> revoke(inForce));

        assertThat(outcomes.moved())
                .as("one withdrawal of one consent: the other racer cannot also have moved it")
                .containsExactly(inForce);
        assertThat(outcomes.refused())
                .as("the re-read shows the loser the status the winner left, which is a 400")
                .singleElement()
                .isInstanceOf(BadRequestException.class);
        assertThat(consents.findById(inForce).orElseThrow().getStatus())
                .isEqualTo(ConsentStatus.REVOKED);
        assertThat(
                        events.findByConsentIdOrderByOccurredAtAsc(inForce).stream()
                                .map(ConsentEvent::getEventState)
                                .toList())
                .as("the seeded grant, then one withdrawal: the loser appends nothing")
                .containsExactly(
                        ConsentEventState.CONSENT_GIVEN, ConsentEventState.CONSENT_REVOKED);
    }

    /**
     * The one interleaving the pair lock cannot cover, and the only reachable proof of AC 12.
     *
     * <p>The held grant is invisible to the resume's locking read, so the resume runs on: it
     * terminates {@code straggler}, the open request its own grant would leave redundant, and only
     * then does its own update reach {@code uq_consents_granted_per_user_notice} and fail. That
     * ordering is what makes the case load-bearing twice over - every refusal elsewhere in these
     * suites is thrown by {@link ConsentStateMachine} <em>before</em> the writer is entered, and
     * would read the same with no transaction at all. Here a write has already landed when the
     * failure comes, so a straggler still {@code PENDING} and still carrying no termination is the
     * transaction unwinding rather than the guard refusing. That the close really is written on
     * this fixture, rather than skipped before the conflict, is what {@code
     * ConsentLifecycleIT.closesTheRequestsTheAnswerMakesRedundant} pins on the same two rows with
     * nothing holding the index.
     */
    @Test
    @DisplayName("a resume losing to a grant outside its locking read is refused with nothing kept")
    void refusesAResumeLosingToAGrantOutsideItsSnapshot() throws Exception {
        seedPair();
        UUID requested = seedConsent(ConsentStatus.PENDING);
        UUID straggler = seedConsent(ConsentStatus.PENDING);
        DataSource pool = dataSources.resolve(dataSource);

        try (ExecutorService resuming = Executors.newSingleThreadExecutor();
                Connection uncommitted = pool.getConnection()) {
            uncommitted.setAutoCommit(false);
            insertGrant(uncommitted);
            Future<UUID> resumed = resuming.submit(() -> resume(requested));
            Await.until(
                    "the resume to block on the index entry the held grant has not committed",
                    BLOCKED,
                    () -> isWaitingOnALock(pool));

            uncommitted.commit();

            assertThatThrownBy(resumed::get)
                    .as("the grant the locking read could not see is answered as a 409, not a 500")
                    .hasCauseInstanceOf(ConflictException.class);
        }

        assertThat(consents.findById(requested).orElseThrow().getStatus())
                .as("the request the resume could not answer is left exactly as it was")
                .isEqualTo(ConsentStatus.PENDING);
        assertThat(
                        events.findByConsentIdOrderByOccurredAtAsc(requested).stream()
                                .map(ConsentEvent::getEventState)
                                .toList())
                .as("the ask it was opened with, and nothing the refused resume wrote")
                .containsExactly(ConsentEventState.CONSENT_REQUESTED);
        assertThat(consents.findById(straggler).orElseThrow().getStatus())
                .as("the termination the resume had already written is unwound with the rest")
                .isEqualTo(ConsentStatus.PENDING);
        assertThat(
                        events.findByConsentIdOrderByOccurredAtAsc(straggler).stream()
                                .map(ConsentEvent::getEventState)
                                .toList())
                .as("neither the status nor its event survives a transaction that failed later")
                .containsExactly(ConsentEventState.CONSENT_REQUESTED);
    }

    /** Whether a backend is held up by another transaction, which is the resume blocked below. */
    private static boolean isWaitingOnALock(DataSource pool) {
        try (Connection probe = pool.getConnection();
                PreparedStatement statement = probe.prepareStatement(WAITING_BACKENDS);
                ResultSet rows = statement.executeQuery()) {
            return rows.next() && rows.getLong(1) > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read what the backends are waiting on", e);
        }
    }

    /**
     * Inserts a grant for the pair on a transaction the caller has not committed.
     *
     * <p>Raw SQL on a held connection rather than the repository, because the point is a row the
     * resume's locking read cannot see: a committed one is found and superseded instead.
     */
    private void insertGrant(Connection held) throws SQLException {
        try (PreparedStatement statement = held.prepareStatement(INSERT_GRANT)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, subject.getId());
            statement.setObject(3, notice.getId());
            statement.setObject(4, notice.getProviderId());
            statement.setObject(5, notice.getConsumerId());
            statement.executeUpdate();
        }
    }

    /** Fails unless the refusal is one the API renders as a client error the caller can act on. */
    private static void isClientError(Throwable refusal) {
        assertThat(refusal).isInstanceOf(ApiException.class);
        assertThat(((ApiException) refusal).problemType().status().getCode())
                .isLessThan(ProblemType.LOWEST_SERVER_ERROR_STATUS);
    }

    /** Fails if any consent of the pair records the same state twice, which no move may do. */
    private void assertNoConsentReachedAStateTwice() {
        consents.findByPrivacyNoticeId(notice.getId())
                .forEach(
                        consent ->
                                assertThat(
                                                events
                                                        .findByConsentIdOrderByOccurredAtAsc(
                                                                consent.getId())
                                                        .stream()
                                                        .map(ConsentEvent::getEventState)
                                                        .toList())
                                        .as(
                                                "no consent is recorded as having reached a state twice")
                                        .doesNotHaveDuplicates());
    }

    /** Runs the two moves so that neither starts before the other, separating win from refusal. */
    private Outcomes race(Callable<UUID> first, Callable<UUID> second) throws Exception {
        CyclicBarrier start = new CyclicBarrier(RACERS);
        List<Callable<UUID>> racers = List.of(started(start, first), started(start, second));
        try (ExecutorService pool = Executors.newFixedThreadPool(RACERS)) {
            List<Future<UUID>> futures = pool.invokeAll(racers);
            List<UUID> moved = new ArrayList<>(RACERS);
            List<Throwable> refused = new ArrayList<>(RACERS);
            for (Future<UUID> future : futures) {
                try {
                    moved.add(future.get());
                } catch (ExecutionException e) {
                    refused.add(e.getCause());
                }
            }
            return new Outcomes(moved, refused);
        }
    }

    private static Callable<UUID> started(CyclicBarrier start, Callable<UUID> move) {
        return () -> {
            start.await();
            return move.call();
        };
    }

    private UUID revoke(UUID consentId) {
        return lifecycle.revoke(subject, consentId).consent().getId();
    }

    private UUID resume(UUID consentId) {
        return lifecycle.resume(principalFor(ISSUER, subject), consentId, null).consent().getId();
    }

    private UUID grant() {
        return consentService
                .decide(
                        subject,
                        new ConsentDecision(notice.getId(), ConsentStatus.GRANTED, null, null))
                .consent()
                .getId();
    }

    private List<Consent> granted() {
        return consents.findByUserIdAndStatus(subject.getId(), ConsentStatus.GRANTED);
    }

    private void seedPair() {
        subject = users.save(new User("urn:test:user:lifecycle-atomicity", null, null, null));
        notice =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI,
                                null,
                                participant(participants, PROVIDER).getId(),
                                participant(participants, CONSUMER).getId(),
                                PrivacyNoticeFixtures.emptyPayload()));
    }

    /**
     * A consent for the pair as an earlier flow would have left it, with the trail it would hold.
     */
    private UUID seedConsent(ConsentStatus status) {
        Consent seeded =
                consents.save(
                        Consent.withStatus(
                                status,
                                subject.getId(),
                                notice.getId(),
                                notice.getProviderId(),
                                notice.getConsumerId(),
                                null,
                                CONTRACT_URI,
                                noTerms()));
        events.save(
                new ConsentEvent(
                        seeded.getId(),
                        status == ConsentStatus.GRANTED
                                ? ConsentEventState.CONSENT_GIVEN
                                : ConsentEventState.CONSENT_REQUESTED,
                        PROVIDER,
                        null));
        return seeded.getId();
    }
}
