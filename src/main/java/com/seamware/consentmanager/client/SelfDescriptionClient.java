package com.seamware.consentmanager.client;

import com.seamware.consentmanager.client.dto.SelfDescription;
import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import io.micronaut.cache.CacheManager;
import io.micronaut.cache.SyncCache;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads a catalog self-description by absolute URL, guarded, cached, and never throwing.
 *
 * <p>The second of the two upstreams a resolved retrieval reads, and the one whose URLs come out of
 * a document this service does not own. Every failure - a URL the guard refuses, a timeout, a
 * non-2xx, a body that will not parse - is an empty result, because a retrieval that threw here
 * would answer the 5xx that resolution exists to avoid.
 *
 * <p>The guard only decides the URL it is given, so the client it reads over must not follow
 * redirects - {@link SelfDescriptionHttpClientFactory} is where that is set, and why.
 *
 * <p>Not {@code @Cacheable}, for {@link ContractGateway}'s reason and a stronger one: the
 * annotation cannot decline to store a result, and an empty one here can mean a blipped network as
 * easily as an unknown document, so it would pin a momentarily unreachable catalog to unavailable
 * for a whole lifetime. The cache is read and written by hand and only a parsed document is stored.
 * A refusal by the guard is not cached either - it is a configuration decision rather than a fetch
 * result, and re-deciding it costs nothing.
 */
@Singleton
public class SelfDescriptionClient {

    /** Name of the cache declared under {@code micronaut.caches.self-descriptions}. */
    public static final String CACHE_NAME = "self-descriptions";

    private static final Logger LOG = LoggerFactory.getLogger(SelfDescriptionClient.class);

    /** The only scheme a self-description is read over unless plaintext is opted into. */
    private static final String HTTPS_SCHEME = "https";

    /** The scheme {@code allow-plaintext-http} admits, for a catalog stub or a private network. */
    private static final String HTTP_SCHEME = "http";

    /** Mask and value of RFC 4193's unique-local IPv6 prefix, {@code fc00::/7}. */
    private static final int UNIQUE_LOCAL_V6_MASK = 0xfe;

    private static final int UNIQUE_LOCAL_V6_PREFIX = 0xfc;

    /** First octet, and the second's bounds, of RFC 6598's {@code 100.64.0.0/10}. */
    private static final int SHARED_V4_FIRST_OCTET = 100;

    private static final int SHARED_V4_SECOND_OCTET_MIN = 64;

    private static final int SHARED_V4_SECOND_OCTET_MAX = 127;

    /** Reads one byte of an address as the unsigned octet it stands for. */
    private static final int OCTET_MASK = 0xff;

    private final HttpClient http;
    private final SyncCache<?> cache;
    private final JsonMapper json;
    private final Set<String> allowedHosts;
    private final boolean allowPlaintextHttp;

    /** Reads over the injected client, which is where the timeouts and the redirect rule live. */
    public SelfDescriptionClient(
            ConsentManagerConfiguration.SelfDescription configuration,
            @Named(SelfDescriptionHttpClientFactory.NAME) HttpClient http,
            CacheManager<?> cacheManager,
            JsonMapper json) {
        this.http = http;
        this.cache = cacheManager.getCache(CACHE_NAME);
        this.json = json;
        this.allowedHosts =
                configuration.getAllowedHosts().stream()
                        .map(host -> host.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
        this.allowPlaintextHttp = configuration.isAllowPlaintextHttp();
    }

    /** The document at that URL, from the cache when it is there; empty on any failure at all. */
    @NonNull
    public Optional<SelfDescription> fetch(@NonNull String url) {
        if (!permits(url)) {
            LOG.debug("Refusing to read a self-description from {}", url);
            return Optional.empty();
        }
        Optional<SelfDescription> cached = cache.get(url, SelfDescription.class);
        if (cached.isPresent()) {
            return cached;
        }
        // Parsed once already by the guard, so this cannot be the URL that is not a URI.
        Optional<SelfDescription> fetched = read(URI.create(url));
        fetched.ifPresent(document -> cache.put(url, document));
        return fetched;
    }

    /**
     * Whether this deployment reads documents from that URL at all, which is what {@link #fetch}
     * asks before anything else.
     *
     * <p>Public because it is the half of this class a caller is entitled to reason about on its
     * own: a refusal and a failed read are the same empty result from {@code fetch}, so the guard
     * is only observable here.
     */
    public boolean permits(@NonNull String url) {
        URI uri = parsed(url);
        return uri != null && permitted(uri);
    }

    /** The URL as a URI, or null when it is not one. */
    private static URI parsed(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * Whether this deployment reads documents from that URL.
     *
     * <p>The scheme and the host list are decided from the URL itself; the address check behind
     * them is a heuristic, and a host named in {@code allowed-hosts} waives it, because an explicit
     * entry is an operator's decision about their own network.
     */
    private boolean permitted(URI uri) {
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        boolean schemeAdmitted =
                HTTPS_SCHEME.equals(scheme) || (allowPlaintextHttp && HTTP_SCHEME.equals(scheme));
        if (host == null || !schemeAdmitted) {
            return false;
        }
        boolean named = allowedHosts.contains(host.toLowerCase(Locale.ROOT));
        if (!allowedHosts.isEmpty() && !named) {
            return false;
        }
        return named || isExternal(host);
    }

    /**
     * Whether every address the host resolves to lies outside this deployment's own network.
     *
     * <p>Resolved here and again when the connection is opened, so a name that changes answers
     * between the two could still be followed somewhere this refuses. That is what {@code
     * allowed-hosts} is for in a deployment where it matters; nothing short of connecting through a
     * pre-resolved address closes it, and a catalog is named by DNS.
     */
    private static boolean isExternal(String host) {
        try {
            return Arrays.stream(InetAddress.getAllByName(host))
                    .noneMatch(SelfDescriptionClient::isInternal);
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * Whether that address is one this deployment's own network answers for.
     *
     * <p>The two ranges spelled out below are the ones {@link InetAddress}'s own predicates miss:
     * {@code isSiteLocalAddress} tests only the deprecated {@code fec0::/10} on IPv6, never the
     * {@code fc00::/7} every IPv6-addressed cluster actually uses, and no predicate covers the
     * shared address space managed Kubernetes hands out.
     */
    private static boolean isInternal(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()
                || isUniqueLocalV6(address)
                || isSharedV4(address);
    }

    /** Whether that is an RFC 4193 unique-local IPv6 address, {@code fc00::/7}. */
    private static boolean isUniqueLocalV6(InetAddress address) {
        return address instanceof Inet6Address
                && (address.getAddress()[0] & UNIQUE_LOCAL_V6_MASK) == UNIQUE_LOCAL_V6_PREFIX;
    }

    /** Whether that is in RFC 6598 shared address space, {@code 100.64.0.0/10}. */
    private static boolean isSharedV4(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            return false;
        }
        byte[] octets = address.getAddress();
        int second = octets[1] & OCTET_MASK;
        return (octets[0] & OCTET_MASK) == SHARED_V4_FIRST_OCTET
                && second >= SHARED_V4_SECOND_OCTET_MIN
                && second <= SHARED_V4_SECOND_OCTET_MAX;
    }

    /** The document at that URI, or empty when anything at all went wrong reading it. */
    private Optional<SelfDescription> read(URI uri) {
        try {
            String body = http.toBlocking().retrieve(HttpRequest.GET(uri), String.class);
            return body == null
                    ? Optional.empty()
                    : Optional.of(json.readValue(body, SelfDescription.class));
        } catch (IOException | RuntimeException e) {
            LOG.debug("Could not read the self-description at {}", uri, e);
            return Optional.empty();
        }
    }
}
