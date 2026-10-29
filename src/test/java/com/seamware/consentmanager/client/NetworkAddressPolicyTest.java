package com.seamware.consentmanager.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Pins which address classes count as inside this deployment. Literals only, so no DNS. */
@DisplayName("Network address policy")
class NetworkAddressPolicyTest {

    private static final String PUBLIC_V4 = "203.0.113.10";

    private static final String PRIVATE_V4 = "10.1.2.3";

    @ParameterizedTest(name = "{0} ({1}) internal = {2}")
    @CsvSource({
        "127.0.0.1,      loopback,             true",
        "10.1.2.3,       10/8,                 true",
        "172.16.0.1,     172.16/12,            true",
        "172.31.255.254, 172.16/12 upper edge, true",
        "192.168.1.1,    192.168/16,           true",
        "169.254.169.254, link-local,          true",
        "100.64.0.1,     100.64/10,            true",
        "100.127.255.254, 100.64/10 upper edge, true",
        "fc00::1,        fc00::/7,             true",
        "fd12:3456::1,   fd00::/8,             true",
        "::1,            v6 loopback,          true",
        "0.0.0.0,        any-local,            true",
        "224.0.0.1,      multicast,            true",
        "100.128.0.1,    just past 100.64/10,  false",
        "172.32.0.1,     just past 172.16/12,  false",
        "203.0.113.10,   public v4,            false",
        "2001:db8::1,    public v6,            false"
    })
    void classifiesAddress(String literal, String addressClass, boolean internal)
            throws UnknownHostException {
        assertThat(NetworkAddressPolicy.isInternal(InetAddress.getByName(literal)))
                .as(addressClass)
                .isEqualTo(internal);
    }

    @Test
    void oneInternalAddressMakesTheHostInternal() throws UnknownHostException {
        assertThat(
                        NetworkAddressPolicy.allExternal(
                                InetAddress.getByName(PUBLIC_V4),
                                InetAddress.getByName(PRIVATE_V4)))
                .isFalse();
        assertThat(NetworkAddressPolicy.allExternal(InetAddress.getByName(PUBLIC_V4))).isTrue();
    }
}
