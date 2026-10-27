package com.seamware.consentmanager.client;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.retry.annotation.RetryPredicate;

/**
 * Retries a 5xx or a transport failure only; a 4xx is a request defect retrying cannot fix.
 * Instantiated by the retry interceptor: keep the public no-arg constructor and no injected state.
 */
public final class UpstreamRetryPredicate implements RetryPredicate {

    /** Lowest status code that means the upstream failed rather than refused. */
    private static final int LOWEST_SERVER_ERROR = HttpStatus.INTERNAL_SERVER_ERROR.getCode();

    @Override
    public boolean test(Throwable throwable) {
        if (throwable instanceof HttpClientResponseException response) {
            return response.getStatus().getCode() >= LOWEST_SERVER_ERROR;
        }
        // Timeouts and other transport failures, which a later attempt may survive.
        return throwable instanceof HttpClientException;
    }
}
