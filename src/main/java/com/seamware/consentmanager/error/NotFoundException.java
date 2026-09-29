package com.seamware.consentmanager.error;

/**
 * Exception indicating that the requested resource was not found (HTTP 404).
 *
 * <p>Thrown when a lookup by identifier (e.g., consent ID) yields no result. The {@link
 * GlobalExceptionHandler} maps this exception to an HTTP 404 Not Found response with a {@link
 * com.seamware.consentmanager.api.generated.model.ProblemDetail} body.
 */
public class NotFoundException extends RuntimeException {

    /**
     * Creates a {@code NotFoundException} with the specified detail message.
     *
     * @param message a human-readable description of what was not found
     */
    public NotFoundException(String message) {
        super(message);
    }

    /**
     * Creates a {@code NotFoundException} with the specified detail message and cause.
     *
     * @param message a human-readable description of what was not found
     * @param cause the underlying cause of this exception
     */
    public NotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
