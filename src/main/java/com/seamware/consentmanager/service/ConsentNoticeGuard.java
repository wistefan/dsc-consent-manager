package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.error.BadRequestException;
import com.seamware.consentmanager.error.NotFoundException;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import io.micronaut.core.annotation.NonNull;
import jakarta.inject.Singleton;
import java.util.UUID;

/**
 * One definition of a notice that may be consented to, shared by both routes into {@code GRANTED}.
 *
 * <p>Its own bean rather than a private method on {@link ConsentService} because {@code POST
 * /consents} is no longer the only way a grant is recorded: {@code POST /consents/{id}/resume}
 * grants a request that may have sat unanswered while its notice was regenerated, and a rule
 * written twice is a rule that drifts.
 */
@Singleton
public class ConsentNoticeGuard {

    private static final String UNKNOWN_NOTICE = "No privacy notice exists with identifier '%s'.";
    private static final String ARCHIVED_NOTICE =
            "Privacy notice '%s' has been archived and can no longer be consented to. Request the"
                    + " current notice for the contract.";
    private static final String NO_CONSUMER =
            "Privacy notice '%s' names no data consumer, so no consent can be recorded against it.";

    private final PrivacyNoticeRepository notices;

    public ConsentNoticeGuard(PrivacyNoticeRepository notices) {
        this.notices = notices;
    }

    /**
     * The notice a grant may be recorded against, or the reason it may not.
     *
     * <p>Read straight from the repository rather than through {@link PrivacyNoticeService}, whose
     * read resolves self-descriptions this flow has no use for. A notice naming no consumer is
     * refused rather than written through: {@code consents.consumer_id} is {@code NOT NULL}, so the
     * alternative is a constraint violation surfacing as a {@code 500}. Both generation paths
     * require a consumer, so only rows predating that invariant can reach it.
     */
    @NonNull
    public PrivacyNotice consentable(@NonNull UUID privacyNoticeId) {
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
}
