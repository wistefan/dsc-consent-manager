package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;

/**
 * A consent and the audit event that records how it reached its current status.
 *
 * <p>The two are returned together because they are only ever written together: a consent stored
 * without its event is a decision with no trail of who made it. This is the write-side pair alone;
 * the participant identifiers the API renders belong to {@code ConsentDetails}, so neither type has
 * to carry the other's concern.
 *
 * <p>{@code created} separates an inserted consent from a stored one moved to a new status, which
 * is what the API needs to answer {@code 201} for a record this call brought into being and {@code
 * 200} for an open request it answered in place.
 */
public record RecordedConsent(Consent consent, ConsentEvent event, boolean created) {}
