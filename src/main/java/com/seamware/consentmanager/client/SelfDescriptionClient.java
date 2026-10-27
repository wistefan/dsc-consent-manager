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
 * Reads a catalog self-description by absolute URL, guarded and cached; every failure is an empty
 * result. Only a parsed document is cached, so a network blip is never pinned.
 *
 * <p>The guard decides only the URL given, so the client must not follow redirects; see {@link
 * SelfDescriptionHttpClientFactory}.
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

    /** Reads an address byte as an unsigned octet. */
    private static final int OCTET_MASK = 0xff;

    private final HttpClient http;
    private final SyncCache<?> cache;
    private final JsonMapper json;
    private final Set<String> allowedHosts;
    private final boolean allowPlaintextHttp;

    /** Reads over the injected client, which carries the timeouts and the redirect rule. */
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

    /** The document at that URL, cached when possible; empty on any failure. */
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
        // The guard already parsed it, so this cannot throw.
        Optional<SelfDescription> fetched = read(URI.create(url));
        fetched.ifPresent(document -> cache.put(url, document));
        return fetched;
    }

    /**
     * Whether this deployment reads documents from that URL at all; public because {@link #fetch}
     * reports a refusal and a failed read alike.
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
     * Checks scheme and host list, then the address heuristic, which a host named in {@code
     * allowed-hosts} waives as the operator's decision.
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
     * Whether every address the host resolves to is outside this deployment's network. DNS
     * rebinding between this check and the connect stays open; {@code allowed-hosts} closes it.
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
     * Whether the address is internal; adds {@code fc00::/7} and {@code 100.64.0.0/10}, which
     * {@link InetAddress}'s own predicates miss.
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

    /** The document at that URI, or empty on any failure. */
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
