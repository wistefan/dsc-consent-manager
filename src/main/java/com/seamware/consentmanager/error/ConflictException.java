package com.seamware.consentmanager.error;

/**
 * The request conflicts with the current state of the resource (HTTP 409).
 *
 * <p>Thrown where a uniqueness constraint or a lifecycle rule refuses an otherwise well-formed
 * request, such as linking a user to a participant it is already linked to.
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
