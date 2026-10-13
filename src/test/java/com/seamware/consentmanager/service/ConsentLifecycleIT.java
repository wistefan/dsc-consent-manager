package com.seamware.consentmanager.service;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentSnapshot;
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
import com.seamware.consentmanager.security.CatalogPrincipal;
import com.seamware.consentmanager.security.ParticipantPrincipal;
import com.seamware.consentmanager.security.UserPrincipal;
import com.seamware.consentmanager.support.PostgresTestResource;
import com.seamware.consentmanager.support.PrivacyNoticeFixtures;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Instant;
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
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Proves what the lifecycle moves - the subject's revoke, refuse and resume, a party's terminate,
 * and the subject's re-confirmation, which moves nothing - are checked against and what they leave
 * behind: the status the record ends in, the single event naming who acted, and the statuses each
 * is refused from.
 *
 * <p>Resume carries more than the others, because it is the second route into {@code GRANTED}: it
 * is reachable by a catalog relaying the subject's decision, it answers the notice guard {@code
 * POST /consents} answers, and it supersedes the grant in force for the pair.
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

    /** A notice archived since the request was opened, which no grant may be recorded against. */
    private static final String ARCHIVED_CONTRACT_URI = "urn:test:contract:consent-lifecycle-gone";

    /** A notice predating the invariant that a notice names a consumer. */
    private static final String UNNAMED_CONSUMER_CONTRACT_URI =
            "urn:test:contract:consent-lifecycle-unnamed";

    private static final String NOTICE_TITLE = "Notice the lifecycle moves act on";

    /** The one elected term a resumed request carries, so keeping it can be asserted. */
    private static final String SEEDED_PURPOSE = "research the request was opened for";

    /** What {@code consent_events.event_type} reads for a move an identified caller asked for. */
    private static final String EXPLICIT_EVENT_TYPE = "explicit";

    /** The provider of a token this suite never validates; only its shape matters here. */
    private static final String ISSUER = "https://issuer.test/realms/consent-lifecycle";

    private static final String CATALOG_SUBJECT = "catalog-service";

    /** How a relaying catalog names itself, which is the one thing a relayed grant reads by. */
    private static final String CATALOG_ACTOR = "catalog:" + ISSUER + ":" + CATALOG_SUBJECT;

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
                                    .as("the flag follows the status the move landed in")
                                    .isEqualTo(move.target.isConsented());
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

    @Test
    @DisplayName("re-confirmation appends one event and leaves the record it affirms alone")
    void reConfirmsWithoutTouchingTheRecord() {
        User subject = user();
        UUID consentId = seedConsent(subject, notice(), ConsentStatus.GRANTED);
        Instant seededAt = consents.findById(consentId).orElseThrow().getUpdatedAt();

        ConsentDetails affirmed = lifecycle.reConfirm(subject, consentId);

        assertThat(affirmed.consent().getStatus()).isEqualTo(ConsentStatus.GRANTED);
        assertThat(affirmed.created()).isFalse();
        assertThat(consents.findById(consentId).orElseThrow())
                .satisfies(
                        stored -> {
                            assertThat(stored.getStatus()).isEqualTo(ConsentStatus.GRANTED);
                            assertThat(stored.isConsented()).isTrue();
                            assertThat(stored.getUpdatedAt())
                                    .as("updatedAt dates state changes, and this is not one")
                                    .isEqualTo(seededAt);
                        });
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId))
                .as("the affirmation shows only in the trail, dated in its own right")
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.getEventState())
                                    .isEqualTo(ConsentEventState.CONSENT_RE_CONFIRMED);
                            assertThat(event.getEventType()).isEqualTo(EXPLICIT_EVENT_TYPE);
                            assertThat(event.getActor()).isEqualTo(subject.getIdentifier());
                        });
    }

    @ParameterizedTest(name = "from {0}")
    @EnumSource(value = ConsentStatus.class, names = "GRANTED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("a consent not in force cannot be re-confirmed and records no attempt")
    void refusesReConfirmingAConsentNotInForce(ConsentStatus from) {
        User subject = user();
        UUID consentId = seedConsent(subject, notice(), from);

        assertThatThrownBy(() -> lifecycle.reConfirm(subject, consentId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(from.name())
                .hasMessageContaining(ConsentStatus.GRANTED.name());

        assertThat(consents.findById(consentId).orElseThrow().getStatus()).isEqualTo(from);
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId)).isEmpty();
    }

    @Test
    @DisplayName("re-confirming another subject's consent is the 404 an unknown one is")
    void refusesReConfirmingAnotherSubjectsConsent() {
        User subject = user();
        UUID theirs = seedConsent(user(), notice(), ConsentStatus.GRANTED);

        assertThatThrownBy(() -> lifecycle.reConfirm(subject, theirs))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(theirs.toString());

        assertThat(events.findByConsentIdOrderByOccurredAtAsc(theirs))
                .as("the other subject's trail is untouched by the probe")
                .isEmpty();
    }

    @Test
    @DisplayName("resuming revokes the grant in force and names it as what the decision replaced")
    void supersedesTheGrantInForce() {
        User subject = user();
        PrivacyNotice notice = notice();
        UUID inForce = seedConsent(subject, notice, ConsentStatus.GRANTED);
        UUID requested = seedConsent(subject, notice, ConsentStatus.PENDING);

        lifecycle.resume(principalFor(subject), requested, null);

        assertThat(consents.findById(requested).orElseThrow())
                .satisfies(
                        stored -> {
                            assertThat(stored.getStatus()).isEqualTo(ConsentStatus.GRANTED);
                            assertThat(stored.getParentConsentId())
                                    .as("the resumed record names the grant its decision closed")
                                    .isEqualTo(inForce);
                        });
        assertThat(consents.findById(inForce).orElseThrow())
                .satisfies(
                        stored -> {
                            assertThat(stored.getStatus()).isEqualTo(ConsentStatus.REVOKED);
                            assertThat(stored.isConsented()).isFalse();
                        });
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(inForce))
                .as("the closing shows in the closed record's own trail")
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.getEventState())
                                    .isEqualTo(ConsentEventState.CONSENT_REVOKED);
                            assertThat(event.getActor()).isEqualTo(subject.getIdentifier());
                        });
    }

    @Test
    @DisplayName("resuming with nothing in force leaves the parent the request was opened with")
    void keepsTheParentWhereNothingWasInForce() {
        User subject = user();
        PrivacyNotice notice = notice();
        UUID closed = seedConsent(subject, notice, ConsentStatus.REFUSED);
        UUID requested = seedConsent(subject, notice, ConsentStatus.PENDING, closed, noTerms());

        lifecycle.resume(principalFor(subject), requested, null);

        assertThat(consents.findById(requested).orElseThrow().getParentConsentId())
                .as("the link records what a decision closed, so nothing closed writes nothing")
                .isEqualTo(closed);
        assertThat(consents.findById(closed).orElseThrow().getStatus())
                .as("a record already closed is not closed again")
                .isEqualTo(ConsentStatus.REFUSED);
    }

    @Test
    @DisplayName("a relayed resume grants the consent and the trail names the relaying catalog")
    void relayedResumeNamesTheCatalog() {
        User subject = user();
        UUID consentId = seedConsent(subject, notice(), ConsentStatus.PENDING);

        ConsentDetails resumed =
                lifecycle.resume(
                        new CatalogPrincipal(ISSUER, CATALOG_SUBJECT),
                        consentId,
                        subject.getIdentifier());

        assertThat(resumed.consent().getStatus()).isEqualTo(ConsentStatus.GRANTED);
        assertThat(consents.findById(consentId).orElseThrow().isConsented()).isTrue();
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId))
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.getEventState())
                                    .isEqualTo(ConsentEventState.CONSENT_RESUMED);
                            assertThat(event.getEventType()).isEqualTo(EXPLICIT_EVENT_TYPE);
                            assertThat(event.getActor())
                                    .as("a relayed grant never reads as one the subject made here")
                                    .isEqualTo(CATALOG_ACTOR);
                        });
    }

    @ParameterizedTest(name = "userIdentifier [{0}]")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("a relay naming no subject is a 400 and writes nothing")
    void refusesARelayNamingNoSubject(String userIdentifier) {
        User subject = user();
        UUID consentId = seedConsent(subject, notice(), ConsentStatus.PENDING);

        assertThatThrownBy(
                        () ->
                                lifecycle.resume(
                                        new CatalogPrincipal(ISSUER, CATALOG_SUBJECT),
                                        consentId,
                                        userIdentifier))
                .isInstanceOf(BadRequestException.class);

        assertThat(consents.findById(consentId).orElseThrow().getStatus())
                .isEqualTo(ConsentStatus.PENDING);
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId)).isEmpty();
    }

    @Test
    @DisplayName("a relay naming anybody but the consent's subject is the 404 an unknown one is")
    void refusesARelayNamingAnotherSubject() {
        UUID consentId = seedConsent(user(), notice(), ConsentStatus.PENDING);
        User stranger = user();

        assertThatThrownBy(
                        () ->
                                lifecycle.resume(
                                        new CatalogPrincipal(ISSUER, CATALOG_SUBJECT),
                                        consentId,
                                        stranger.getIdentifier()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(consentId.toString());

        assertThat(consents.findById(consentId).orElseThrow().getStatus())
                .isEqualTo(ConsentStatus.PENDING);
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId)).isEmpty();
    }

    @Test
    @DisplayName("resuming another subject's consent is the 404 an unknown one is")
    void refusesResumingAnotherSubjectsConsent() {
        User subject = user();
        UUID theirs = seedConsent(user(), notice(), ConsentStatus.PENDING);

        assertThatThrownBy(() -> lifecycle.resume(principalFor(subject), theirs, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(theirs.toString());

        assertThat(events.findByConsentIdOrderByOccurredAtAsc(theirs)).isEmpty();
    }

    @Test
    @DisplayName("a participant records no decision, which the route's roles already refuse")
    void refusesAParticipantResuming() {
        User subject = user();
        UUID consentId = seedConsent(subject, notice(), ConsentStatus.PENDING);
        Participant party = participant(PROVIDER);

        assertThatThrownBy(
                        () ->
                                lifecycle.resume(
                                        new ParticipantPrincipal(ISSUER, PROVIDER, PROVIDER, party),
                                        consentId,
                                        subject.getIdentifier()))
                .isInstanceOf(ForbiddenException.class);

        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId)).isEmpty();
    }

    @ParameterizedTest(name = "a notice that {0}")
    @EnumSource(Unconsentable.class)
    @DisplayName("a request outliving the terms it was opened against is a 400 and writes nothing")
    void refusesResumingAgainstANoticeNoGrantMayName(Unconsentable kind) {
        User subject = user();
        PrivacyNotice notice = unconsentable(kind);
        UUID consentId = seedConsent(subject, notice, ConsentStatus.PENDING);

        assertThatThrownBy(() -> lifecycle.resume(principalFor(subject), consentId, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(notice.getId().toString());

        assertThat(consents.findById(consentId).orElseThrow().getStatus())
                .isEqualTo(ConsentStatus.PENDING);
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId)).isEmpty();
    }

    @Test
    @DisplayName("resuming keeps the terms the request was opened with")
    void keepsTheTermsTheRequestWasOpenedWith() {
        User subject = user();
        ConsentSnapshot opened =
                new ConsentSnapshot(
                        NOTICE_TITLE,
                        List.of(SEEDED_PURPOSE),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        UUID consentId = seedConsent(subject, notice(), ConsentStatus.PENDING, null, opened);

        lifecycle.resume(principalFor(subject), consentId, null);

        assertThat(consents.findById(consentId).orElseThrow().getSnapshot())
                .as("a resume records a decision taken elsewhere rather than electing scope here")
                .isEqualTo(opened);
    }

    /** The two ways a notice stops being one a grant may be recorded against. */
    private enum Unconsentable {
        HAS_BEEN_ARCHIVED,
        NAMES_NO_CONSUMER
    }

    /** A notice the guard refuses, on its own contract so the active-notice index stays free. */
    private PrivacyNotice unconsentable(Unconsentable kind) {
        PrivacyNotice notice =
                new PrivacyNotice(
                        switch (kind) {
                            case HAS_BEEN_ARCHIVED -> ARCHIVED_CONTRACT_URI;
                            case NAMES_NO_CONSUMER -> UNNAMED_CONSUMER_CONTRACT_URI;
                        },
                        NOTICE_TITLE,
                        participant(PROVIDER).getId(),
                        kind == Unconsentable.NAMES_NO_CONSUMER
                                ? null
                                : participant(CONSUMER).getId(),
                        PrivacyNoticeFixtures.emptyPayload());
        if (kind == Unconsentable.HAS_BEEN_ARCHIVED) {
            notice.setArchivedAt(Instant.now());
        }
        PrivacyNotice saved = notices.save(notice);
        seededNotices.add(saved.getId());
        return saved;
    }

    /** The subject's own token, as the resolution filter would have handed it to a handler. */
    private static UserPrincipal principalFor(User subject) {
        return new UserPrincipal(
                ISSUER,
                subject.getIdentifier(),
                subject.getIdentifier(),
                null,
                false,
                null,
                null,
                null,
                subject,
                false);
    }

    /** A consent for the pair as some other flow would have left it, with no trail of its own. */
    private UUID seedConsent(User subject, PrivacyNotice notice, ConsentStatus status) {
        return seedConsent(subject, notice, status, null, noTerms());
    }

    /**
     * The same, where the case turns on what the record carries beyond its status.
     *
     * <p>The row names a consumer even where its notice has stopped naming one: {@code
     * consents.consumer_id} is {@code NOT NULL}, and the guard under test reads the notice rather
     * than the row.
     */
    private UUID seedConsent(
            User subject,
            PrivacyNotice notice,
            ConsentStatus status,
            UUID parentConsentId,
            ConsentSnapshot snapshot) {
        UUID consumerId =
                notice.getConsumerId() == null
                        ? participant(CONSUMER).getId()
                        : notice.getConsumerId();
        return consents.save(
                        Consent.withStatus(
                                status,
                                subject.getId(),
                                notice.getId(),
                                notice.getProviderId(),
                                consumerId,
                                parentConsentId,
                                CONTRACT_URI,
                                snapshot))
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

    /**
     * The moves a subject makes, each with what it is permitted from and what it records.
     *
     * <p>{@code RESUME} is here as the subject's own, which is one of the two ways it is reached;
     * the relayed way has its own cases, because only the actor and who may call differ.
     */
    private enum Move {
        REVOKE(
                ConsentStatus.REVOKED,
                ConsentEventState.CONSENT_REVOKED,
                EnumSet.of(ConsentStatus.GRANTED)),
        REFUSE(
                ConsentStatus.REFUSED,
                ConsentEventState.CONSENT_REFUSED,
                EnumSet.of(ConsentStatus.PENDING, ConsentStatus.DRAFT)),
        RESUME(
                ConsentStatus.GRANTED,
                ConsentEventState.CONSENT_RESUMED,
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
                case RESUME -> lifecycle.resume(principalFor(subject), consentId, null);
            };
        }
    }
}
