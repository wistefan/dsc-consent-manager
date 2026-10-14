package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.ConsentStatus;

/**
 * One consent write together with the status it moved away from.
 *
 * <p>{@code held} is read under the same row lock as the write itself, so whoever reports the move
 * names the status it actually moved from rather than whatever an earlier, unlocked read saw.
 */
public record ConsentMove(ConsentStatus held, RecordedConsent recorded) {}
