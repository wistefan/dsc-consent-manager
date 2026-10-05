package com.seamware.consentmanager.error;

/**
 * The addressed resource does not exist (HTTP 404).
 *
 * <p>Also the answer when a resource exists but not for this caller - a participant asking about a
 * user it is not linked to gets 404 rather than 403, because 403 would confirm the identifier.
 */
public class NotFoundException extends ApiException {

    /** Creates a not-found whose message is published to the caller. */
    public NotFoundException(String message) {
        super(ProblemType.NOT_FOUND, message);
    }

    /** Creates a not-found from an underlying lookup failure. */
    public NotFoundException(String message, Throwable cause) {
        super(ProblemType.NOT_FOUND, message, cause);
    }
}
