package com.seamware.consentmanager.service;

import static com.seamware.consentmanager.support.ConsentFixtures.noTerms;
import static com.seamware.consentmanager.support.ConsentFixtures.participant;
import static com.seamware.consentmanager.support.ConsentFixtures.principalFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEventState;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.error.BadRequestException;
import com.seamware.consentmanager.repository.ConsentEventRepository;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.support.PostgresTestResource;
import com.seamware.consentmanager.support.PrivacyNoticeFixtures;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
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
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every lifecycle move from every status lands where {@link ConsentStateMachine} says, writing
 * nothing when refused. {@code DRAFT} and {@code EXPIRED} are seeded straight through the
 * repository because no service produces them.
 */
@MicronautTest(transactional = false)
@DisplayName("Consent lifecycle matrix")
class ConsentLifecycleMatrixIT extends PostgresTestResource {

    private static final String PROVIDER = "urn:test:participant:consent-matrix-provider";

    private static final String CONSUMER = "urn:test:participant:consent-matrix-consumer";

    private static final String CONTRACT_URI = "urn:test:contract:consent-matrix";

    private static final String NOTICE_TITLE = "Notice the matrix moves act on";

    /** What {@code consent_events.event_type} reads for a move an identified caller asked for. */
    private static final String EXPLICIT_EVENT_TYPE = "explicit";

    /** Issuer of a token this suite never validates; only its shape matters. */
    private static final String ISSUER = "https://issuer.test/realms/consent-matrix";

    private static final List<ConsentStatus> ALL_STATUSES = List.of(ConsentStatus.values());

    @Inject ConsentLifecycleService lifecycle;

    @Inject ConsentRepository consents;

    @Inject ConsentEventRepository events;

    @Inject PrivacyNoticeRepository notices;

    @Inject ParticipantRepository participants;

    @Inject UserRepository users;

    private final List<User> seededUsers = new ArrayList<>();

    private PrivacyNotice notice;

    /** Every (move, starting status) pair the table permits. */
    static Stream<Arguments> permittedCells() {
        return cells(operation -> operation.permittedFrom);
    }

    /** Every (move, starting status) pair the table does not permit. */
    static Stream<Arguments> refusedCells() {
        return cells(operation -> EnumSet.complementOf(EnumSet.copyOf(operation.permittedFrom)));
    }

    private static Stream<Arguments> cells(Function<Operation, Set<ConsentStatus>> starts) {
        return Arrays.stream(Operation.values())
                .flatMap(
                        operation ->
                                starts.apply(operation).stream()
                                        .map(from -> Arguments.of(operation, from)));
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
    }

    @AfterAll
    void removeFixtures() {
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
    @DisplayName("every move the service offers has a row in this table")
    void coversEveryMoveTheServiceOffers() {
        assertThat(
                        Arrays.stream(ConsentLifecycleService.class.getDeclaredMethods())
                                .filter(method -> Modifier.isPublic(method.getModifiers()))
                                .filter(method -> !method.isSynthetic())
                                .map(Method::getName)
                                .distinct())
                .as("a move added to the service without a row here would go unexercised")
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(Operation.values()).map(Operation::method).toList());
    }

    @ParameterizedTest(name = "{0} from {1}")
    @MethodSource("permittedCells")
    @DisplayName(
            "a permitted cell leaves the status the table names and one event naming who acted")
    void storesThePermittedCell(Operation operation, ConsentStatus from) {
        User subject = user();
        Participant party = participant(participants, PROVIDER);
        UUID consentId = seedConsent(subject, from);

        ConsentDetails moved = operation.apply(lifecycle, subject, party, consentId);

        assertThat(moved.consent().getStatus()).isEqualTo(operation.leaves);
        assertThat(consents.findById(consentId).orElseThrow())
                .satisfies(
                        stored -> {
                            assertThat(stored.getStatus()).isEqualTo(operation.leaves);
                            assertThat(stored.isConsented())
                                    .as("the flag follows the status the move left behind")
                                    .isEqualTo(operation.leaves.isConsented());
                        });
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId))
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.getEventState()).isEqualTo(operation.state);
                            assertThat(event.getEventType()).isEqualTo(EXPLICIT_EVENT_TYPE);
                            assertThat(event.getActor()).isEqualTo(operation.actor(subject, party));
                        });
    }

    @ParameterizedTest(name = "{0} from {1}")
    @MethodSource("refusedCells")
    @DisplayName("a refused cell names both statuses and writes neither status nor event")
    void refusesTheCellTheTableWithholds(Operation operation, ConsentStatus from) {
        User subject = user();
        Participant party = participant(participants, PROVIDER);
        UUID consentId = seedConsent(subject, from);

        assertThatThrownBy(() -> operation.apply(lifecycle, subject, party, consentId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(from.name())
                .hasMessageContaining(operation.leaves.name());

        assertThat(consents.findById(consentId).orElseThrow().getStatus()).isEqualTo(from);
        assertThat(events.findByConsentIdOrderByOccurredAtAsc(consentId))
                .as("a refused move writes nothing, not even the event")
                .isEmpty();
    }

    /** A consent for the pair as some other flow would have left it, with no trail of its own. */
    private UUID seedConsent(User subject, ConsentStatus status) {
        PrivacyNotice against = notice();
        return consents.save(
                        Consent.withStatus(
                                status,
                                subject.getId(),
                                against.getId(),
                                against.getProviderId(),
                                against.getConsumerId(),
                                null,
                                CONTRACT_URI,
                                noTerms()))
                .getId();
    }

    private User user() {
        User subject =
                users.save(
                        new User(
                                "urn:test:user:consent-matrix:" + UUID.randomUUID(),
                                null,
                                null,
                                null));
        seededUsers.add(subject);
        return subject;
    }

    /**
     * The one notice every cell acts against, shared because {@code
     * uq_privacy_notices_active_contract} admits one active notice per (contract, provider).
     */
    private PrivacyNotice notice() {
        if (notice == null) {
            notice =
                    notices.save(
                            new PrivacyNotice(
                                    CONTRACT_URI,
                                    NOTICE_TITLE,
                                    participant(participants, PROVIDER).getId(),
                                    participant(participants, CONSUMER).getId(),
                                    PrivacyNoticeFixtures.emptyPayload()));
        }
        return notice;
    }

    /**
     * Every lifecycle move with the status it leaves, the event it appends and where it is
     * permitted from, as read off {@link ConsentStateMachine}. {@code leaves} is the end status,
     * not a transition target, so the no-op {@code RE_CONFIRM} fits the same table.
     */
    private enum Operation {
        REVOKE(
                "revoke",
                ConsentStatus.REVOKED,
                ConsentEventState.CONSENT_REVOKED,
                EnumSet.of(ConsentStatus.GRANTED)),
        REFUSE(
                "refuse",
                ConsentStatus.REFUSED,
                ConsentEventState.CONSENT_REFUSED,
                EnumSet.of(ConsentStatus.PENDING, ConsentStatus.DRAFT)),
        TERMINATE(
                "terminate",
                ConsentStatus.TERMINATED,
                ConsentEventState.CONSENT_TERMINATED,
                EnumSet.of(ConsentStatus.GRANTED, ConsentStatus.PENDING, ConsentStatus.DRAFT)),
        RE_CONFIRM(
                "reConfirm",
                ConsentStatus.GRANTED,
                ConsentEventState.CONSENT_RE_CONFIRMED,
                EnumSet.of(ConsentStatus.GRANTED)),
        RESUME(
                "resume",
                ConsentStatus.GRANTED,
                ConsentEventState.CONSENT_RESUMED,
                EnumSet.of(ConsentStatus.PENDING, ConsentStatus.DRAFT));

        private final String method;
        private final ConsentStatus leaves;
        private final ConsentEventState state;
        private final Set<ConsentStatus> permittedFrom;

        Operation(
                String method,
                ConsentStatus leaves,
                ConsentEventState state,
                Set<ConsentStatus> permittedFrom) {
            this.method = method;
            this.leaves = leaves;
            this.state = state;
            this.permittedFrom = permittedFrom;
        }

        /** The service method this row drives. */
        String method() {
            return method;
        }

        ConsentDetails apply(
                ConsentLifecycleService lifecycle,
                User subject,
                Participant party,
                UUID consentId) {
            return switch (this) {
                case REVOKE -> lifecycle.revoke(subject, consentId);
                case REFUSE -> lifecycle.refuse(subject, consentId);
                case TERMINATE -> lifecycle.terminate(party, consentId);
                case RE_CONFIRM -> lifecycle.reConfirm(subject, consentId);
                case RESUME -> lifecycle.resume(principalFor(ISSUER, subject), consentId, null);
            };
        }

        /** The identifier the appended event records as actor. */
        String actor(User subject, Participant party) {
            return switch (this) {
                case REVOKE, REFUSE, RE_CONFIRM, RESUME -> subject.getIdentifier();
                case TERMINATE -> party.getIdentifier();
            };
        }
    }
}
