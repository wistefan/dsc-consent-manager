package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentSnapshot;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.error.BadRequestException;
import com.seamware.consentmanager.error.ConflictException;
import com.seamware.consentmanager.error.NotFoundException;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.exceptions.DataAccessException;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.UUID;

/**
 * The consent lifecycle as the data subject drives it: what a decision is checked against before it
 * is written, and what a written decision reads back as.
 *
 * <p>Everything that touches the database transactionally lives in {@link ConsentWriter}; this bean
 * resolves the notice, the parties and the elected scope around it, and turns a race lost on the
 * one-grant-per-notice index into a {@code 409} instead of a {@code 500}.
 */
@Singleton
public class ConsentService {

    private static final String UNKNOWN_NOTICE = "No privacy notice exists with identifier '%s'.";
    private static final String ARCHIVED_NOTICE =
            "Privacy notice '%s' has been archived and can no longer be consented to. Request the"
                    + " current notice for the contract.";
    private static final String NO_CONSUMER =
            "Privacy notice '%s' names no data consumer, so no consent can be recorded against it.";
    private static final String UNKNOWN_PARTY =
            "privacy notice %s names participant %s, which no longer exists";
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

    private static final String PURPOSES = "purposes";
    private static final String DATA_CATEGORIES = "categories of data";

    private final PrivacyNoticeRepository notices;
    private final ParticipantRepository participants;
    private final ConsentWriter writer;
    private final ConsentStateMachine lifecycle;

    public ConsentService(
            PrivacyNoticeRepository notices,
            ParticipantRepository participants,
            ConsentWriter writer,
            ConsentStateMachine lifecycle) {
        this.notices = notices;
        this.participants = participants;
        this.writer = writer;
        this.lifecycle = lifecycle;
    }

    /**
     * Records the subject's decision about one privacy notice and reads it back with its parties
     * named.
     *
     * <p>The subject comes from the token rather than from {@code decision}, which is what makes a
     * grant in someone else's name unrepresentable here. Refuses an unknown notice with {@code
     * 404}, a status that is not a decision, an archived or unconsentable notice and an election
     * outside its terms with {@code 400}, and a decision overtaken by a concurrent grant with
     * {@code 409}.
     *
     * <p>{@link ConsentDetails#created()} reports which of the writer's branches ran: a decision
     * answering a request that was still open is written onto that record rather than creating
     * one, and the API owes the caller a different success code for it.
     */
    @NonNull
    public ConsentDetails decide(@NonNull User subject, @NonNull ConsentDecision decision) {
        lifecycle.requireDecision(decision.decision());
        PrivacyNotice notice = consentable(decision.privacyNoticeId());
        PrivacyNoticePayload payload = notice.getPayload();
        requireWithinScope(PURPOSES, decision.purposes(), payload.purposes(), notice);
        requireWithinScope(
                DATA_CATEGORIES, decision.dataCategories(), payload.categoriesOfData(), notice);

        ConsentSnapshot snapshot =
                ConsentSnapshot.of(notice, decision.purposes(), decision.dataCategories());
        return details(record(subject, notice, decision, snapshot), notice);
    }

    /** The decision as stored, with a race on the one-grant-per-notice index answered as 409. */
    private RecordedConsent record(
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
     * The notice a decision may be recorded against, or the reason it may not.
     *
     * <p>Read straight from the repository rather than through {@link PrivacyNoticeService}, whose
     * read resolves self-descriptions this flow has no use for. A notice naming no consumer is
     * refused rather than written through: {@code consents.consumer_id} is {@code NOT NULL}, so the
     * alternative is a constraint violation surfacing as a {@code 500}. Both generation paths
     * require a consumer, so only rows predating that invariant can reach it.
     */
    private PrivacyNotice consentable(UUID privacyNoticeId) {
        PrivacyNotice notice =
                notices.findById(privacyNoticeId)
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                UNKNOWN_NOTICE.formatted(privacyNoticeId)));
        if (notice.getArchivedAt() != null) {
            throw new BadRequestException(ARCHIVED_NOTICE.formatted(privacyNoticeId));
        }
        if (notice.getConsumerId() == null) {
            throw new BadRequestException(NO_CONSUMER.formatted(privacyNoticeId));
        }
        return notice;
    }

    /**
     * Refuses an election the notice cannot honour, naming the heading at fault.
     *
     * <p>Every heading of a notice is optional, so an election against one the notice never
     * declared is a client error rather than an empty subset test, and is checked first because the
     * subset test would otherwise dereference nothing. An explicitly empty election is refused too:
     * narrowing to nothing records a consent to nothing, which an omitted field - consenting to the
     * notice's full scope - cannot be mistaken for.
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

    /** The stored consent with both parties resolved to the identifiers the API renders. */
    private ConsentDetails details(RecordedConsent recorded, PrivacyNotice notice) {
        Consent consent = recorded.consent();
        return new ConsentDetails(
                consent,
                party(notice, consent.getProviderId()).getIdentifier(),
                party(notice, consent.getConsumerId()).getIdentifier(),
                recorded.created());
    }

    /**
     * A participant the notice names. Absence is corruption rather than a client error - the
     * notice's foreign keys forbid it - so it fails loudly instead of as a {@code 404}.
     */
    private Participant party(PrivacyNotice notice, UUID participantId) {
        return participants
                .findById(participantId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        UNKNOWN_PARTY.formatted(notice.getId(), participantId)));
    }
}
