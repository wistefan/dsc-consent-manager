package com.seamware.consentmanager.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.error.UpstreamServiceException;
import com.seamware.consentmanager.support.ContractFixtures;
import com.seamware.consentmanager.support.ContractServiceStub;
import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins what the contract cache stores and, more importantly, what it refuses to store.
 *
 * <p>The two negative cases are asserted apart because "a failed fetch is not cached" does not
 * distinguish them: an unknown contract and an unreachable upstream both yield no cache entry, but
 * only the first is an answer. Caching either would freeze it for a whole lifetime, and nothing in
 * the service could then tell "unknown" from "unknown an hour ago".
 *
 * <p>Starts a real context against a stubbed upstream rather than mocking the cache, because the
 * claim under test is about the configured {@code contracts} cache and not about a call sequence.
 */
@DisplayName("Contract caching")
class ContractCacheIT {

    /** Contract URI the caching cases fetch twice. */
    private static final String CONTRACT_URI = "https://contract.example.org/v1/contracts/c-1";

    /** Identifier derived from that URI. */
    private static final String CONTRACT_ID = "c-1";

    /** A second contract, to show the cache is keyed and not just a single-slot memo. */
    private static final String OTHER_CONTRACT_URI =
            "https://contract.example.org/v1/contracts/c-2";

    /** Identifier derived from the second URI. */
    private static final String OTHER_CONTRACT_ID = "c-2";

    /** Status standing in for an upstream outage that outlasts the retry budget. */
    private static final int UPSTREAM_FAILURE = 503;

    @Test
    @DisplayName("a repeated fetch of one contract makes no second upstream call")
    void repeatedFetchIsServedFromTheCache() {
        try (ContractServiceStub stub = new ContractServiceStub();
                ApplicationContext context = startContext(stub)) {
            stub.serveEcosystemContract(
                    CONTRACT_ID, ContractFixtures.body(ContractFixtures.ECOSYSTEM));
            ContractGateway gateway = context.getBean(ContractGateway.class);

            assertThat(gateway.fetch(CONTRACT_URI)).isPresent();
            assertThat(gateway.fetch(CONTRACT_URI)).isPresent();

            assertThat(stub.ecosystemRequestCount(CONTRACT_ID))
                    .as("a regeneration from the same contract costs one upstream call, not two")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("two contracts are cached apart")
    void differentContractsAreFetchedSeparately() {
        try (ContractServiceStub stub = new ContractServiceStub();
                ApplicationContext context = startContext(stub)) {
            String body = ContractFixtures.body(ContractFixtures.ECOSYSTEM);
            stub.serveEcosystemContract(CONTRACT_ID, body);
            stub.serveEcosystemContract(OTHER_CONTRACT_ID, body);
            ContractGateway gateway = context.getBean(ContractGateway.class);

            assertThat(gateway.fetch(CONTRACT_URI)).isPresent();
            assertThat(gateway.fetch(OTHER_CONTRACT_URI)).isPresent();

            assertThat(stub.ecosystemRequestCount(CONTRACT_ID)).isEqualTo(1);
            assertThat(stub.ecosystemRequestCount(OTHER_CONTRACT_ID))
                    .as("the cache is keyed on the contract URI as given")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("an unknown contract is not cached, so a contract created later is visible")
    void unknownContractIsNotCached() {
        try (ContractServiceStub stub = new ContractServiceStub();
                ApplicationContext context = startContext(stub)) {
            stub.serveUnknown(CONTRACT_ID);
            ContractGateway gateway = context.getBean(ContractGateway.class);

            assertThat(gateway.fetch(CONTRACT_URI)).isEmpty();

            stub.serveEcosystemContract(
                    CONTRACT_ID, ContractFixtures.body(ContractFixtures.ECOSYSTEM));

            assertThat(gateway.fetch(CONTRACT_URI))
                    .as("a contract created upstream after the miss must not stay invisible")
                    .isPresent();
        }
    }

    @Test
    @DisplayName("an exhausted retry is not cached, so a recovered upstream is reached again")
    void upstreamFailureIsNotCached() {
        try (ContractServiceStub stub = new ContractServiceStub();
                ApplicationContext context = startContext(stub)) {
            stub.serveStatus(CONTRACT_ID, UPSTREAM_FAILURE);
            ContractGateway gateway = context.getBean(ContractGateway.class);

            assertThatThrownBy(() -> gateway.fetch(CONTRACT_URI))
                    .isInstanceOf(UpstreamServiceException.class);

            stub.serveEcosystemContract(
                    CONTRACT_ID, ContractFixtures.body(ContractFixtures.ECOSYSTEM));

            assertThat(gateway.fetch(CONTRACT_URI))
                    .as("an outage must not outlive itself as a cached failure")
                    .isPresent();
        }
    }

    /**
     * Starts a context against the stub, reusing the gateway suite's property set so the retry
     * budget both suites run under is declared once.
     */
    private static ApplicationContext startContext(ContractServiceStub stub) {
        return ContractGatewayTest.startContext(stub, Map.of());
    }
}
