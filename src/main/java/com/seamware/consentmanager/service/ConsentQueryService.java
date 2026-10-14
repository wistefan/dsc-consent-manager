package com.seamware.consentmanager.service;

import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.error.NotFoundException;
import com.seamware.consentmanager.repository.ConsentRepository;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.Sort;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.UUID;

/**
 * The read side of consents, for a data subject reading their own decisions.
 *
 * <p>Every query is scoped to the subject's own {@code user_id} by predicate, never by a filter
 * applied after the read, so no consent of another subject is ever materialised here. The listing's
 * {@code totalElements} is counted under the same predicate as its content, which is what keeps a
 * narrowed page from reporting the whole history as its total.
 */
@Singleton
public class ConsentQueryService {

    private static final String UNKNOWN_CONSENT = "No consent '%s' belongs to the calling subject.";

    /** First page, applied when the caller names none. */
    private static final int FIRST_PAGE = 0;

    /** Property the listing orders by; a UUIDv7 id breaks a tie within one creation microsecond. */
    private static final Sort NEWEST_FIRST =
            Sort.of(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ConsentRepository consents;
    private final ConsentParties parties;
    private final ConsentManagerConfiguration.Consents paging;

    public ConsentQueryService(
            ConsentRepository consents,
            ConsentParties parties,
            ConsentManagerConfiguration.Consents paging) {
        this.consents = consents;
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
