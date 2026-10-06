package com.seamware.consentmanager.error;

/**
 * The client request was malformed or semantically invalid (HTTP 400).
 *
 * <p>Thrown where request validation that bean validation cannot express fails, or where a required
 * parameter is missing or unparseable.
 */
public class BadRequestException extends ApiException {

    /** Creates a bad request whose message is published to the caller. */
    public BadRequestException(String message) {
        super(ProblemType.BAD_REQUEST, message);
    }

    /** Creates a bad request from an underlying parse or validation failure. */
    public BadRequestException(String message, Throwable cause) {
        super(ProblemType.BAD_REQUEST, message, cause);
    }
}
