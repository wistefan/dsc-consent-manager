package com.seamware.consentmanager.service;

import io.micronaut.core.annotation.NonNull;

/**
 * The two participant identifiers a contract puts on either side of it. Both are always stated;
 * {@link ContractPartyResolver} refuses rather than leave one unnamed.
 */
public record ContractParties(@NonNull String dataProvider, @NonNull String dataConsumer) {}
