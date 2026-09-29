package com.seamware.consentmanager.error;

/**
 * Exception indicating that access to the requested resource is forbidden (HTTP 403).
 *
 * <p>Thrown when the authenticated principal lacks the required permissions or roles to access a
 * resource. The {@link GlobalExceptionHandler} maps this exception to an HTTP 403 Forbidden
 * response with a {@link com.seamware.consentmanager.api.generated.model.ProblemDetail} body.
 */
public class ForbiddenException extends RuntimeException {

    /**
     * Creates a {@code ForbiddenException} with the specified detail message.
     *
     * @param message a human-readable description of the access denial
     */
    public ForbiddenException(String message) {
        super(message);
    }

    /**
     * Creates a {@code ForbiddenException} with the specified detail message and cause.
     *
     * @param message a human-readable description of the access denial
     * @param cause the underlying cause of this exception
     */
    public ForbiddenException(String message, Throwable cause) {
        super(message, cause);
    }
}
