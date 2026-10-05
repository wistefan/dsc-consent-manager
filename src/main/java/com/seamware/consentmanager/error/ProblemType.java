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

    /** A defect on this side of the connection; its cause is logged and never published. */
    INTERNAL_SERVER_ERROR(
            "internal-server-error", "Internal Server Error", HttpStatus.INTERNAL_SERVER_ERROR),

    /** A service this request depends on failed or was unreachable. */
    UPSTREAM_SERVICE("upstream-service", "Bad Gateway", HttpStatus.BAD_GATEWAY);

    /** RFC 7807 media type every problem detail in this service is rendered as. */
    public static final String MEDIA_TYPE = "application/problem+json";

    /**
     * Detail every server-side failure publishes in place of its own message, which is logged
     * instead so that an internal host, dependency or stack frame never reaches the caller.
     */
    public static final String SERVER_ERROR_DETAIL =
            "An unexpected error occurred. Please try again later.";

    /** Lowest status code that reports a fault on this side of the connection. */
    public static final int LOWEST_SERVER_ERROR_STATUS = 500;

    /** Namespace every problem type URI in this service is minted under. */
    private static final String TYPE_PREFIX = "https://consent-manager.example/problems/";

    /** RFC 7807's "no semantics beyond the status", for statuses this service mints no type for. */
    private static final URI UNTYPED = URI.create("about:blank");

    /**
     * Resolves the type a status is rendered with. Declaring a second type on an already-registered
     * status fails here, at class initialisation, rather than producing two bodies for one status.
     */
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
     * Renders a problem for a status reached without an {@link ApiException} — a framework-level
     * refusal such as a 405 or an unroutable 404.
     *
     * <p>A status no type is registered for falls back to {@code about:blank} under the reason
     * phrase the framework produced, which RFC 7807 defines as carrying no semantics beyond the
     * status itself.
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
     * Renders one occurrence of this problem.
     *
     * <p>{@code detail} is published to the caller verbatim and must therefore name nothing
     * internal; {@code instance} is the request path the failure occurred on.
     */
    public ProblemDetail toProblemDetail(String detail, String instance) {
        return withInstance(
                new ProblemDetail(type, title, status.getCode()).detail(detail), instance);
    }

    /**
     * Sets {@code instance} from a raw request path, escaping it where it is not a URI already.
     *
     * <p>Netty passes the request target through unvalidated, so the path can hold characters a URI
     * may not — {@code |}, {@code {}}, {@code ^}, raw non-ASCII. Throwing on those would turn a
     * deliberate refusal into a rendering failure inside the handler itself, so such a path is
     * escaped, and one that is unusable even then simply loses the member, which is optional. A
     * path that already parses is kept verbatim, so existing percent-encoding is not encoded twice.
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
