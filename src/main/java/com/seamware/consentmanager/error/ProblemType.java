package com.seamware.consentmanager.error;

import com.seamware.consentmanager.api.generated.model.ProblemDetail;
import io.micronaut.http.HttpStatus;
import java.net.URI;

/**
 * The RFC 7807 problem types this service mints, each pinned to the status and title it renders
 * with, so one type URI can never appear under two statuses across the handlers that emit it.
 *
 * <p>The type URIs are a published part of the API contract: clients branch on them, so a slug is
 * as breaking to rename as a path.
 */
public enum ProblemType {

    /** The request was syntactically or semantically unusable, including validation failures. */
    BAD_REQUEST("bad-request", "Bad Request", HttpStatus.BAD_REQUEST),

    /** The caller authenticated but may not perform the operation. */
    FORBIDDEN("forbidden", "Forbidden", HttpStatus.FORBIDDEN),

    /** The addressed resource does not exist, or does not exist for this caller. */
    NOT_FOUND("not-found", "Not Found", HttpStatus.NOT_FOUND),

    /** The request conflicts with the current state of the resource. */
    CONFLICT("conflict", "Conflict", HttpStatus.CONFLICT),

    /** The request carried no usable bearer token. */
    UNAUTHORIZED("unauthorized", "Unauthorized", HttpStatus.UNAUTHORIZED),

    /** A service this request depends on failed or was unreachable. */
    UPSTREAM_SERVICE("upstream-service", "Bad Gateway", HttpStatus.BAD_GATEWAY);

    /** RFC 7807 media type every problem detail in this service is rendered as. */
    public static final String MEDIA_TYPE = "application/problem+json";

    /** Namespace every problem type URI in this service is minted under. */
    private static final String TYPE_PREFIX = "https://consent-manager.example/problems/";

    private final URI type;

    private final String title;

    private final HttpStatus status;

    ProblemType(String slug, String title, HttpStatus status) {
        this.type = URI.create(TYPE_PREFIX + slug);
        this.title = title;
        this.status = status;
    }

    /** The {@code type} URI clients branch on. */
    public URI type() {
        return type;
    }

    /** The {@code title}, constant per type by RFC 7807. */
    public String title() {
        return title;
    }

    /** The status this type is always rendered with. */
    public HttpStatus status() {
        return status;
    }

    /**
     * Renders one occurrence of this problem.
     *
     * <p>{@code detail} is published to the caller verbatim and must therefore name nothing
     * internal; {@code instance} is the request path the failure occurred on.
     */
    public ProblemDetail toProblemDetail(String detail, String instance) {
        return new ProblemDetail(type, title, status.getCode())
                .detail(detail)
                .instance(URI.create(instance));
    }
}
