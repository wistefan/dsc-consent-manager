package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.Consent;

/**
 * A stored consent with its two participants resolved to identifiers, as the API renders them.
 * {@code created} selects {@code 201} for a new consent, {@code 200} for a request answered.
 */
public record ConsentDetails(
        Consent consent, String providerIdentifier, String consumerIdentifier, boolean created) {}
