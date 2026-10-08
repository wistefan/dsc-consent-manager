package com.seamware.consentmanager.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.client.dto.ContractResponse;
import com.seamware.consentmanager.error.UpstreamServiceException;
import com.seamware.consentmanager.support.ContractFixtures;
import com.seamware.consentmanager.support.ContractServiceStub;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins how the gateway treats an upstream that fails, refuses or does not know a contract.
 *
 * <p>"Not retried" and "failed" are different claims, so every case asserts the request count the
 * stub actually saw rather than only the outcome the caller sees: a predicate that retried a 400
 * would still raise the same exception while tripling the load on an upstream that already refused.
 */
@DisplayName("Contract Service gateway")
class ContractGatewayTest {

    /** Retries each test configures, low enough to keep the retried cases quick. */
    private static final int RETRY_ATTEMPTS = 3;

    /**
     * Requests a fully retried fetch leaves at the stub: the initial attempt plus every retry.
     *
     * <p>{@code @Retryable} counts retries after the first attempt, so a budget of three retries is
     * four upstream requests.
     */
    private static final int EXPECTED_UPSTREAM_REQUESTS = RETRY_ATTEMPTS + 1;

    /** Pause between attempts, kept short because the suite asserts counts and not timing. */
    private static final String RETRY_DELAY = "20ms";

    /** Flat backoff, so the total wait of a retried case stays bounded by the attempt count. */
    private static final String RETRY_MULTIPLIER = "1.0";

    /** Read timeout of the slow-upstream case. */
    private static final String SHORT_READ_TIMEOUT = "300ms";

    /** How long the slow upstream holds a request, comfortably past that timeout. */
    private static final Duration UPSTREAM_DELAY = Duration.ofSeconds(3);

    /** Contract URI under test; its last segment is the id the upstream is asked for. */
    private static final String CONTRACT_URI = "https://contract.example.org/v1/contracts/c-1";

    /** The identifier derived from that URI. */
    private static final String CONTRACT_ID = "c-1";

    /** A 200 that is no contract: what a proxy in front of the upstream answers with. */
    private static final String PROXY_ERROR_PAGE = "<html><body>502 Bad Gateway</body></html>";

    @ParameterizedTest(name = "{0} is retried")
    @ValueSource(ints = {500, 502, 503})
    @DisplayName("an upstream failure is retried to the budget and then surfaces as 502")
    void serverErrorsAreRetried(int status) {
        try (ContractServiceStub stub = new ContractServiceStub();
                ApplicationContext context = startContext(stub, Map.of())) {
            stub.serveStatus(CONTRACT_ID, status);
            ContractGateway gateway = context.getBean(ContractGateway.class);

            assertThatThrownBy(() -> gateway.fetch(CONTRACT_URI))
                    .as("an unreachable Contract Service must never look like an unknown contract")
                    .isInstanceOf(UpstreamServiceException.class)
                    .hasMessageContaining(CONTRACT_URI);

            assertThat(stub.ecosystemRequestCount(CONTRACT_ID))
                    .as("a transient failure is worth the initial attempt plus every retry")
                    .isEqualTo(EXPECTED_UPSTREAM_REQUESTS);
            assertThat(stub.bilateralRequestCount(CONTRACT_ID))
                    .as("the fallback endpoint is for a miss, not for a failure")
                    .isZero();
        }
    }

    @ParameterizedTest(name = "{0} is not retried")
    @ValueSource(ints = {400, 401, 403, 409})
    @DisplayName("a refusal is not retried, because another attempt cannot change it")
    void clientErrorsAreNotRetried(int status) {
        try (ContractServiceStub stub = new ContractServiceStub();
                ApplicationContext context = startContext(stub, Map.of())) {
            stub.serveStatus(CONTRACT_ID, status);
            ContractGateway gateway = context.getBean(ContractGateway.class);

            assertThatThrownBy(() -> gateway.fetch(CONTRACT_URI))
                    .isInstanceOf(UpstreamServiceException.class);

            assertThat(stub.ecosystemRequestCount(CONTRACT_ID))
                    .as("retrying a refused request would only multiply the load it caused")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a read timeout is retried like any other transport failure")
    void readTimeoutsAreRetried() {
        try (ContractServiceStub stub = new ContractServiceStub();
                ApplicationContext context =
                        startContext(
                                stub,
                                Map.of(
                                        "micronaut.http.services.contract-service.read-timeout",
                                        SHORT_READ_TIMEOUT))) {
            stub.serveSlowly(CONTRACT_ID, UPSTREAM_DELAY);
            ContractGateway gateway = context.getBean(ContractGateway.class);

            assertThatThrownBy(() -> gateway.fetch(CONTRACT_URI))
                    .as("an upstream that never answers is an outage, not a missing contract")
                    .isInstanceOf(UpstreamServiceException.class);

            assertThat(stub.ecosystemRequestCount(CONTRACT_ID))
                    .as("a timeout is transient, so it spends the retry budget a 5xx would")
                    .isEqualTo(EXPECTED_UPSTREAM_REQUESTS);
        }
    }

    @Test
    @DisplayName("a contract neither endpoint knows is empty, fetched once per endpoint")
    void unknownContractIsEmptyAndNotRetried() {
        try (ContractServiceStub stub = new ContractServiceStub();
                ApplicationContext context = startContext(stub, Map.of())) {
            stub.serveUnknown(CONTRACT_ID);
            ContractGateway gateway = context.getBean(ContractGateway.class);

            assertThat(gateway.fetch(CONTRACT_URI))
                    .as("a 404 is the answer 'no such contract', not a failure")
                    .isEmpty();

            assertThat(stub.ecosystemRequestCount(CONTRACT_ID))
                    .as("a 404 never reaches the retry predicate")
                    .isEqualTo(1);
            assertThat(stub.bilateralRequestCount(CONTRACT_ID))
                    .as("the bilateral endpoint is tried exactly once after the ecosystem miss")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a failure within the budget still returns the contract")
    void recoversWithinTheRetryBudget() {
        try (ContractServiceStub stub = new ContractServiceStub();
                ApplicationContext context = startContext(stub, Map.of())) {
            stub.serveFailureThenContract(
                    CONTRACT_ID, 500, ContractFixtures.body(ContractFixtures.ECOSYSTEM));
            ContractGateway gateway = context.getBean(ContractGateway.class);

            Optional<ContractResponse> contract = gateway.fetch(CONTRACT_URI);

            assertThat(contract)
                    .as("one failed attempt out of the budget is survivable")
                    .isPresent();
            assertThat(contract.orElseThrow().shape()).isEqualTo(ContractResponse.Shape.ECOSYSTEM);
            assertThat(stub.ecosystemRequestCount(CONTRACT_ID))
                    .as("the second attempt succeeded, so no further attempt was spent")
                    .isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a contract only the bilateral endpoint knows is found by the fallback")
    void fallsBackToTheBilateralEndpoint() {
        try (ContractServiceStub stub = new ContractServiceStub();
                ApplicationContext context = startContext(stub, Map.of())) {
            stub.serveUnknown(CONTRACT_ID);
            stub.serveBilateralContract(
                    CONTRACT_ID, ContractFixtures.body(ContractFixtures.BILATERAL));
            ContractGateway gateway = context.getBean(ContractGateway.class);

            assertThat(gateway.fetch(CONTRACT_URI).orElseThrow().shape())
                    .as("both contract shapes are reachable through one gateway call")
                    .isEqualTo(ContractResponse.Shape.BILATERAL);
        }
    }

    @Test
    @DisplayName("a 200 carrying no contract is an upstream failure, not a defect in this service")
    void anUndecodableBodyIsAnUpstreamFailure() {
        try (ContractServiceStub stub = new ContractServiceStub();
                ApplicationContext context = startContext(stub, Map.of())) {
            stub.serveEcosystemContract(CONTRACT_ID, PROXY_ERROR_PAGE);
            ContractGateway gateway = context.getBean(ContractGateway.class);

            assertThatThrownBy(() -> gateway.fetch(CONTRACT_URI))
                    .as("a proxy's error page served as JSON is a 502, never the 500 of a defect")
                    .isInstanceOf(UpstreamServiceException.class);
        }
    }

    @ParameterizedTest(name = "{0} is fetched as {1}")
    @CsvSource({
        "https://contract.example.org/v1/contracts/c-1, c-1",
        "https://contract.example.org/v1/contracts/c-1/, c-1",
        "https://contract.example.org/v1/contracts/c-1?version=2, c-1",
        "https://contract.example.org/v1/contracts/c-1#terms, c-1",
        "c-1, c-1"
    })
    @DisplayName("an http contract URI is fetched by its last path segment, query and fragment off")
    void derivesTheIdentifierFromTheLastSegment(String contractUri, String expectedId) {
        assertThat(ContractGateway.contractIdOf(contractUri))
                .as("an identifier carrying a query would URL-encode into a 404")
                .isEqualTo(expectedId);
    }

    /** Starts a context whose Contract Service is the stub, with a quick retry budget. */
    static ApplicationContext startContext(
            ContractServiceStub stub, Map<String, Object> overrides) {
        Map<String, Object> properties = new LinkedHashMap<>();
        // No datasource and no Flyway: the gateway talks to an HTTP stub and never to the database.
        properties.put("datasources.default.enabled", false);
        properties.put("flyway.enabled", false);
        properties.put("micronaut.server.port", -1);
        properties.put("consent-manager.contract-service.url", stub.url());
        properties.put("consent-manager.contract-service.retry-attempts", RETRY_ATTEMPTS);
        properties.put("consent-manager.contract-service.retry-delay", RETRY_DELAY);
        properties.put("consent-manager.contract-service.retry-multiplier", RETRY_MULTIPLIER);
        properties.putAll(overrides);
        return ApplicationContext.builder(Environment.TEST).properties(properties).start();
    }
}
