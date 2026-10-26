package com.seamware.consentmanager.error;

/**
 * The request conflicts with the resource's current state (HTTP 409), e.g. a uniqueness constraint
 * or lifecycle rule refuses an otherwise well-formed request.
 */
public class ConflictException extends ApiException {

    /** Creates a conflict whose message is published to the caller. */
    public ConflictException(String message) {
        super(ProblemType.CONFLICT, message);
    }

    /** Creates a conflict from an underlying failure, typically a constraint violation. */
    public ConflictException(String message, Throwable cause) {
        super(ProblemType.CONFLICT, message, cause);
    }
}
