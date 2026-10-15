package com.seamware.consentmanager.service;

import com.seamware.consentmanager.client.dto.ContractResponse;
import io.micronaut.core.annotation.NonNull;
import java.util.Set;

/**
 * A contract the caller takes part in: the URI this service keys it by, the document the Contract
 * Service holds for it, and the sides this service's notices put the caller on that the document
 * still confirms. {@code sides} is never empty and iterates in declaration order.
 */
public record DiscoveredContract(
        @NonNull String contractUri,
        @NonNull ContractResponse contract,
        @NonNull Set<ContractSide> sides) {}
