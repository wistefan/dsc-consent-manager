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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The read side of consents: a data subject reading their own decisions, and a participant reading
 * the decisions it is a party to.
 *
 * <p>Every query is scoped by predicate - the subject's own {@code user_id}, or the participant as
 * provider or consumer - never by a filter applied after the read, so no consent outside the
 * caller's scope is ever materialised here. The listing's {@code totalElements} is counted under
 * the same predicate as its content, which is what keeps a narrowed page from reporting the whole
 * history as its total.
 */
@Singleton
public class ConsentQueryService {

    private static final String UNKNOWN_CONSENT = "No consent '%s' belongs to the calling subject.";

    private static final String UNKNOWN_CONSENT_FOR_PARTICIPANT =
            "No consent '%s' names the calling participant as a party.";

    /** First page, applied when the caller names none. */
    private static final int FIRST_PAGE = 0;

    /**
     * Statuses a participant listing admits when it names none; the decisions in force are what a
     * participant asking what it may act on wants, so an absent filter means {@code GRANTED} alone.
     */
    private static final List<ConsentStatus> DEFAULT_PARTICIPANT_STATUSES =
            List.of(ConsentStatus.GRANTED);

    /** Property the listing orders by; a UUIDv7 id breaks a tie within one creation microsecond. */
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
     * One page of the subject's own consents, newest first, with both parties named.
     *
     * <p>{@code size} is clamped to the configured ceiling rather than refused; the returned page's
     * size reports what was applied.
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

    /**
     * One consent the subject owns, or a {@code 404} that does not distinguish an unknown id from
     * one owned by somebody else.
     */
    @NonNull
    public ConsentDetails getForUser(@NonNull User subject, @NonNull UUID id) {
        Consent consent =
                consents.findByIdAndUserId(id, subject.getId())
                        .orElseThrow(() -> new NotFoundException(UNKNOWN_CONSENT.formatted(id)));
        return read(consent);
    }

    /**
     * One page of the consents the participant is a party to, newest first, with both parties
     * named.
     *
     * <p>{@code userIdentifier} narrows the page to one data subject. An identifier no user holds,
     * or one the caller is party to no consent for, yields an empty page rather than a refusal: the
     * scope is the predicate {@code provider = caller OR consumer = caller}, so a 403 here would
     * let a participant probe which identifiers exist. An absent {@code status} filter means {@code
     * GRANTED} alone; {@code size} is clamped to the configured ceiling rather than refused.
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
                return Page.empty();
            }
            return participantPage(caller, subject.get().getId(), criteria, pageable);
        }
        return participantPage(caller, null, criteria, pageable);
    }

    /**
     * One consent the participant is a party to, or a {@code 404} that does not distinguish an
     * unknown id from one the caller is no party to.
     */
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
     * The scoped page for a resolved (or absent) subject, statuses defaulted to {@code GRANTED}.
     */
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

    /**
     * The stored consent with its parties resolved; a read creates nothing, hence {@code false}.
     */
    private ConsentDetails read(Consent consent) {
        return parties.details(consent, false);
    }

    /**
     * The requested size bounded by the configured ceiling, or the configured default when none.
     */
    private int applicableSize(@Nullable Integer size) {
        return Math.min(size == null ? paging.getPageDefaultSize() : size, paging.getPageMaxSize());
    }
}
