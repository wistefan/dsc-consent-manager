package com.seamware.consentmanager.service;

import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.error.NotFoundException;
import com.seamware.consentmanager.repository.ConsentRepository;
import com.seamware.consentmanager.repository.UserRepository;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.Sort;
import jakarta.inject.Singleton;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The read side of consents: a subject reading their own, a participant reading those it is party
 * to.
 *
 * <p>Every query, counts included, is scoped by SQL predicate, never filtered after the read, so no
 * consent outside the caller's scope is ever materialised. See ADR-0013.
 */
@Singleton
public class ConsentQueryService {

    private static final String UNKNOWN_CONSENT = "No consent '%s' belongs to the calling subject.";

    private static final String UNKNOWN_CONSENT_FOR_PARTICIPANT =
            "No consent '%s' names the calling participant as a party.";

    /** The no-grant reason; shared by unknown and grantless subjects so neither is disclosed. */
    private static final String NO_GRANT_IN_FORCE =
            "No consent is in force for this subject and notice.";

    /** The reason when a grant was found but does not cover the named purpose. */
    private static final String PURPOSE_NOT_COVERED =
            "The consent in force does not cover the requested purpose.";

    private static final int FIRST_PAGE = 0;

    /** Statuses a participant listing admits when it names none. */
    private static final List<ConsentStatus> DEFAULT_PARTICIPANT_STATUSES =
            List.of(ConsentStatus.GRANTED);

    /** Listing order; the UUIDv7 id breaks a tie within one creation microsecond. */
    private static final Sort NEWEST_FIRST =
            Sort.of(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ConsentRepository consents;
    private final UserRepository users;
    private final ConsentParties parties;
    private final ConsentManagerConfiguration.Consents paging;

    public ConsentQueryService(
            ConsentRepository consents,
            UserRepository users,
            ConsentParties parties,
            ConsentManagerConfiguration.Consents paging) {
        this.consents = consents;
        this.users = users;
        this.parties = parties;
        this.paging = paging;
    }

    /**
     * One page of the subject's own consents, newest first, with both parties named. {@code size}
     * is clamped to the configured ceiling, not refused.
     */
    @NonNull
    public Page<ConsentDetails> listForUser(
            @NonNull User subject, @NonNull ConsentQueryCriteria criteria) {
        Pageable pageable =
                Pageable.from(
                        criteria.page() == null ? FIRST_PAGE : criteria.page(),
                        applicableSize(criteria.size()),
                        NEWEST_FIRST);
        return matching(subject.getId(), criteria, pageable).map(this::read);
    }

    /** One consent the subject owns; unknown and foreign ids are the same {@code 404}. */
    @NonNull
    public ConsentDetails getForUser(@NonNull User subject, @NonNull UUID id) {
        Consent consent =
                consents.findByIdAndUserId(id, subject.getId())
                        .orElseThrow(() -> new NotFoundException(UNKNOWN_CONSENT.formatted(id)));
        return read(consent);
    }

    /**
     * One page of the consents the participant is party to, newest first, with both parties named.
     *
     * <p>An unknown or out-of-scope {@code userIdentifier} yields an empty page, never a refusal,
     * so identifiers cannot be probed. No status filter means {@code GRANTED}; size is clamped.
     */
    @NonNull
    public Page<ConsentDetails> listForParticipant(
            @NonNull Participant caller,
            @Nullable String userIdentifier,
            @NonNull ConsentQueryCriteria criteria) {
        Pageable pageable =
                Pageable.from(
                        criteria.page() == null ? FIRST_PAGE : criteria.page(),
                        applicableSize(criteria.size()),
                        NEWEST_FIRST);
        if (userIdentifier != null) {
            Optional<User> subject = users.findByIdentifier(userIdentifier);
            if (subject.isEmpty()) {
                return Page.of(List.of(), pageable, 0L);
            }
            return participantPage(caller, subject.get().getId(), criteria, pageable);
        }
        return participantPage(caller, null, criteria, pageable);
    }

    /** One consent the participant is party to; unknown and out-of-scope are one {@code 404}. */
    @NonNull
    public ConsentDetails getForParticipant(@NonNull Participant caller, @NonNull UUID id) {
        Consent consent =
                consents.findByIdForParticipant(id, caller.getId())
                        .orElseThrow(
                                () ->
                                        new NotFoundException(
                                                UNKNOWN_CONSENT_FOR_PARTICIPANT.formatted(id)));
        return read(consent);
    }

    /**
     * Whether a {@code GRANTED} consent the caller is party to is in force for the subject and
     * notice, and covers {@code purpose} verbatim when one is named.
     *
     * <p>Every no-grant case answers alike, so neither the subject's existence nor another
     * participant's grant is disclosed.
     */
    @NonNull
    public ConsentValidityResult checkValidity(
            @NonNull Participant caller,
            @NonNull String userIdentifier,
            @NonNull UUID privacyNoticeId,
            @Nullable String purpose) {
        OffsetDateTime checkedAt = now();
        Optional<Consent> grant =
                consents.findForParticipantBySubjectAndNoticeAndStatus(
                        userIdentifier, privacyNoticeId, ConsentStatus.GRANTED, caller.getId());
        if (grant.isEmpty()) {
            return new ConsentValidityResult(false, null, NO_GRANT_IN_FORCE, checkedAt);
        }
        Consent consent = grant.get();
        if (purpose != null && !covers(consent, purpose)) {
            return new ConsentValidityResult(false, consent, PURPOSE_NOT_COVERED, checkedAt);
        }
        return new ConsentValidityResult(true, consent, null, checkedAt);
    }

    /** Whether the grant's frozen scope names the purpose; a consent naming none covers none. */
    private boolean covers(Consent grant, String purpose) {
        List<String> purposes = grant.getSnapshot().purposes();
        return purposes != null && purposes.contains(purpose);
    }

    /** The moment the check was made, at the microsecond resolution the stored timestamps keep. */
    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    /** The scoped page for a resolved or absent subject, statuses defaulted to GRANTED. */
    private Page<ConsentDetails> participantPage(
            Participant caller,
            @Nullable UUID userId,
            ConsentQueryCriteria criteria,
            Pageable pageable) {
        return consents.findForParticipant(
                        caller.getId(),
                        applicableStatuses(criteria.statuses()),
                        userId,
                        criteria.privacyNoticeId(),
                        pageable)
                .map(this::read);
    }

    /** The requested statuses, or {@code GRANTED} alone when the filter named none. */
    private List<ConsentStatus> applicableStatuses(@Nullable List<ConsentStatus> requested) {
        return requested == null || requested.isEmpty() ? DEFAULT_PARTICIPANT_STATUSES : requested;
    }

    /** The repository query for the filters present, each backed by {@code idx_consents_user_*}. */
    private Page<Consent> matching(UUID userId, ConsentQueryCriteria criteria, Pageable pageable) {
        List<ConsentStatus> statuses = criteria.statuses();
        UUID noticeId = criteria.privacyNoticeId();
        boolean byStatus = statuses != null && !statuses.isEmpty();
        if (byStatus && noticeId != null) {
            return consents.findByUserIdAndStatusInAndPrivacyNoticeId(
                    userId, statuses, noticeId, pageable);
        }
        if (byStatus) {
            return consents.findByUserIdAndStatusIn(userId, statuses, pageable);
        }
        if (noticeId != null) {
            return consents.findByUserIdAndPrivacyNoticeId(userId, noticeId, pageable);
        }
        return consents.findByUserId(userId, pageable);
    }

    /** The stored consent with its parties resolved; never {@code created}. */
    private ConsentDetails read(Consent consent) {
        return parties.details(consent, false);
    }

    /** The requested size capped at the configured ceiling, or the configured default. */
    private int applicableSize(@Nullable Integer size) {
        return Math.min(size == null ? paging.getPageDefaultSize() : size, paging.getPageMaxSize());
    }
}
