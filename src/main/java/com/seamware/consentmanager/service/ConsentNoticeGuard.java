package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.error.BadRequestException;
import com.seamware.consentmanager.error.NotFoundException;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import io.micronaut.core.annotation.NonNull;
import jakarta.inject.Singleton;
import java.util.UUID;

/** One definition of a notice that may be consented to, shared by both routes into GRANTED. */
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
     * The notice a grant may be recorded against; {@code 404} if unknown, {@code 400} if archived
     * or naming no consumer (which would otherwise violate {@code consents.consumer_id NOT NULL}).
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
