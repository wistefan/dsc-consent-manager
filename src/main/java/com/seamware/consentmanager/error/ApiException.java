package com.seamware.consentmanager.error;

/**
 * Base for a service's refusal, carrying the problem type it renders as. A 4xx message reaches the
 * caller verbatim, so it must name nothing internal; a 5xx message is logged instead.
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
