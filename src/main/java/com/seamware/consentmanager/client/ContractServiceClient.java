package com.seamware.consentmanager.client;

import com.seamware.consentmanager.client.dto.ContractResponse;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.retry.annotation.Retryable;
import java.util.Optional;

/**
 * Reads a contract from the Contract Service; callers use {@link ContractGateway} instead. The
 * {@code Optional} is load-bearing: a 404 is "no such contract", never an outage.
 *
 * <p>Hand-written because the upstream's Swagger 2.0 description models its examples rather than
 * its documents; revisit if it publishes an OpenAPI 3 description with real schemas.
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
