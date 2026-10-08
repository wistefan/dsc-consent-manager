package com.seamware.consentmanager.client;

import com.seamware.consentmanager.client.dto.ContractResponse;
import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.error.UpstreamServiceException;
import io.micronaut.cache.CacheManager;
import io.micronaut.cache.SyncCache;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.http.codec.CodecException;
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

    /** Characters that end a URI's path; a query or a fragment is no part of the identifier. */
    private static final String PATH_TERMINATORS = "?#";

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
     * <p>Throws {@link UpstreamServiceException} when the Contract Service could not be reached,
     * answered with a failure the retry budget did not survive, or answered with a body that is no
     * contract at all - the cases that must not be mistaken for an unknown contract. An undecodable
     * body is upstream's problem too: it is a 502 rather than the 500 an escaping decode failure
     * would otherwise render.
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
     * Derives the upstream identifier from a contract URI: the last segment of its path, or the
     * whole value when it carries no separator.
     *
     * <p>An http address is the ordinary case - {@code https://contract.example.org/contracts/c-1}
     * is read as {@code c-1} and fetched from the Contract Service this deployment is configured
     * against. A query or fragment is dropped first, so {@code .../c-1?v=2} is still {@code c-1}
     * rather than an identifier that URL-encodes into a 404.
     *
     * <p>The authority is deliberately not checked against {@code
     * consent-manager.contract-service.url}: the URI a contract is published under is a public one
     * and a deployment commonly reaches the same service on an internal address, so requiring the
     * two to agree would refuse contracts that resolve perfectly well. The cost is that two URIs
     * naming one document are two cache keys and, downstream, two rows under {@code
     * uq_privacy_notices_active_contract}. That is a property of taking the URI as the caller's own
     * key, which {@code privacy_notices.contract_uri} does by design, and it is recorded here
     * rather than papered over with a normalisation that would split the uniqueness guarantee.
     *
     * <p>Never applied to what is stored. {@code privacy_notices.contract_uri} keeps the URI
     * verbatim, because it is the key of the partial unique index.
     */
    static String contractIdOf(String contractUri) {
        String path = contractUri;
        for (int i = 0; i < PATH_TERMINATORS.length(); i++) {
            int terminator = path.indexOf(PATH_TERMINATORS.charAt(i));
            if (terminator >= 0) {
                path = path.substring(0, terminator);
            }
        }
        while (!path.isEmpty() && path.charAt(path.length() - 1) == PATH_SEPARATOR) {
            path = path.substring(0, path.length() - 1);
        }
        int lastSeparator = path.lastIndexOf(PATH_SEPARATOR);
        String identifier = lastSeparator < 0 ? path : path.substring(lastSeparator + 1);
        return identifier.isEmpty() ? contractUri : identifier;
    }

    /** Tries the ecosystem endpoint, then the bilateral one; empty when neither knows the id. */
    private Optional<ContractResponse> fetchUpstream(String contractUri) {
        String contractId = contractIdOf(contractUri);
        try {
            Optional<ContractResponse> ecosystem = client.fetchContract(contractId);
            return ecosystem.isPresent() ? ecosystem : client.fetchBilateralContract(contractId);
        } catch (HttpClientException | CodecException e) {
            throw new UpstreamServiceException(
                    "Contract Service at %s failed for contract %s"
                            .formatted(upstreamUrl, contractUri),
                    e);
        }
    }
}
