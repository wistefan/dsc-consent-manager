package com.seamware.consentmanager.client;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.retry.annotation.RetryPredicate;

/**
 * Decides which upstream failure is worth another attempt: a 5xx or a transport failure, nothing
 * else.
 *
 * <p>A 4xx is a defect in the request that retrying cannot fix, so retrying one would only multiply
 * the load on an upstream that already refused. A 404 never reaches this predicate at all - the
 * declarative client turns it into an empty {@code Optional}, which is the "no such contract"
 * answer rather than a failure.
 *
 * <p>Instantiated by the retry interceptor, so it must keep its public no-argument constructor and
 * stay free of injected state.
 */
public final class UpstreamRetryPredicate implements RetryPredicate {

    /** Lowest status code that means the upstream failed rather than refused. */
    private static final int LOWEST_SERVER_ERROR = HttpStatus.INTERNAL_SERVER_ERROR.getCode();

    @Override
    public boolean test(Throwable throwable) {
        if (throwable instanceof HttpClientResponseException response) {
            return response.getStatus().getCode() >= LOWEST_SERVER_ERROR;
        }
        // Covers the connect and read timeouts and every other transport-level failure, all of
        // which are subclasses of HttpClientException and all of which a later attempt may survive.
        return throwable instanceof HttpClientException;
    }
}
