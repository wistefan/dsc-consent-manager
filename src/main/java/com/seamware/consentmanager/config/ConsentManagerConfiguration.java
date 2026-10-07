package com.seamware.consentmanager.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Nullable;

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
