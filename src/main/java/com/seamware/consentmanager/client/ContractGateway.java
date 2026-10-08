package com.seamware.consentmanager.client;

import com.seamware.consentmanager.client.dto.ContractResponse;
import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.error.UpstreamServiceException;
import io.micronaut.cache.CacheManager;
import io.micronaut.cache.SyncCache;
import io.micronaut.http.client.exceptions.HttpClientException;
import jakarta.inject.Singleton;
import java.util.Optional;

/**
 * Resolves a contract URI to the contract behind it, trying both upstream shapes and caching what
 * came back.
 *
 * <p>The bean every caller depends on, and the only place contracts are cached. The cache is not on
 * {@link ContractServiceClient}: {@code @Cacheable} is around-advice and {@code @Client} is
 * introduction advice, so stacking them would make the applied order an implementation detail, and
 * caching one endpoint call would miss the ecosystem-then-bilateral fallback that a repeated
 * generation has to avoid repeating.
 *
 * <p>It is not {@code @Cacheable} either. That annotation has no way to decline to store a result,
 * so an empty one - "no such contract" - would be served for a whole lifetime, and a contract
 * created upstream a second after the miss would stay invisible with nothing able to tell the two
 * apart. So the cache is read and written here by hand, and only a contract that actually arrived
 * is stored; a miss and a failure are both returned uncached.
 */
@Singleton
public class ContractGateway {

    /** Name of the cache declared under {@code micronaut.caches.contracts}. */
    public static final String CACHE_NAME = "contracts";

    /** Separator whose last occurrence in a contract URI precedes the contract's identifier. */
    private static final char PATH_SEPARATOR = '/';

    private final ContractServiceClient client;
    private final SyncCache<?> cache;
    private final String upstreamUrl;

    /** Creates the gateway over the declarative client and the {@code contracts} cache. */
    public ContractGateway(
            ContractServiceClient client,
            CacheManager<?> cacheManager,
            ConsentManagerConfiguration.ContractService configuration) {
        this.client = client;
        this.cache = cacheManager.getCache(CACHE_NAME);
        this.upstreamUrl = configuration.getUrl();
    }

    /**
     * Returns the contract the URI names, from the cache when it is there; empty when neither
     * upstream endpoint knows it.
     *
     * <p>Throws {@link UpstreamServiceException} when the Contract Service could not be reached or
     * answered with a failure the retry budget did not survive - the one case that must not be
     * mistaken for an unknown contract.
     */
    public Optional<ContractResponse> fetch(String contractUri) {
        Optional<ContractResponse> cached = cache.get(contractUri, ContractResponse.class);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<ContractResponse> fetched = fetchUpstream(contractUri);
        fetched.ifPresent(contract -> cache.put(contractUri, contract));
        return fetched;
    }

    /**
     * Derives the upstream identifier from a contract URI: its last path segment, or the whole
     * value when it carries no separator.
     *
     * <p>Never applied to what is stored. {@code privacy_notices.contract_uri} keeps the URI
     * verbatim, because it is the key of the partial unique index and normalising it would split
     * the uniqueness guarantee silently.
     */
    static String contractIdOf(String contractUri) {
        String trimmed = contractUri;
        while (!trimmed.isEmpty() && trimmed.charAt(trimmed.length() - 1) == PATH_SEPARATOR) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int lastSeparator = trimmed.lastIndexOf(PATH_SEPARATOR);
        return lastSeparator < 0 ? trimmed : trimmed.substring(lastSeparator + 1);
    }

    /** Tries the ecosystem endpoint, then the bilateral one; empty when neither knows the id. */
    private Optional<ContractResponse> fetchUpstream(String contractUri) {
        String contractId = contractIdOf(contractUri);
        try {
            Optional<ContractResponse> ecosystem = client.fetchContract(contractId);
            return ecosystem.isPresent() ? ecosystem : client.fetchBilateralContract(contractId);
        } catch (HttpClientException e) {
            throw new UpstreamServiceException(
                    "Contract Service at %s failed for contract %s"
                            .formatted(upstreamUrl, contractUri),
                    e);
        }
    }
}
