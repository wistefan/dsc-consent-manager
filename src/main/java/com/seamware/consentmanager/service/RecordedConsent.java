package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;

/**
 * A consent and the audit event that records how it reached its current status.
 *
 * <p>The two are returned together because they are only ever written together: a consent stored
 * without its event is a decision with no trail of who made it.
 */
public record RecordedConsent(Consent consent, ConsentEvent event) {}
