package com.seamware.consentmanager.error;

/**
 * Exception indicating that an upstream service returned an error (HTTP 502).
 *
 * <p>Thrown when an external service dependency (e.g., Contract Service,
 * Identity Provider) is unreachable or returns an unexpected error. The
 * {@link GlobalExceptionHandler} maps this exception to an HTTP 502
 * Bad Gateway response with an {@link ApiError} body.
 */
public class UpstreamServiceException extends RuntimeException {

    /**
     * Creates an {@code UpstreamServiceException} with the specified detail message.
     *
     * @param message a human-readable description of the upstream failure
     */
    public UpstreamServiceException(String message) {
        super(message);
    }

    /**
     * Creates an {@code UpstreamServiceException} with the specified detail
     * message and cause.
     *
     * @param message a human-readable description of the upstream failure
     * @param cause   the underlying cause of this exception
     */
    public UpstreamServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
