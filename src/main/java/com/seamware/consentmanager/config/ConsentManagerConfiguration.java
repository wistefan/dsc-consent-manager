package com.seamware.consentmanager.config;

import io.micronaut.context.annotation.ConfigurationProperties;

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
 *   <li>{@code CONTRACT_SERVICE_URL} &rarr; {@link #getContractServiceUrl()}
 *   <li>{@code USERS_SEARCH_MAX_RESULTS} &rarr; {@link Users#getSearchMaxResults()}
 * </ul>
 *
 * @see <a href="https://docs.micronaut.io/latest/guide/#configurationProperties">Micronaut
 *     Configuration Properties</a>
 */
@ConfigurationProperties("consent-manager")
public class ConsentManagerConfiguration {

    private String url;
    private String contractServiceUrl;

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
     * Returns the base URL of the Contract Service.
     *
     * <p>Bound to the {@code CONTRACT_SERVICE_URL} environment variable via the {@code
     * consent-manager.contract-service-url} configuration key.
     *
     * @return the Contract Service base URL (default provided via {@code application.yml})
     */
    public String getContractServiceUrl() {
        return contractServiceUrl;
    }

    /**
     * Sets the base URL of the Contract Service.
     *
     * @param contractServiceUrl the Contract Service base URL
     */
    public void setContractServiceUrl(String contractServiceUrl) {
        this.contractServiceUrl = contractServiceUrl;
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
}
