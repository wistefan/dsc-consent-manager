package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentEvent;

/**
 * A consent and the audit event recording how it reached its status; always written together.
 * {@code created} separates an insert ({@code 201}) from a stored row moved in place ({@code 200}).
 */
public record RecordedConsent(Consent consent, ConsentEvent event, boolean created) {}
