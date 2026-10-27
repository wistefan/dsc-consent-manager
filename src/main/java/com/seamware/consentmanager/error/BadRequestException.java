package com.seamware.consentmanager.error;

/**
 * The request was malformed or semantically invalid beyond what bean validation expresses (400).
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
