package com.seamware.consentmanager.error;

/**
 * The addressed resource does not exist (HTTP 404), or exists but not for this caller: answering
 * 403 there would confirm the identifier.
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
