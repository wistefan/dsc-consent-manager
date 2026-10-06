package com.seamware.consentmanager.error;

/**
 * Base for the exceptions a service throws to refuse a request, carrying the problem type it is
 * rendered as so no layer below HTTP has to know about status codes.
 *
 * <p>For a 4xx type the message reaches the caller verbatim as the problem detail, so it must name
 * nothing internal; for a 5xx type {@link ApiExceptionHandler} logs it and publishes a fixed detail
 * instead.
 */
public abstract class ApiException extends RuntimeException {

    private final ProblemType problemType;

    /** Creates an exception rendered as {@code problemType}. */
    protected ApiException(ProblemType problemType, String message) {
        super(message);
        this.problemType = problemType;
    }

    /** Creates an exception rendered as {@code problemType}, retaining the underlying failure. */
    protected ApiException(ProblemType problemType, String message, Throwable cause) {
        super(message, cause);
        this.problemType = problemType;
    }

    /** The problem type, and with it the status and title, this exception renders as. */
    public ProblemType problemType() {
        return problemType;
    }
}
