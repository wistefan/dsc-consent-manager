package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.Consent;

/**
 * A stored consent with the two participants named as the API renders them.
 *
 * <p>The consent carries its parties as foreign keys, and resolving them is the service's job
 * rather than the mapper's, so the read side is a projection of its own instead of a widened {@link
 * RecordedConsent}, whose two fields are a write unit.
 *
 * <p>{@code created} is what separates the two success codes - {@code 201} for a consent this
 * decision brought into being, {@code 200} for an open request it answered in place, where the
 * record and its identifier were already there.
 */
public record ConsentDetails(
        Consent consent, String providerIdentifier, String consumerIdentifier, boolean created) {}
