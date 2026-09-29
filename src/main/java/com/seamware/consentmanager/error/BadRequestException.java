package com.seamware.consentmanager.error;

/**
 * Exception indicating that the client request was malformed or invalid (HTTP 400).
 *
 * <p>Thrown when request validation fails, required parameters are missing,
 * or the request body cannot be parsed. The {@link GlobalExceptionHandler}
 * maps this exception to an HTTP 400 Bad Request response with a
 * {@link com.seamware.consentmanager.api.generated.model.ProblemDetail} body.
 */
public class BadRequestException extends RuntimeException {

    /**
     * Creates a {@code BadRequestException} with the specified detail message.
     *
     * @param message a human-readable description of the validation failure
     */
    public BadRequestException(String message) {
        super(message);
    }

    /**
     * Creates a {@code BadRequestException} with the specified detail message
     * and cause.
     *
     * @param message a human-readable description of the validation failure
     * @param cause   the underlying cause of this exception
     */
    public BadRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
