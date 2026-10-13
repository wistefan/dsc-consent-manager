package com.seamware.consentmanager.service;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.error.BadRequestException;
import com.seamware.consentmanager.error.ForbiddenException;
import com.seamware.consentmanager.error.NotFoundException;
import com.seamware.consentmanager.repository.ConsentEventRepository;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.support.PostgresTestResource;
import com.seamware.consentmanager.support.PrivacyNoticeFixtures;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves what the lifecycle moves - the subject's revoke and refuse, and a party's terminate - are
 * checked against and what they leave behind: the status the record ends in, the single event
 * naming who ended it, and the statuses each move is refused from.
 *
 * <p>Every assertion reads the database back rather than the returned projection where the two
 * could disagree, because what matters is what was committed.
 */
@MicronautTest(transactional = false)
@DisplayName("Consent lifecycle moves")
class ConsentLifecycleIT extends PostgresTestResource {

    private static final String PROVIDER = "urn:test:participant:consent-lifecycle-provider";

    private static final String CONSUMER = "urn:test:participant:consent-lifecycle-consumer";

    /** A participant the seeded consents name on neither side, so it has no standing to end one. */
    private static final String STRANGER = "urn:test:participant:consent-lifecycle-stranger";

    private static final String CONTRACT_URI = "urn:test:contract:consent-lifecycle";

    private static final String NOTICE_TITLE = "Notice the lifecycle moves act on";

    /** What {@code consent_events.event_type} reads for a move an identified caller asked for. */
    private static final String EXPLICIT_EVENT_TYPE = "explicit";

    private static final List<ConsentStatus> ALL_STATUSES = List.of(ConsentStatus.values());

    /**
     * The statuses a party may terminate from.
     *
     * <p>Not {@code GRANTED} alone: the table's entry for both unanswered statuses contains {@code
     * TERMINATED}, because erasure and deregistration already close open offers through that edge,
     * so a party may equally end a request the subject has not answered.
     */
    private static final Set<ConsentStatus> TERMINABLE =
            EnumSet.of(ConsentStatus.GRANTED, ConsentStatus.PENDING, ConsentStatus.DRAFT);

    @Inject ConsentLifecycleService lifecycle;

    @Inject ConsentRepository consents;

    @Inject ConsentEventRepository events;

    @Inject PrivacyNoticeRepository notices;

    @Inject ParticipantRepository participants;

    @Inject UserRepository users;

    private final List<User> seededUsers = new ArrayList<>();

    private final List<UUID> seededNotices = new ArrayList<>();

    /** Every (move, starting status) pair the state table permits. */
    static Stream<Arguments> permittedMoves() {
        return pairs(move -> move.permittedFrom);
    }

    /** Every (move, starting status) pair the state table does not permit. */
    static Stream<Arguments> refusedMoves() {
        return pairs(move -> EnumSet.complementOf(EnumSet.copyOf(move.permittedFrom)));
    }

    /** Each party to a consent, paired with every status termination is permitted from. */
    static Stream<Arguments> terminations() {
        return Stream.of(PROVIDER, CONSUMER)
                .flatMap(party -> TERMINABLE.stream().map(from -> Arguments.of(party, from)));
    }

    /** The statuses termination is refused from: exactly the terminal ones. */
    static Stream<ConsentStatus> terminalStatuses() {
        return EnumSet.complementOf(EnumSet.copyOf(TERMINABLE)).stream();
    }

    private static Stream<Arguments> pairs(Function<Move, Set<ConsentStatus>> starts) {
        return Arrays.stream(Move.values())
                .flatMap(move -> starts.apply(move).stream().map(from -> Arguments.of(move, from)));
    }

    /** Rows these cases commit outlive their transaction, and a notice holds its parties. */
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
        List.of(PROVIDER, CONSUMER, STRANGER)
                .forEach(
                        identifier ->
                                participants
                                        .findByIdentifier(identifier)
                                        .ifPresent(participants::delete));
    }

    @ParameterizedTest(name = "{0} from {1}")
    @MethodSource("permittedMoves")
    @DisplayName("a permitted move stores the new status and the one event naming who made it")
    void storesThePermittedMoveWithItsTrail(Move move, ConsentStatus from) {
        User subject = user();
        UUID consentId = seedConsent(subject, notice(), from);

        ConsentDetails moved = move.apply(lifecycle, subject, consentId);

        assertThat(moved.consent().getStatus()).isEqualTo(move.target);
        assertThat(moved.created())
                .as("a lifecycle move writes a record that was already there")
                .isFalse();
        assertThat(moved.providerIdentifier()).isEqualTo(PROVIDER);
        assertThat(moved.consumerIdentifier()).isEqualTo(CONSUMER);
        assertThat(consents.findById(consentId).orElseThrow())
                .satisfies(
                        stored -> {
                            assertThat(stored.getStatus()).isEqualTo(move.target);
                            assertThat(stored.isConsented())
                                    .as("nothing is consented to once the move has landed")
                                    .isFalse();
                        });
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId))
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.getEventState()).isEqualTo(move.state);
                            assertThat(event.getEventType()).isEqualTo(EXPLICIT_EVENT_TYPE);
                            assertThat(event.getActor())
                                    .as("the trail names the subject who acted")
                                    .isEqualTo(subject.getIdentifier());
                        });
    }

    @ParameterizedTest(name = "{0} from {1}")
    @MethodSource("refusedMoves")
    @DisplayName("a move the table does not permit writes nothing and names both statuses")
    void refusesAMoveTheTableDoesNotPermit(Move move, ConsentStatus from) {
        User subject = user();
        UUID consentId = seedConsent(subject, notice(), from);

        assertThatThrownBy(() -> move.apply(lifecycle, subject, consentId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(from.name())
                .hasMessageContaining(move.target.name());

        assertThat(consents.findById(consentId).orElseThrow().getStatus()).isEqualTo(from);
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId))
                .as("a refused move leaves no trace of having been attempted")
                .isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Move.class)
    @DisplayName("a consent belonging to another subject is the 404 an unknown one is")
    void refusesAConsentBelongingToAnotherSubject(Move move) {
        User subject = user();
        User other = user();
        UUID theirs = seedConsent(other, notice(), ConsentStatus.GRANTED);

        assertThatThrownBy(() -> move.apply(lifecycle, subject, theirs))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(theirs.toString());

        assertThat(consents.findById(theirs).orElseThrow().getStatus())
                .as("the other subject's record is untouched by the probe")
                .isEqualTo(ConsentStatus.GRANTED);
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(theirs)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Move.class)
    @DisplayName("a consent nothing stores is a 404")
    void refusesAnUnknownConsent(Move move) {
        User subject = user();
        UUID absent = UUID.randomUUID();

        assertThatThrownBy(() -> move.apply(lifecycle, subject, absent))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(absent.toString());
    }

    @ParameterizedTest(name = "{0} terminates from {1}")
    @MethodSource("terminations")
    @DisplayName("either party terminates the consent and the trail names the one that did")
    void terminatesAsEitherParty(String party, ConsentStatus from) {
        User subject = user();
        UUID consentId = seedConsent(subject, notice(), from);

        ConsentDetails terminated = lifecycle.terminate(participant(party), consentId);

        assertThat(terminated.consent().getStatus()).isEqualTo(ConsentStatus.TERMINATED);
        assertThat(consents.findById(consentId).orElseThrow())
                .satisfies(
                        stored -> {
                            assertThat(stored.getStatus()).isEqualTo(ConsentStatus.TERMINATED);
                            assertThat(stored.isConsented()).isFalse();
                        });
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId))
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.getEventState())
                                    .isEqualTo(ConsentEventState.CONSENT_TERMINATED);
                            assertThat(event.getEventType()).isEqualTo(EXPLICIT_EVENT_TYPE);
                            assertThat(event.getActor())
                                    .as("the trail names the participant that ended it")
                                    .isEqualTo(party);
                        });
    }

    @ParameterizedTest(name = "from {0}")
    @MethodSource("terminalStatuses")
    @DisplayName("termination from a terminal status writes nothing and names both statuses")
    void refusesTerminationFromATerminalStatus(ConsentStatus from) {
        UUID consentId = seedConsent(user(), notice(), from);
        Participant party = participant(PROVIDER);

        assertThatThrownBy(() -> lifecycle.terminate(party, consentId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(from.name())
                .hasMessageContaining(ConsentStatus.TERMINATED.name());

        assertThat(consents.findById(consentId).orElseThrow().getStatus()).isEqualTo(from);
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId)).isEmpty();
    }

    @Test
    @DisplayName(
            "a participant the consent names on neither side is refused, not told it is absent")
    void refusesAParticipantThatIsNoParty() {
        UUID consentId = seedConsent(user(), notice(), ConsentStatus.GRANTED);
        Participant stranger = participant(STRANGER);

        assertThatThrownBy(() -> lifecycle.terminate(stranger, consentId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining(STRANGER)
                .hasMessageContaining(consentId.toString());

        assertThat(consents.findById(consentId).orElseThrow().getStatus())
                .isEqualTo(ConsentStatus.GRANTED);
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId)).isEmpty();
    }

    @Test
    @DisplayName("a consent nothing stores is a 404 before the caller's standing is weighed")
    void refusesTerminatingAnUnknownConsent() {
        UUID absent = UUID.randomUUID();
        Participant stranger = participant(STRANGER);

        assertThatThrownBy(() -> lifecycle.terminate(stranger, absent))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(absent.toString());
    }

    /** A consent for the pair as some other flow would have left it, with no trail of its own. */
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
                users.save(
                        new User(
                                "urn:test:user:consent-lifecycle:" + UUID.randomUUID(),
                                null,
                                null,
                                null));
        seededUsers.add(subject);
        return subject;
    }

    private PrivacyNotice notice() {
        PrivacyNotice notice =
                notices.save(
                        new PrivacyNotice(
                                CONTRACT_URI,
                                NOTICE_TITLE,
                                participant(PROVIDER).getId(),
                                participant(CONSUMER).getId(),
                                PrivacyNoticeFixtures.emptyPayload()));
        seededNotices.add(notice.getId());
        return notice;
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

    /** The two moves a subject makes, each with what it is permitted from and what it records. */
    private enum Move {
        REVOKE(
                ConsentStatus.REVOKED,
                ConsentEventState.CONSENT_REVOKED,
                EnumSet.of(ConsentStatus.GRANTED)),
        REFUSE(
                ConsentStatus.REFUSED,
                ConsentEventState.CONSENT_REFUSED,
                EnumSet.of(ConsentStatus.PENDING, ConsentStatus.DRAFT));

        private final ConsentStatus target;
        private final ConsentEventState state;
        private final Set<ConsentStatus> permittedFrom;

        Move(ConsentStatus target, ConsentEventState state, Set<ConsentStatus> permittedFrom) {
            this.target = target;
            this.state = state;
            this.permittedFrom = permittedFrom;
        }

        ConsentDetails apply(ConsentLifecycleService lifecycle, User subject, UUID consentId) {
            return switch (this) {
                case REVOKE -> lifecycle.revoke(subject, consentId);
                case REFUSE -> lifecycle.refuse(subject, consentId);
            };
        }
    }
}
