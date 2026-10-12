package com.seamware.consentmanager.service;

import io.micronaut.core.annotation.NonNull;

/**
 * The two participants a contract puts on either side of it, as identifiers.
 *
 * <p>What {@link ContractPartyResolver} read off a contract that names its parties unambiguously.
 * Both components are stated: a derivation that could not name one of them refuses instead.
 */
public record ContractParties(@NonNull String dataProvider, @NonNull String dataConsumer) {}
