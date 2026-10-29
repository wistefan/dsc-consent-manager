package com.seamware.consentmanager.client;

import io.micronaut.core.annotation.NonNull;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * The address heuristic every participant- or catalog-supplied URL is vetted with before a connect:
 * a host is fetched from only when none of its addresses is inside this deployment.
 */
public final class NetworkAddressPolicy {

    /** Mask and value of RFC 4193's unique-local IPv6 prefix, {@code fc00::/7}. */
    private static final int UNIQUE_LOCAL_V6_MASK = 0xfe;

    private static final int UNIQUE_LOCAL_V6_PREFIX = 0xfc;

    /** First octet, and the second's bounds, of RFC 6598's {@code 100.64.0.0/10}. */
    private static final int SHARED_V4_FIRST_OCTET = 100;

    private static final int SHARED_V4_SECOND_OCTET_MIN = 64;

    private static final int SHARED_V4_SECOND_OCTET_MAX = 127;

    /** Reads an address byte as an unsigned octet. */
    private static final int OCTET_MASK = 0xff;

    private NetworkAddressPolicy() {}

    /**
     * Whether every address the host resolves to is outside this deployment's network; false when
     * it does not resolve. DNS rebinding between this check and the connect stays open.
     */
    public static boolean isExternal(@NonNull String host) {
        try {
            return allExternal(InetAddress.getAllByName(host));
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** Whether none of those addresses is internal. */
    public static boolean allExternal(@NonNull InetAddress... addresses) {
        return Arrays.stream(addresses).noneMatch(NetworkAddressPolicy::isInternal);
    }

    /**
     * Whether the address is internal; adds {@code fc00::/7} and {@code 100.64.0.0/10}, which
     * {@link InetAddress}'s own predicates miss.
     */
    public static boolean isInternal(@NonNull InetAddress address) {
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
}
