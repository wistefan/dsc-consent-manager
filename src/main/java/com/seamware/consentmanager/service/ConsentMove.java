package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.ConsentStatus;

/**
 * One consent write together with the status it moved away from. {@code held} is read under the
 * write's own row lock, never from an earlier unlocked read.
 */
public record ConsentMove(ConsentStatus held, RecordedConsent recorded) {}
