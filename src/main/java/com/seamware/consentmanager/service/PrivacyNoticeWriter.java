package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.repository.PrivacyNoticeRepository;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The one transaction notice generation needs: supersede the active notice and store its
 * replacement, or neither. A separate bean because Micronaut AOP skips self-invocation.
 */
@Singleton
public class PrivacyNoticeWriter {

    private final PrivacyNoticeRepository notices;

    public PrivacyNoticeWriter(PrivacyNoticeRepository notices) {
        this.notices = notices;
    }

    /**
     * Archives the superseded notice, if any, and stores the replacement atomically.
     *
     * @throws io.micronaut.data.exceptions.DataAccessException when a concurrent generation holds
     *     {@code uq_privacy_notices_active_contract}; the caller re-reads the winner outside the
     *     transaction
     */
    @NonNull
    @Transactional
    public PrivacyNotice archiveAndCreate(
            @Nullable PrivacyNotice superseded, @NonNull PrivacyNotice replacement) {
        if (superseded != null) {
            superseded.setArchivedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
            notices.update(superseded);
        }
        return notices.save(replacement);
    }
}
