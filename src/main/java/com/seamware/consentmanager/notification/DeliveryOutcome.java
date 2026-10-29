package com.seamware.consentmanager.notification;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;

/**
 * What one delivery attempt came to. {@code reason} is a short classification safe to store and
 * log, never a response body; it is null only for {@link Result#DELIVERED}.
 */
public record DeliveryOutcome(@NonNull Result result, @Nullable String reason) {

    /** How the dispatcher treats the row afterwards. */
    public enum Result {
        /** Accepted with a 2xx; terminal. */
        DELIVERED,
        /** Transient failure; the row is retried on the backoff schedule. */
        RETRY,
        /** Permanent failure; the row is terminal. */
        ABANDON
    }

    /** Lowest and highest code of each status class the classification distinguishes. */
    static final int SUCCESS_MIN = 200;

    static final int SUCCESS_MAX = 299;

    static final int REDIRECT_MIN = 300;

    static final int REDIRECT_MAX = 399;

    static final int SERVER_ERROR_MIN = 500;

    static final int SERVER_ERROR_MAX = 599;

    /** Client errors that say "try again later" rather than "this will never work". */
    static final int REQUEST_TIMEOUT = 408;

    static final int TOO_MANY_REQUESTS = 429;

    private static final String HTTP_REASON = "HTTP %d";

    private static final DeliveryOutcome DELIVERED_OUTCOME =
            new DeliveryOutcome(Result.DELIVERED, null);

    /** A 2xx. */
    public static DeliveryOutcome delivered() {
        return DELIVERED_OUTCOME;
    }

    /** A transient failure. */
    public static DeliveryOutcome retry(@NonNull String reason) {
        return new DeliveryOutcome(Result.RETRY, reason);
    }

    /** A permanent failure. */
    public static DeliveryOutcome abandon(@NonNull String reason) {
        return new DeliveryOutcome(Result.ABANDON, reason);
    }

    /**
     * Classifies a received status: 2xx delivers; 408, 429 and 5xx retry; 3xx (redirects are never
     * followed) and every other code abandon.
     */
    public static DeliveryOutcome forStatus(int code) {
        if (code >= SUCCESS_MIN && code <= SUCCESS_MAX) {
            return delivered();
        }
        String reason = HTTP_REASON.formatted(code);
        boolean transientFailure =
                code == REQUEST_TIMEOUT
                        || code == TOO_MANY_REQUESTS
                        || (code >= SERVER_ERROR_MIN && code <= SERVER_ERROR_MAX);
        return transientFailure ? retry(reason) : abandon(reason);
    }
}
