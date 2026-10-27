package com.seamware.consentmanager.error;

/**
 * A service this request depends on failed or was unreachable (HTTP 502). The message is logged,
 * not published: it names an upstream the caller has no business learning about.
 */
public class UpstreamServiceException extends ApiException {

    /** Creates an upstream failure; the message is logged, not returned to the caller. */
    public UpstreamServiceException(String message) {
        super(ProblemType.UPSTREAM_SERVICE, message);
    }

    /** Creates an upstream failure from the underlying transport or protocol error. */
    public UpstreamServiceException(String message, Throwable cause) {
        super(ProblemType.UPSTREAM_SERVICE, message, cause);
    }
}
