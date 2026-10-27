package com.seamware.consentmanager.service;

import com.seamware.consentmanager.client.dto.ContractResponse;
import io.micronaut.core.annotation.NonNull;
import java.util.Set;

/**
 * A contract the caller takes part in, its Contract Service document, and the confirmed sides.
 * {@code sides} is never empty and iterates in declaration order.
 */
public record DiscoveredContract(
        @NonNull String contractUri,
        @NonNull ContractResponse contract,
        @NonNull Set<ContractSide> sides) {}
