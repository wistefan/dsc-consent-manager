package com.seamware.consentmanager.client;

import com.seamware.consentmanager.client.dto.ContractResponse;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.retry.annotation.Retryable;
import java.util.Optional;

/**
 * Reads a contract from the Contract Service, by identifier, from either of its two endpoints.
 *
 * <p>The {@code Optional} return type is load-bearing: a 404 becomes an empty result rather than an
 * exception, which is exactly the "this service does not know that contract" case and keeps it from
 * ever looking like an outage. Identifiers travel as ordinary path variables and are URL-encoded by
 * the client; nothing is base64-encoded.
 *
 * <p>Callers use {@link ContractGateway} instead, which caches, picks between the two endpoints and
 * translates a failure. Retry is configured here rather than there so it applies per endpoint call.
 */
@Client(id = ContractServiceClient.SERVICE_ID)
@Retryable(
        attempts = "${consent-manager.contract-service.retry-attempts}",
        delay = "${consent-manager.contract-service.retry-delay}",
        multiplier = "${consent-manager.contract-service.retry-multiplier}",
        predicate = UpstreamRetryPredicate.class)
public interface ContractServiceClient {

    /** Service id whose URL and timeouts are configured under {@code micronaut.http.services}. */
    String SERVICE_ID = "contract-service";

    /** Fetches an ecosystem contract; empty when the service knows no such contract. */
    @Get("/contracts/{contractId}")
    Optional<ContractResponse> fetchContract(String contractId);

    /** Fetches a bilateral contract; empty when the service knows no such contract. */
    @Get("/bilaterals/{contractId}")
    Optional<ContractResponse> fetchBilateralContract(String contractId);
}
