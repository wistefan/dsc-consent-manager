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
 *
 * <p>Hand-written rather than generated, unlike this service's own API, because the upstream
 * description cannot carry a generation. The Contract Service publishes {@code
 * src/swagger/swagger.json} (Prometheus-X-association/contract-manager), but it is Swagger 2.0
 * emitted by {@code swagger-autogen} from example objects, so its {@code definitions} describe the
 * examples rather than the documents - {@code Contract.uid} comes out as an object with a {@code
 * type} property whose value is the string {@code "string"} - and {@code GET
 * /bilaterals/&#123;id&#125;} declares no response schema at all. Generating from it would produce
 * wrapper types that match no document this service ever receives, so the two endpoints are
 * declared by hand and {@link ContractResponse} models what the upstream actually serves. Revisit
 * if the Contract Service ever publishes an OpenAPI 3 description with real schemas.
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
