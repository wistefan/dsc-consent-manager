package com.seamware.consentmanager.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Nullable;
import java.time.Duration;

/**
 * Root configuration properties for the Consent Manager application.
 *
 * <p>Binds properties under the {@code consent-manager} prefix from {@code application.yml} (or
 * environment variables) into typed Java fields.
 *
 * <p>Environment variable mapping:
 *
 * <ul>
 *   <li>{@code CONSENT_MANAGER_URL} &rarr; {@link #getUrl()}
 *   <li>{@code CONTRACT_SERVICE_URL} &rarr; {@link ContractService#getUrl()}
 *   <li>{@code USERS_SEARCH_MAX_RESULTS} &rarr; {@link Users#getSearchMaxResults()}
 *   <li>{@code PARTICIPANTS_PAGE_DEFAULT_SIZE} &rarr; {@link Participants#getPageDefaultSize()}
 *   <li>{@code PARTICIPANTS_PAGE_MAX_SIZE} &rarr; {@link Participants#getPageMaxSize()}
 * </ul>
 *
 * @see <a href="https://docs.micronaut.io/latest/guide/#configurationProperties">Micronaut
 *     Configuration Properties</a>
 */
@ConfigurationProperties("consent-manager")
public class ConsentManagerConfiguration {

    private String url;

    /**
     * Returns the public base URL of this Consent Manager instance.
     *
     * <p>Bound to the {@code CONSENT_MANAGER_URL} environment variable via the {@code
     * consent-manager.url} configuration key.
     *
     * @return the public base URL (default provided via {@code application.yml})
     */
    public String getUrl() {
        return url;
    }

    /**
     * Sets the public base URL of this Consent Manager instance.
     *
     * @param url the public base URL
     */
    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * The Contract Service this instance reads contracts from, bound under {@code
     * consent-manager.contract-service}.
     *
     * <p>These keys are the single place each setting is named. {@code application.yml} forwards
     * them into the keys the framework reads - {@code micronaut.http.services.contract-service} for
     * the base URL and the two timeouts, {@code micronaut.caches.contracts} for the cache lifetime
     * - and the retry settings are resolved from them by the {@code @Retryable} placeholders on
     * {@code ContractServiceClient}. The getters here type and document that contract in one place;
     * only {@link #getUrl()} is read from Java, where a failure has to name the upstream it failed
     * against.
     */
    @ConfigurationProperties("contract-service")
    public static class ContractService {

        /** Longest a connection attempt may take when none is configured. */
        public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);

        /** Longest a response may take to arrive when none is configured. */
        public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(5);

        /** Retries attempted after a failed fetch, when none is configured. */
        public static final int DEFAULT_RETRY_ATTEMPTS = 3;

        /** Pause before the first retry when none is configured. */
        public static final Duration DEFAULT_RETRY_DELAY = Duration.ofMillis(500);

        /** Factor each further retry delay is multiplied by when none is configured. */
        public static final double DEFAULT_RETRY_MULTIPLIER = 2.0;

        /** Longest a fetched contract is served from the cache when none is configured. */
        public static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(60);

        private String url;

        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;

        private Duration readTimeout = DEFAULT_READ_TIMEOUT;

        private int retryAttempts = DEFAULT_RETRY_ATTEMPTS;

        private Duration retryDelay = DEFAULT_RETRY_DELAY;

        private double retryMultiplier = DEFAULT_RETRY_MULTIPLIER;

        private Duration cacheTtl = DEFAULT_CACHE_TTL;

        /** Base URL of the Contract Service, bound to {@code CONTRACT_SERVICE_URL}. */
        public String getUrl() {
            return url;
        }

        /** Sets the base URL; the declarative client resolves it at startup. */
        public void setUrl(String url) {
            this.url = url;
        }

        /**
         * Longest a connection attempt may take, bound to {@code CONTRACT_SERVICE_CONNECT_TIMEOUT}.
         */
        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        /** Sets the connect timeout; exceeding it is a retryable failure, not a bad contract. */
        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        /**
         * Longest a response may take to arrive, bound to {@code CONTRACT_SERVICE_READ_TIMEOUT}.
         */
        public Duration getReadTimeout() {
            return readTimeout;
        }

        /** Sets the read timeout; it bounds how long a generation request can be held open. */
        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        /**
         * Retries after a failed fetch, bound to {@code CONTRACT_SERVICE_RETRY_ATTEMPTS}.
         *
         * <p>Counted after the initial attempt, as {@code @Retryable} does, so three retries are at
         * most four upstream requests. Only a 5xx or a transport failure is retried; a 4xx is a
         * defect retrying cannot fix and would only multiply the load on an upstream that already
         * refused.
         */
        public int getRetryAttempts() {
            return retryAttempts;
        }

        /** Sets the retry budget; zero makes a failed fetch final. */
        public void setRetryAttempts(int retryAttempts) {
            this.retryAttempts = retryAttempts;
        }

        /** Pause before the first retry, bound to {@code CONTRACT_SERVICE_RETRY_DELAY}. */
        public Duration getRetryDelay() {
            return retryDelay;
        }

        /** Sets the first retry delay; together with the multiplier it bounds the total wait. */
        public void setRetryDelay(Duration retryDelay) {
            this.retryDelay = retryDelay;
        }

        /**
         * Factor each further retry delay grows by, bound to {@code
         * CONTRACT_SERVICE_RETRY_MULTIPLIER}.
         */
        public double getRetryMultiplier() {
            return retryMultiplier;
        }

        /** Sets the backoff factor; one makes every retry wait the same delay. */
        public void setRetryMultiplier(double retryMultiplier) {
            this.retryMultiplier = retryMultiplier;
        }

        /**
         * Longest a fetched contract is served from the cache, bound to {@code
         * CONTRACT_SERVICE_CACHE_TTL}.
         *
         * <p>Not a latency knob: a notice is generated from the cached document, so this is how
         * stale the contract behind a freshly published notice may be, and how long a contract
         * change can go unnoticed by a repeated generation. Keep it short.
         */
        public Duration getCacheTtl() {
            return cacheTtl;
        }

        /** Sets the contract cache lifetime; longer trades contract freshness for fewer calls. */
        public void setCacheTtl(Duration cacheTtl) {
            this.cacheTtl = cacheTtl;
        }
    }

    /** Settings of the erasure path, bound under {@code consent-manager.erasure}. */
    @ConfigurationProperties("erasure")
    public static class Erasure {

        private String verificationSecret;

        /**
         * Key of the HMAC an erased record carries instead of its identifier, bound to {@code
         * ERASURE_VERIFICATION_SECRET}.
         *
         * <p>Held by the operator, never by a participant and never in the database, so that only
         * the operator can confirm a candidate identifier against an erased record. Blank or unset
         * - the default - means erased records carry no verifier at all, and nothing can be
         * confirmed against them ever again.
         */
        @Nullable
        public String getVerificationSecret() {
            return verificationSecret;
        }

        /** Sets the key; changing it abandons every verifier written under the previous one. */
        public void setVerificationSecret(@Nullable String verificationSecret) {
            this.verificationSecret = verificationSecret;
        }
    }

    /** Limits on the user directory, bound under {@code consent-manager.users}. */
    @ConfigurationProperties("users")
    public static class Users {

        /** Cap applied when none is configured; neither search nor lookup is paginated. */
        public static final int DEFAULT_SEARCH_MAX_RESULTS = 200;

        /** Entries one bulk registration may carry when none is configured. */
        public static final int DEFAULT_BULK_MAX_SIZE = 500;

        private int searchMaxResults = DEFAULT_SEARCH_MAX_RESULTS;

        private int bulkMaxSize = DEFAULT_BULK_MAX_SIZE;

        /**
         * Most users one search may return, bound to {@code USERS_SEARCH_MAX_RESULTS}.
         *
         * <p>The result is sorted by identifier before the cap applies, so truncation is
         * deterministic rather than whatever the database happened to return first.
         */
        public int getSearchMaxResults() {
            return searchMaxResults;
        }

        /** Sets the cap; a value below one would make every search answer empty. */
        public void setSearchMaxResults(int searchMaxResults) {
            this.searchMaxResults = searchMaxResults;
        }

        /**
         * Most entries one bulk registration may carry, bound to {@code USERS_BULK_MAX_SIZE}.
         *
         * <p>A larger batch is refused whole with {@code 400} before anything is written, since
         * entries are applied one at a time and an unbounded batch would hold a request open for as
         * long as the client cared to make it.
         */
        public int getBulkMaxSize() {
            return bulkMaxSize;
        }

        /** Sets the cap; a value below one would make every bulk registration a bad request. */
        public void setBulkMaxSize(int bulkMaxSize) {
            this.bulkMaxSize = bulkMaxSize;
        }
    }

    /** Paging of the participant directory, bound under {@code consent-manager.participants}. */
    @ConfigurationProperties("participants")
    public static class Participants {

        /** Records a directory page carries when the caller asks for no size. */
        public static final int DEFAULT_PAGE_DEFAULT_SIZE = 20;

        /** Most records a directory page may carry when no ceiling is configured. */
        public static final int DEFAULT_PAGE_MAX_SIZE = 100;

        private int pageDefaultSize = DEFAULT_PAGE_DEFAULT_SIZE;

        private int pageMaxSize = DEFAULT_PAGE_MAX_SIZE;

        /**
         * Page size applied when {@code GET /participants} names none, bound to {@code
         * PARTICIPANTS_PAGE_DEFAULT_SIZE}.
         *
         * <p>Deliberately absent from the specification, which could not track a value configured
         * per deployment; the response reports the size it applied instead.
         */
        public int getPageDefaultSize() {
            return pageDefaultSize;
        }

        /** Sets the default; a value below one would make every page answer empty. */
        public void setPageDefaultSize(int pageDefaultSize) {
            this.pageDefaultSize = pageDefaultSize;
        }

        /**
         * Most records one directory page may carry, bound to {@code PARTICIPANTS_PAGE_MAX_SIZE}.
         *
         * <p>A larger requested size is clamped to this rather than refused, so raising or lowering
         * the ceiling never turns a caller's working request into a {@code 400}.
         */
        public int getPageMaxSize() {
            return pageMaxSize;
        }

        /** Sets the ceiling; it also caps the default, since the clamp applies to that too. */
        public void setPageMaxSize(int pageMaxSize) {
            this.pageMaxSize = pageMaxSize;
        }
    }
}
