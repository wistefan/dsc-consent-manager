package com.seamware.consentmanager.error;

import com.seamware.consentmanager.api.generated.model.ProblemDetail;
import io.micronaut.http.HttpStatus;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The RFC 7807 problem types this service mints, each pinned to one status and title. The type URIs
 * are API contract: renaming a slug is as breaking as renaming a path.
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

    /** A defect on this side of the connection; its cause is logged and never published. */
    INTERNAL_SERVER_ERROR(
            "internal-server-error", "Internal Server Error", HttpStatus.INTERNAL_SERVER_ERROR),

    /** A service this request depends on failed or was unreachable. */
    UPSTREAM_SERVICE("upstream-service", "Bad Gateway", HttpStatus.BAD_GATEWAY);

    /** RFC 7807 media type every problem detail in this service is rendered as. */
    public static final String MEDIA_TYPE = "application/problem+json";

    /**
     * Detail a server-side failure publishes in place of its logged message; only {@link
     * ServerErrorDetail} may substitute the message, and only where configured to.
     */
    public static final String SERVER_ERROR_DETAIL =
            "An unexpected error occurred. Please try again later.";

    /** Lowest status code that reports a fault on this side of the connection. */
    public static final int LOWEST_SERVER_ERROR_STATUS = 500;

    private static final String TYPE_PREFIX = "https://consent-manager.example/problems/";

    /** RFC 7807's "no semantics beyond the status", for statuses this service mints no type for. */
    private static final URI UNTYPED = URI.create("about:blank");

    /** Type per status; a second type on one status fails class initialisation. */
    private static final Map<Integer, ProblemType> BY_STATUS =
            Arrays.stream(values())
                    .collect(
                            Collectors.toUnmodifiableMap(
                                    problemType -> problemType.status.getCode(),
                                    Function.identity()));

    private final URI type;

    private final String title;

    private final HttpStatus status;

    ProblemType(String slug, String title, HttpStatus status) {
        this.type = URI.create(TYPE_PREFIX + slug);
        this.title = title;
        this.status = status;
    }

    /**
     * Renders a problem for a framework-level refusal such as a 405; a status with no registered
     * type falls back to {@code about:blank} under the framework's reason phrase.
     */
    public static ProblemDetail problemFor(
            int status, String reason, String detail, String instance) {
        ProblemType registered = BY_STATUS.get(status);
        return registered != null
                ? registered.toProblemDetail(detail, instance)
                : withInstance(new ProblemDetail(UNTYPED, reason, status).detail(detail), instance);
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
     * Renders one occurrence at request path {@code instance}; {@code detail} is published verbatim
     * and must name nothing internal.
     */
    public ProblemDetail toProblemDetail(String detail, String instance) {
        return withInstance(
                new ProblemDetail(type, title, status.getCode()).detail(detail), instance);
    }

    /**
     * Sets {@code instance} from a raw, unvalidated request path: kept verbatim when it parses,
     * escaped when not, and omitted when unusable even then, so rendering never throws.
     */
    private static ProblemDetail withInstance(ProblemDetail problem, String instance) {
        if (instance == null || instance.isBlank()) {
            return problem;
        }
        try {
            return problem.instance(URI.create(instance));
        } catch (IllegalArgumentException notAUri) {
            try {
                return problem.instance(new URI(null, null, instance, null));
            } catch (URISyntaxException unusable) {
                return problem;
            }
        }
    }
}
