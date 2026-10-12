package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.Consent;

/**
 * A stored consent with the two participants named as the API renders them.
 *
 * <p>The consent carries its parties as foreign keys, and resolving them is the service's job
 * rather than the mapper's, so the read side is a projection of its own instead of a widened {@link
 * RecordedConsent}, whose two fields are a write unit.
 */
public record ConsentDetails(
        Consent consent, String providerIdentifier, String consumerIdentifier) {}
