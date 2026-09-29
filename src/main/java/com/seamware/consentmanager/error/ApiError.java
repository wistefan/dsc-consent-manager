package com.seamware.consentmanager.error;

import io.micronaut.serde.annotation.Serdeable;
import java.net.URI;

/**
 * RFC 7807 Problem Details representation for HTTP API error responses.
 *
 * <p>This record models the standard fields defined in
 * <a href="https://www.rfc-editor.org/rfc/rfc7807">RFC 7807</a>:
 * {@code type}, {@code title}, {@code status}, {@code detail}, and
 * {@code instance}. It is used as the response body for all error responses
 * returned by the Consent Manager API.
 *
 * <p>Instances are created via the {@link #of(URI, String, int, String, URI)}
 * factory method or the convenience {@link #of(String, int, String)} method.
 *
 * @param type     a URI reference identifying the problem type
 * @param title    a short, human-readable summary of the problem type
 * @param status   the HTTP status code for this occurrence
 * @param detail   a human-readable explanation specific to this occurrence
 * @param instance a URI reference identifying the specific occurrence
 */
@Serdeable
public record ApiError(
        URI type,
        String title,
        int status,
        String detail,
        URI instance) {

    /** Default problem type URI used when no specific type is applicable. */
    public static final URI DEFAULT_TYPE = URI.create("about:blank");

    /** HTTP status code for Bad Request (400). */
    public static final int STATUS_BAD_REQUEST = 400;

    /** HTTP status code for Forbidden (403). */
    public static final int STATUS_FORBIDDEN = 403;

    /** HTTP status code for Not Found (404). */
    public static final int STATUS_NOT_FOUND = 404;

    /** HTTP status code for Internal Server Error (500). */
    public static final int STATUS_INTERNAL_SERVER_ERROR = 500;

    /** HTTP status code for Bad Gateway (502). */
    public static final int STATUS_BAD_GATEWAY = 502;

    /**
     * Creates an {@code ApiError} with all RFC 7807 fields.
     *
     * @param type     a URI reference identifying the problem type
     * @param title    a short, human-readable summary of the problem type
     * @param status   the HTTP status code
     * @param detail   a human-readable explanation specific to this occurrence
     * @param instance a URI reference identifying the specific occurrence
     * @return a new {@code ApiError} instance
     */
    public static ApiError of(URI type, String title, int status, String detail, URI instance) {
        return new ApiError(type, title, status, detail, instance);
    }

    /**
     * Creates an {@code ApiError} with sensible defaults for {@code type}
     * ({@code about:blank}) and {@code instance} ({@code null}).
     *
     * @param title  a short, human-readable summary of the problem type
     * @param status the HTTP status code
     * @param detail a human-readable explanation specific to this occurrence
     * @return a new {@code ApiError} instance with default type and no instance
     */
    public static ApiError of(String title, int status, String detail) {
        return new ApiError(DEFAULT_TYPE, title, status, detail, null);
    }
}
