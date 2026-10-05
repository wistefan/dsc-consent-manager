package com.seamware.consentmanager.error;

/**
 * The caller authenticated but may not perform the operation (HTTP 403).
 *
 * <p>For a refusal that would confirm the existence of a resource the caller may not see, throw
 * {@link NotFoundException} instead.
 */
public class ForbiddenException extends ApiException {

    /** Creates a refusal whose message is published to the caller. */
    public ForbiddenException(String message) {
        super(ProblemType.FORBIDDEN, message);
    }

    /** Creates a refusal from an underlying authorization failure. */
    public ForbiddenException(String message, Throwable cause) {
        super(ProblemType.FORBIDDEN, message, cause);
    }
}
