package com.seamware.consentmanager.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Nullable;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * Typed {@code consent-manager.*} settings; each is documented once, on its Lombok-backed field.
 * Lombok must precede {@code micronaut-inject-java} in {@code annotationProcessorPaths}.
 */
@Getter
@Setter
@ConfigurationProperties("consent-manager")
public class ConsentManagerConfiguration {

    /** Public base URL, bound to {@code CONSENT_MANAGER_URL}; defaulted in application.yml. */
    private String url;

    /**
     * The upstream Contract Service, bound under {@code consent-manager.contract-service}. {@code
     * application.yml} forwards these keys to the framework's HTTP, cache and retry settings; only
     * the URL is read from Java, to name the upstream in a failure.
     */
    @Getter
    @Setter
    @ConfigurationProperties("contract-service")
    public static class ContractService {

        /** Default connection-attempt timeout. */
        public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);

        /** Default per-attempt response timeout. */
        public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(5);

        /** Default retries after a failed fetch. */
        public static final int DEFAULT_RETRY_ATTEMPTS = 3;

        /** Default pause before the first retry. */
        public static final Duration DEFAULT_RETRY_DELAY = Duration.ofMillis(500);

        /** Default factor each further retry delay is multiplied by. */
        public static final double DEFAULT_RETRY_MULTIPLIER = 2.0;

        /** Default lifetime of a cached contract. */
        public static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(60);

        /** Base URL, bound to {@code CONTRACT_SERVICE_URL}. */
        private String url;

        /** Connect timeout, bound to {@code CONTRACT_SERVICE_CONNECT_TIMEOUT}; retryable. */
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;

        /**
         * Per-attempt response timeout, bound to {@code CONTRACT_SERVICE_READ_TIMEOUT}. A fetch may
         * take {@code (retry-attempts + 1) x read-timeout} plus pauses (~23.5s at defaults), twice
         * that when the bilateral endpoint is tried after the ecosystem one.
         */
        private Duration readTimeout = DEFAULT_READ_TIMEOUT;

        /**
         * Retries after the initial attempt, bound to {@code CONTRACT_SERVICE_RETRY_ATTEMPTS}; at
         * least one, as {@code @Retryable} requires. Only a 5xx or transport failure is retried.
         */
        private int retryAttempts = DEFAULT_RETRY_ATTEMPTS;

        /** Pause before the first retry, bound to {@code CONTRACT_SERVICE_RETRY_DELAY}. */
        private Duration retryDelay = DEFAULT_RETRY_DELAY;

        /** Retry-delay growth factor, bound to {@code CONTRACT_SERVICE_RETRY_MULTIPLIER}. */
        private double retryMultiplier = DEFAULT_RETRY_MULTIPLIER;

        /**
         * Contract cache lifetime, bound to {@code CONTRACT_SERVICE_CACHE_TTL}; it bounds how stale
         * the contract behind a freshly generated notice may be, so keep it short.
         */
        private Duration cacheTtl = DEFAULT_CACHE_TTL;
    }

    /**
     * Fetching of catalog self-descriptions a contract names, bound under {@code
     * consent-manager.self-description}. The URLs come from foreign documents, so the host guards
     * here keep it from becoming an open proxy; timeouts are shared with the Contract Service.
     */
    @Getter
    @Setter
    @ConfigurationProperties("self-description")
    public static class SelfDescription {

        /** Default lifetime of a cached self-description. */
        public static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(10);

        /** Default ceiling on documents one resolved retrieval reads. */
        public static final int DEFAULT_MAX_DOCUMENTS = 50;

        /** Default ceiling on time one resolved retrieval spends walking the catalog. */
        public static final Duration DEFAULT_MAX_DURATION = Duration.ofSeconds(15);

        /**
         * Hosts a self-description may be fetched from, bound to {@code
         * SELF_DESCRIPTION_ALLOWED_HOSTS}; empty admits any non-private host. A listed host is
         * exempt from the loopback, link-local and private-range refusal.
         */
        private List<String> allowedHosts = List.of();

        /**
         * Documents one resolved retrieval reads before reporting the data section unavailable,
         * bound to {@code SELF_DESCRIPTION_MAX_DOCUMENTS}; caps fan-out over foreign lists.
         */
        private int maxDocuments = DEFAULT_MAX_DOCUMENTS;

        /**
         * Time one resolved retrieval may walk the catalog before reporting the data section
         * unavailable, bound to {@code SELF_DESCRIPTION_MAX_DURATION}; complements max-documents.
         */
        private Duration maxDuration = DEFAULT_MAX_DURATION;

        /** Whether plain {@code http} is allowed; {@code SELF_DESCRIPTION_ALLOW_PLAINTEXT_HTTP}. */
        private boolean allowPlaintextHttp;

        /** Self-description cache lifetime, bound to {@code SELF_DESCRIPTION_CACHE_TTL}. */
        private Duration cacheTtl = DEFAULT_CACHE_TTL;

        /**
         * Sets the allow-list, dropping blank entries; an unset variable binds as one blank entry,
         * which would otherwise make the list non-empty and refuse every URL.
         */
        public void setAllowedHosts(@Nullable List<String> allowedHosts) {
            this.allowedHosts =
                    allowedHosts == null
                            ? List.of()
                            : allowedHosts.stream()
                                    .filter(host -> host != null && !host.isBlank())
                                    .map(String::trim)
                                    .toList();
        }
    }

    /** Settings of the erasure path, bound under {@code consent-manager.erasure}. */
    @Getter
    @Setter
    @ConfigurationProperties("erasure")
    public static class Erasure {

        /**
         * Operator-held HMAC key for an erased record's verifier, bound to {@code
         * ERASURE_VERIFICATION_SECRET}; blank means no verifier is written, and changing it
         * abandons every verifier written under the previous one. See ADR-0007.
         */
        @Nullable private String verificationSecret;
    }

    /** Limits on the user directory, bound under {@code consent-manager.users}. */
    @Getter
    @Setter
    @ConfigurationProperties("users")
    public static class Users {

        /** Default search cap; neither search nor lookup is paginated. */
        public static final int DEFAULT_SEARCH_MAX_RESULTS = 200;

        /** Default ceiling on entries in one bulk registration. */
        public static final int DEFAULT_BULK_MAX_SIZE = 500;

        /**
         * Most users one search returns, bound to {@code USERS_SEARCH_MAX_RESULTS}; at least one.
         * Results are sorted by identifier before the cap, so truncation is deterministic.
         */
        private int searchMaxResults = DEFAULT_SEARCH_MAX_RESULTS;

        /**
         * Most entries one bulk registration carries, bound to {@code USERS_BULK_MAX_SIZE}; at
         * least one. A larger batch is refused whole with {@code 400} before anything is written.
         */
        private int bulkMaxSize = DEFAULT_BULK_MAX_SIZE;
    }

    /** Paging of the participant directory, bound under {@code consent-manager.participants}. */
    @Getter
    @Setter
    @ConfigurationProperties("participants")
    public static class Participants {

        /** Default page size when the caller names none. */
        public static final int DEFAULT_PAGE_DEFAULT_SIZE = 20;

        /** Default page-size ceiling. */
        public static final int DEFAULT_PAGE_MAX_SIZE = 100;

        /**
         * Page size when {@code GET /participants} names none, bound to {@code
         * PARTICIPANTS_PAGE_DEFAULT_SIZE}; at least one, and absent from the spec by design.
         */
        private int pageDefaultSize = DEFAULT_PAGE_DEFAULT_SIZE;

        /**
         * Page-size ceiling, bound to {@code PARTICIPANTS_PAGE_MAX_SIZE}; a larger size, the
         * default included, is clamped rather than refused.
         */
        private int pageMaxSize = DEFAULT_PAGE_MAX_SIZE;
    }

    /**
     * Paging and lineage depth of consent queries, bound under {@code consent-manager.consents}.
     */
    @Getter
    @Setter
    @ConfigurationProperties("consents")
    public static class Consents {

        /** Default page size when the caller names none. */
        public static final int DEFAULT_PAGE_DEFAULT_SIZE = 20;

        /** Default page-size ceiling. */
        public static final int DEFAULT_PAGE_MAX_SIZE = 100;

        /** Default ceiling on superseded records one lineage carries. */
        public static final int DEFAULT_LINEAGE_MAX_DEPTH = 20;

        /**
         * Page size when {@code GET /consents/me} names none, bound to {@code
         * CONSENTS_PAGE_DEFAULT_SIZE}; at least one, and absent from the spec by design.
         */
        private int pageDefaultSize = DEFAULT_PAGE_DEFAULT_SIZE;

        /**
         * Page-size ceiling, bound to {@code CONSENTS_PAGE_MAX_SIZE}; a larger size, the default
         * included, is clamped rather than refused.
         */
        private int pageMaxSize = DEFAULT_PAGE_MAX_SIZE;

        /**
         * Most superseded records one {@code includeLineage} walk reads, bound to {@code
         * CONSENTS_LINEAGE_MAX_DEPTH}; bounds rows read, not just output.
         */
        private int lineageMaxDepth = DEFAULT_LINEAGE_MAX_DEPTH;
    }

    /**
     * Bounds on one contract-discovery listing, bound under {@code
     * consent-manager.contract-discovery}.
     */
    @Getter
    @Setter
    @ConfigurationProperties("contract-discovery")
    public static class ContractDiscovery {

        /** Default number of concurrent Contract Service reads per listing. */
        public static final int DEFAULT_MAX_CONCURRENCY = 8;

        /** Default ceiling on how long one listing waits on the Contract Service. */
        public static final Duration DEFAULT_MAX_DURATION = Duration.ofSeconds(30);

        /** Parallel reads per listing, at least one; {@code CONTRACT_DISCOVERY_MAX_CONCURRENCY}. */
        private int maxConcurrency = DEFAULT_MAX_CONCURRENCY;

        /**
         * Wait before a listing answers {@code 502}, bound to {@code
         * CONTRACT_DISCOVERY_MAX_DURATION}; a single uncached fetch alone may take ~47s.
         */
        private Duration maxDuration = DEFAULT_MAX_DURATION;
    }

    /** Settings of the subject notifications, bound under {@code consent-manager.notifications}. */
    @Getter
    @Setter
    @ConfigurationProperties("notifications")
    public static class Notifications {

        /**
         * Client a consent-requested email links to, bound to {@code NOTIFICATION_CLIENT_URL}; the
         * bare URL, never extended with an id or token. Unset means the email carries no link.
         */
        @Nullable private URI clientUrl;

        /** Binds the client URL, treating the empty value of an unset variable as no URL. */
        public void setClientUrl(@Nullable URI clientUrl) {
            this.clientUrl = clientUrl == null || clientUrl.toString().isBlank() ? null : clientUrl;
        }
    }

    /**
     * Webhook delivery of consent changes to participants, bound under {@code
     * consent-manager.participant-notifications}; {@link Notifications} is subject email.
     */
    @Getter
    @Setter
    @ConfigurationProperties("participant-notifications")
    public static class ParticipantNotifications {

        /** Default bound on resolving a webhook host; the JDK resolver has none of its own. */
        public static final Duration DEFAULT_RESOLVE_TIMEOUT = Duration.ofSeconds(2);

        /** Default number of concurrent webhook host lookups. */
        public static final int DEFAULT_RESOLVER_THREADS = 8;

        /** Default webhook connection-attempt timeout. */
        public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);

        /** Default webhook response timeout. */
        public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(5);

        /**
         * Bound on resolving a webhook host, bound to {@code
         * PARTICIPANT_NOTIFICATIONS_RESOLVE_TIMEOUT}; a lookup that outlasts it is retried.
         */
        private Duration resolveTimeout = DEFAULT_RESOLVE_TIMEOUT;

        /**
         * Concurrent webhook host lookups, bound to {@code
         * PARTICIPANT_NOTIFICATIONS_RESOLVER_THREADS}; a stalled host holds at most one, since
         * lookups of the same host are shared.
         */
        private int resolverThreads = DEFAULT_RESOLVER_THREADS;

        /** Connect timeout, bound to {@code PARTICIPANT_NOTIFICATIONS_CONNECT_TIMEOUT}. */
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;

        /** Response timeout, bound to {@code PARTICIPANT_NOTIFICATIONS_READ_TIMEOUT}. */
        private Duration readTimeout = DEFAULT_READ_TIMEOUT;

        /**
         * Hosts a webhook may be delivered to, bound to {@code
         * PARTICIPANT_NOTIFICATIONS_ALLOWED_HOSTS}; empty admits any host whose addresses are all
         * external. A listed host is exempt from the private-address refusal.
         */
        private List<String> allowedHosts = List.of();

        /** Sets the allow-list, dropping the blank entry an unset variable binds as. */
        public void setAllowedHosts(@Nullable List<String> allowedHosts) {
            this.allowedHosts =
                    allowedHosts == null
                            ? List.of()
                            : allowedHosts.stream()
                                    .filter(host -> host != null && !host.isBlank())
                                    .map(String::trim)
                                    .toList();
        }
    }

    /**
     * What a consent receipt states about this deployment, bound under {@code
     * consent-manager.receipts}.
     */
    @Getter
    @Setter
    @ConfigurationProperties("receipts")
    public static class Receipts {

        /** Default jurisdiction a receipt names. */
        public static final String DEFAULT_JURISDICTION = "EU";

        /**
         * Jurisdiction a Kantara receipt names as governing the processing, bound to {@code
         * RECEIPTS_JURISDICTION}; neither a notice nor a contract carries one.
         */
        private String jurisdiction = DEFAULT_JURISDICTION;

        /**
         * Private JWK (RFC 7517 JSON, EC or RSA) every receipt is signed with, bound to {@code
         * RECEIPTS_SIGNING_KEY}; a secret. Unset fails startup unless an ephemeral key is allowed.
         */
        @Nullable private String signingKey;

        /**
         * Path of a mounted file holding the {@link #signingKey} JWK, bound to {@code
         * RECEIPTS_SIGNING_KEY_FILE}; the production form. Setting both fails startup.
         */
        @Nullable private String signingKeyFile;

        /**
         * Whether startup may generate a throwaway key when neither {@link #signingKey} nor {@link
         * #signingKeyFile} is set, bound to {@code RECEIPTS_EPHEMERAL_SIGNING_KEY}; receipts it
         * signed stop verifying on restart.
         */
        private boolean ephemeralSigningKey;

        /**
         * Public-only JWKS (RFC 7517 JSON) of keys earlier receipts were signed with, bound to
         * {@code RECEIPTS_RETIRED_VERIFICATION_KEYS}; published so those receipts still verify.
         */
        @Nullable private String retiredVerificationKeys;

        /**
         * Path of a mounted file holding the {@link #retiredVerificationKeys} JWKS, bound to {@code
         * RECEIPTS_RETIRED_VERIFICATION_KEYS_FILE}; the production form. Setting both fails
         * startup.
         */
        @Nullable private String retiredVerificationKeysFile;
    }

    /** How error responses are rendered, bound under {@code consent-manager.errors}. */
    @Getter
    @Setter
    @ConfigurationProperties("errors")
    public static class Errors {

        /**
         * Whether a 5xx publishes its exception message, bound to {@code
         * ERRORS_EXPOSE_EXCEPTION_DETAIL}; a debugging aid that must stay off in production.
         */
        private boolean exposeExceptionDetail;
    }

    /** How log lines refer to people, bound under {@code consent-manager.logging}. */
    @Getter
    @Setter
    @ConfigurationProperties("logging")
    public static class Logging {

        /**
         * HMAC key log pseudonyms are computed under, bound to {@code LOG_PSEUDONYM_KEY}; a secret.
         * Unset or short fails startup, and changing it unlinks every pseudonym already logged.
         */
        @Nullable private String pseudonymKey;
    }
}
