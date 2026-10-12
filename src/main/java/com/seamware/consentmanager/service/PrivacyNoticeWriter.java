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
 * Holds the one transaction privacy-notice generation needs: supersede the active notice and store
 * its replacement, or neither.
 *
 * <p>A bean of its own rather than a method on {@link PrivacyNoticeService}, because Micronaut's
 * transactional advice is a proxy and does not apply to self-invocation - called from a sibling
 * method it would run with no transaction at all, and a crash between the two writes would leave
 * the contract with no active notice.
 */
@Singleton
public class PrivacyNoticeWriter {

    private final PrivacyNoticeRepository notices;

    public PrivacyNoticeWriter(PrivacyNoticeRepository notices) {
        this.notices = notices;
    }

    /**
     * Archives the superseded notice, when there is one, and stores the replacement atomically.
     *
     * <p>Throws {@link io.micronaut.data.exceptions.DataAccessException} when a concurrent
     * generation already holds the active slot: {@code uq_privacy_notices_active_contract} rejects
     * the insert, this transaction rolls back whole, and the caller re-reads the winner outside it.
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
