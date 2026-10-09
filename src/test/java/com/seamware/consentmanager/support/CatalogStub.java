package com.seamware.consentmanager.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A stubbed catalog, serving the self-descriptions a resolved retrieval walks.
 *
 * <p>Every method returns the absolute URL the document is reachable at, because that is what a
 * stored notice carries and what the test has to put into the payload it seeds; the path is only
 * how the stub recognises the request. Owned per test, so one case's request count cannot be
 * another's.
 */
public final class CatalogStub implements AutoCloseable {

    /** Content type a catalog document is served as. */
    private static final String JSON = "application/json";

    private static final String CONTENT_TYPE = "Content-Type";

    private static final int OK = 200;

    private static final int FOUND = 302;

    private static final String LOCATION = "Location";

    private final WireMockServer server;

    /** Starts the stub on a free port; nothing is stubbed until a {@code serve} call. */
    public CatalogStub() {
        this.server = new WireMockServer(options().dynamicPort());
        this.server.start();
    }

    /**
     * A URL on a port nothing listens on, so reading it is refused at the transport.
     *
     * <p>A stub that is started and immediately stopped, because a hard-coded port might be in use
     * and a connection that is refused has to be refused for the reason the case is about.
     */
    public static String unreachableUrl(String path) {
        WireMockServer dead = new WireMockServer(options().dynamicPort());
        dead.start();
        String url = dead.baseUrl() + path;
        dead.stop();
        return url;
    }

    /** Base URL this catalog is reachable at. */
    public String url() {
        return server.baseUrl();
    }

    /** A service-offering document naming the data resources behind it. */
    public String serveOffering(
            String path, String name, String description, List<String> dataResourceUrls) {
        return serveOffering(path, name, description, dataResourceUrls, Duration.ZERO);
    }

    /** The same document answered late, which is how a walk's deadline is stubbed. */
    public String serveOffering(
            String path,
            String name,
            String description,
            List<String> dataResourceUrls,
            Duration delay) {
        String resources =
                dataResourceUrls.stream()
                        .map(resource -> '"' + resource + '"')
                        .collect(Collectors.joining(",", "[", "]"));
        return serveDocument(
                path,
                """
                {"_id": "%s", "name": "%s", "description": "%s", "dataResources": %s}"""
                        .formatted(path, name, description, resources),
                delay);
    }

    /** A data-resource document, the second hop, which names no resources of its own. */
    public String serveResource(String path, String name, String description) {
        return serveDocument(
                path,
                """
                {"_id": "%s", "name": "%s", "description": "%s"}"""
                        .formatted(path, name, description));
    }

    /** Serves a body verbatim, which is how a document that will not parse is stubbed. */
    public String serveDocument(String path, String body) {
        return serveDocument(path, body, Duration.ZERO);
    }

    /** The same body held back for that long; {@link Duration#ZERO} answers at once. */
    public String serveDocument(String path, String body, Duration delay) {
        server.stubFor(
                get(urlEqualTo(path))
                        .willReturn(
                                aResponse()
                                        .withStatus(OK)
                                        .withHeader(CONTENT_TYPE, JSON)
                                        .withFixedDelay((int) delay.toMillis())
                                        .withBody(body)));
        return url() + path;
    }

    /** Answers that path with a redirect, which is how the guard's bypass case is stubbed. */
    public String serveRedirect(String path, String location) {
        server.stubFor(
                get(urlEqualTo(path))
                        .willReturn(aResponse().withStatus(FOUND).withHeader(LOCATION, location)));
        return url() + path;
    }

    /** Answers that path with a status rather than a document. */
    public String serveStatus(String path, int status) {
        server.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(status)));
        return url() + path;
    }

    /** Holds the request open past the configured read timeout before answering. */
    public String serveSlowly(String path, Duration delay) {
        server.stubFor(
                get(urlEqualTo(path))
                        .willReturn(
                                aResponse()
                                        .withStatus(OK)
                                        .withHeader(CONTENT_TYPE, JSON)
                                        .withFixedDelay((int) delay.toMillis())
                                        .withBody("{}")));
        return url() + path;
    }

    /** Requests this stub has received for that path. */
    public int requestCount(String path) {
        return server.countRequestsMatching(getRequestedFor(urlEqualTo(path)).build()).getCount();
    }

    /** Requests this stub has received at all, which is how "nothing was read" is asserted. */
    public int requestCount() {
        return server.getAllServeEvents().size();
    }

    @Override
    public void close() {
        server.stop();
    }
}
