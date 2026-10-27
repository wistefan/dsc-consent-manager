package com.seamware.consentmanager.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import java.time.Duration;

/**
 * Stubbed Contract Service, serving or refusing a contract on either endpoint. Owned per test; each
 * method re-stubs its endpoint, so a test can change the answer mid-test.
 */
public final class ContractServiceStub implements AutoCloseable {

    /** Endpoint an ecosystem contract is served from. */
    private static final String ECOSYSTEM_PATH = "/contracts/";

    /** Endpoint a bilateral contract is served from, tried when the ecosystem one is a miss. */
    private static final String BILATERAL_PATH = "/bilaterals/";

    private static final String JSON = "application/json";

    private static final int OK = 200;

    /** Status both endpoints answer when no contract carries the requested id. */
    private static final int NOT_FOUND = 404;

    private static final String CONTENT_TYPE = "Content-Type";

    /** Scenario state a recovering stub serves the contract from. */
    private static final String RECOVERED_STATE = "recovered";

    /** A JSON-typed body that is not JSON, so reading it fails where decoding happens. */
    private static final String MALFORMED_BODY = "this is not a contract";

    private final WireMockServer server;

    /** Starts the stub on a free port; nothing is stubbed until a {@code serve} call. */
    public ContractServiceStub() {
        this.server = new WireMockServer(options().dynamicPort());
        this.server.start();
    }

    /** Base URL to configure {@code consent-manager.contract-service.url} with. */
    public String url() {
        return server.baseUrl();
    }

    /** Serves a body as the ecosystem contract with that id. */
    public void serveEcosystemContract(String contractId, String body) {
        serveBody(ECOSYSTEM_PATH + contractId, body);
    }

    /** Serves a body as the bilateral contract with that id. */
    public void serveBilateralContract(String contractId, String body) {
        serveBody(BILATERAL_PATH + contractId, body);
    }

    /** Answers both endpoints with a status, which is how an upstream failure is stubbed. */
    public void serveStatus(String contractId, int status) {
        stubStatus(ECOSYSTEM_PATH + contractId, status);
        stubStatus(BILATERAL_PATH + contractId, status);
    }

    /** Answers both endpoints with 404, the "no such contract" case. */
    public void serveUnknown(String contractId) {
        serveStatus(contractId, NOT_FOUND);
    }

    /** Holds the ecosystem endpoint open past any sane read timeout before answering. */
    public void serveSlowly(String contractId, Duration delay) {
        server.stubFor(
                get(urlEqualTo(ECOSYSTEM_PATH + contractId))
                        .willReturn(
                                aResponse()
                                        .withStatus(OK)
                                        .withHeader(CONTENT_TYPE, JSON)
                                        .withFixedDelay((int) delay.toMillis())
                                        .withBody("{}")));
    }

    /**
     * Answers the ecosystem endpoint with a failure once, then with the contract. Shows a retry
     * budget spent rather than exhausted.
     */
    public void serveFailureThenContract(String contractId, int failureStatus, String body) {
        String path = ECOSYSTEM_PATH + contractId;
        server.stubFor(
                get(urlEqualTo(path))
                        .inScenario(path)
                        .whenScenarioStateIs(STARTED)
                        .willSetStateTo(RECOVERED_STATE)
                        .willReturn(aResponse().withStatus(failureStatus)));
        server.stubFor(
                get(urlEqualTo(path))
                        .inScenario(path)
                        .whenScenarioStateIs(RECOVERED_STATE)
                        .willReturn(
                                aResponse()
                                        .withStatus(OK)
                                        .withHeader(CONTENT_TYPE, JSON)
                                        .withBody(body)));
    }

    /**
     * Serves a body on the ecosystem endpoint that is not a contract document. The gateway reports
     * the decoding failure as an unreachable upstream.
     */
    public void serveMalformedContract(String contractId) {
        serveBody(ECOSYSTEM_PATH + contractId, MALFORMED_BODY);
    }

    /** Drops the connection on the ecosystem endpoint, the transport failure a retry cannot fix. */
    public void serveFault(String contractId) {
        server.stubFor(
                get(urlEqualTo(ECOSYSTEM_PATH + contractId))
                        .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    }

    /** Requests this stub has received at all, which is how "nothing was read" is asserted. */
    public int requestCount() {
        return server.getAllServeEvents().size();
    }

    /** Requests the ecosystem endpoint has received for that id. */
    public int ecosystemRequestCount(String contractId) {
        return requestCount(ECOSYSTEM_PATH + contractId);
    }

    /** Requests the bilateral endpoint has received for that id. */
    public int bilateralRequestCount(String contractId) {
        return requestCount(BILATERAL_PATH + contractId);
    }

    @Override
    public void close() {
        server.stop();
    }

    private void serveBody(String path, String body) {
        server.stubFor(
                get(urlEqualTo(path))
                        .willReturn(
                                aResponse()
                                        .withStatus(OK)
                                        .withHeader(CONTENT_TYPE, JSON)
                                        .withBody(body)));
    }

    private void stubStatus(String path, int status) {
        server.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(status)));
    }

    private int requestCount(String path) {
        return server.countRequestsMatching(getRequestedFor(urlEqualTo(path)).build()).getCount();
    }
}
