package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.ConsentSnapshot;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.error.BadRequestException;
import com.seamware.consentmanager.error.ConflictException;
import com.seamware.consentmanager.error.ForbiddenException;
import com.seamware.consentmanager.error.NotFoundException;
import com.seamware.consentmanager.notification.UserNotificationService;
import com.seamware.consentmanager.observability.FailureSummary;
import com.seamware.consentmanager.repository.UserParticipantRepository;
import com.seamware.consentmanager.repository.UserRepository;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.exceptions.DataAccessException;
import jakarta.inject.Singleton;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The two ways a consent comes into being: the subject's own decision, and a participant's request.
 *
 * <p>Deliberately not transactional: every transactional write is in {@link ConsentWriter}, so a
 * race lost on the one-grant-per-notice index is caught here, outside the transaction, as 409.
 */
@Singleton
public class ConsentService {

    private static final Logger LOG = LoggerFactory.getLogger(ConsentService.class);

    private static final String NOTIFICATION_FAILED =
            "Consent {} was requested, but notifying its subject failed: {}";

    private static final String NOTHING_TO_NARROW =
            "Privacy notice '%s' declares no %s, so none can be elected.";
    private static final String EMPTY_ELECTION =
            "At least one of the notice's %s must be elected; electing none is not a narrowing."
                    + " Omit the field to consent to all of them.";
    private static final String OUTSIDE_SCOPE =
            "The %s %s are not declared by privacy notice '%s'.";
    private static final String CONCURRENT_GRANT =
            "Another decision for privacy notice '%s' was recorded concurrently. Re-read the"
                    + " consent before deciding again.";

    private static final String NOT_A_PARTY =
            "Participant '%s' is named by privacy notice '%s' as neither provider nor consumer, so"
                    + " it cannot ask for consent against it.";
    private static final String UNKNOWN_SUBJECT =
            "No user '%s' is affiliated with participant '%s'.";

    private static final String PURPOSES = "purposes";
    private static final String DATA_CATEGORIES = "categories of data";

    private final ConsentNoticeGuard noticeGuard;
    private final UserRepository users;
    private final UserParticipantRepository links;
    private final ConsentWriter writer;
    private final ConsentStateMachine lifecycle;
    private final ConsentReporter reporter;
    private final UserNotificationService notifications;

    public ConsentService(
            ConsentNoticeGuard noticeGuard,
            UserRepository users,
            UserParticipantRepository links,
            ConsentWriter writer,
            ConsentStateMachine lifecycle,
            ConsentReporter reporter,
            UserNotificationService notifications) {
        this.noticeGuard = noticeGuard;
        this.users = users;
        this.links = links;
        this.writer = writer;
        this.lifecycle = lifecycle;
        this.reporter = reporter;
        this.notifications = notifications;
    }

    /**
     * Records the token subject's decision on one notice; the subject never comes from the body.
     * {@code 404} unknown notice, {@code 400} invalid decision/notice/election, {@code 409} race.
     */
    @NonNull
    public ConsentDetails decide(@NonNull User subject, @NonNull ConsentDecision decision) {
        lifecycle.requireDecision(decision.decision());
        PrivacyNotice notice = noticeGuard.consentable(decision.privacyNoticeId());
        PrivacyNoticePayload payload = notice.getPayload();
        requireWithinScope(PURPOSES, decision.purposes(), payload.purposes(), notice);
        requireWithinScope(
                DATA_CATEGORIES, decision.dataCategories(), payload.categoriesOfData(), notice);

        ConsentSnapshot snapshot =
                ConsentSnapshot.of(notice, decision.purposes(), decision.dataCategories());
        return reporter.report(record(subject, notice, decision, snapshot));
    }

    /**
     * Opens a {@code PENDING} request, in the token participant's name, for a user to decide on a
     * notice; it confers nothing. See ADR-0009.
     *
     * <p>{@code 404} for an unknown notice and, indistinguishably, an unknown or unaffiliated user;
     * {@code 400} unconsentable notice; {@code 403} caller not a party; {@code 409} already open.
     * The subject is notified after commit, and a notification failure is only logged.
     */
    @NonNull
    public ConsentDetails request(
            @NonNull Participant caller, @NonNull ConsentRequestCommand command) {
        PrivacyNotice notice = noticeGuard.consentable(command.privacyNoticeId());
        requireParty(caller, notice);
        User subject = affiliated(caller, command.userIdentifier());

        ConsentSnapshot snapshot = ConsentSnapshot.of(notice, null, null);
        ConsentWrite written = writer.request(subject, notice, snapshot, caller.getIdentifier());
        RecordedConsent recorded = written.primary();
        try {
            notifications.consentRequested(subject, recorded.consent(), caller);
        } catch (RuntimeException e) {
            LOG.error(NOTIFICATION_FAILED, recorded.consent().getId(), FailureSummary.of(e));
            LOG.debug(NOTIFICATION_FAILED, recorded.consent().getId(), e.getMessage(), e);
        }
        return reporter.report(written);
    }

    /** Refuses a caller the notice names on neither side, even one linked to the user. */
    private static void requireParty(Participant caller, PrivacyNotice notice) {
        if (!caller.getId().equals(notice.getProviderId())
                && !caller.getId().equals(notice.getConsumerId())) {
            throw new ForbiddenException(
                    NOT_A_PARTY.formatted(caller.getIdentifier(), notice.getId()));
        }
    }

    /** The user the caller may ask; unknown and unaffiliated answer the same 404, so no probing. */
    private User affiliated(Participant caller, String userIdentifier) {
        return users.findByIdentifier(userIdentifier)
                .filter(
                        user ->
                                links.existsByIdUserIdAndIdParticipantId(
                                        user.getId(), caller.getId()))
                .orElseThrow(
                        () ->
                                new NotFoundException(
                                        UNKNOWN_SUBJECT.formatted(
                                                userIdentifier, caller.getIdentifier())));
    }

    /** The decision as stored, with a race on the one-grant-per-notice index answered as 409. */
    private ConsentWrite record(
            User subject,
            PrivacyNotice notice,
            ConsentDecision decision,
            ConsentSnapshot snapshot) {
        try {
            return writer.decide(subject, notice, decision.decision(), snapshot);
        } catch (DataAccessException e) {
            if (!UniqueViolation.rejected(e)) {
                throw e;
            }
            throw new ConflictException(CONCURRENT_GRANT.formatted(notice.getId()), e);
        }
    }

    /**
     * Refuses an election the notice cannot honour, naming the heading at fault. An undeclared
     * heading and an explicitly empty election are both refused; omitted means all.
     */
    private static void requireWithinScope(
            String heading,
            @Nullable List<String> elected,
            @Nullable List<String> declared,
            PrivacyNotice notice) {
        if (elected == null) {
            return;
        }
        if (declared == null || declared.isEmpty()) {
            throw new BadRequestException(NOTHING_TO_NARROW.formatted(notice.getId(), heading));
        }
        if (elected.isEmpty()) {
            throw new BadRequestException(EMPTY_ELECTION.formatted(heading));
        }
        List<String> outside = elected.stream().filter(value -> !declared.contains(value)).toList();
        if (!outside.isEmpty()) {
            throw new BadRequestException(
                    OUTSIDE_SCOPE.formatted(heading, outside, notice.getId()));
        }
    }
}
