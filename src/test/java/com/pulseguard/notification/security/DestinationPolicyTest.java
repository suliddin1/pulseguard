package com.pulseguard.notification.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DestinationPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1",
            "127.1.2.3",
            "10.0.0.1",
            "10.254.0.1",
            "172.16.0.1",
            "172.31.255.255",
            "192.168.1.1",
            "169.254.1.1",
            "0.0.0.0",
            "100.64.0.1",
            "192.0.0.1",
            "192.0.2.1",
            "198.18.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "240.0.0.1",
            "255.255.255.255",
            "::1",
            "fe80::1",
            "fc00::1",
            "fd12:3456:789a::1",
            "2001:db8::1"
    })
    @DisplayName("isBlocked identifies private, loopback, link-local, documentation, and multicast addresses")
    void isBlockedIdentifiesForbiddenAddresses(String ip) throws Exception {
        InetAddress address = InetAddress.getByName(ip);
        assertThat(DestinationPolicy.isBlocked(address))
                .as("IP %s should be blocked", ip)
                .isTrue();
    }

    @Test
    @DisplayName("isBlocked permits valid public IPv4 and IPv6 addresses")
    void isBlockedPermitsPublicAddresses() throws Exception {
        assertThat(DestinationPolicy.isBlocked(InetAddress.getByName("8.8.8.8"))).isFalse();
        assertThat(DestinationPolicy.isBlocked(InetAddress.getByName("1.1.1.1"))).isFalse();
        assertThat(DestinationPolicy.isBlocked(InetAddress.getByName("2607:f8b0:4005:805::200e"))).isFalse();
    }

    @Test
    @DisplayName("validateStatic blocks plain http unless allowUnsafe is true")
    void validateStaticBlocksPlainHttpByDefault() throws Exception {
        DestinationPolicy safePolicy = new DestinationPolicy(HostResolver.SYSTEM, false, Set.of());
        URI httpUri = URI.create("http://api.example.com/webhook");

        assertThatThrownBy(() -> safePolicy.validateStatic(httpUri))
                .isInstanceOf(DestinationNotAllowedException.class)
                .hasMessageContaining("plain http");

        DestinationPolicy unsafePolicy = new DestinationPolicy(HostResolver.SYSTEM, true, Set.of());
        safePolicy.validateStatic(URI.create("https://api.example.com/webhook"));
        unsafePolicy.validateStatic(httpUri);
    }

    @Test
    @DisplayName("validateStatic blocks user credentials in URL")
    void validateStaticBlocksCredentials() {
        DestinationPolicy policy = new DestinationPolicy(HostResolver.SYSTEM, false, Set.of());
        URI uri = URI.create("https://user:pass@api.example.com/webhook");

        assertThatThrownBy(() -> policy.validateStatic(uri))
                .isInstanceOf(DestinationNotAllowedException.class)
                .hasMessageContaining("credentials in the URL");
    }

    @Test
    @DisplayName("validateStatic enforces host allow-list when configured")
    void validateStaticEnforcesHostAllowList() throws Exception {
        DestinationPolicy policy = new DestinationPolicy(HostResolver.SYSTEM, false, Set.of("allowed.example.com"));

        policy.validateStatic(URI.create("https://allowed.example.com/hook"));

        assertThatThrownBy(() -> policy.validateStatic(URI.create("https://other.example.com/hook")))
                .isInstanceOf(DestinationNotAllowedException.class)
                .hasMessageContaining("destination host is not in the allowed host list");
    }

    @Test
    @DisplayName("resolve rejects host if any resolved IP is in a blocked range (DNS rebinding / multi-A defense)")
    void resolveRejectsMixedResolvedAddresses() throws Exception {
        HostResolver mixedResolver = host -> new InetAddress[]{
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("127.0.0.1")
        };
        DestinationPolicy policy = new DestinationPolicy(mixedResolver, false, Set.of());

        assertThatThrownBy(() -> policy.resolve(URI.create("https://rebind.example.com/hook")))
                .isInstanceOf(DestinationNotAllowedException.class)
                .hasMessageContaining("destination resolves to a non-public address");
    }

    @Test
    @DisplayName("resolve returns pinned IP list without re-resolution")
    void resolveReturnsPinnedAddresses() throws Exception {
        InetAddress publicIp = InetAddress.getByName("93.184.216.34");
        HostResolver resolver = host -> new InetAddress[]{publicIp};
        DestinationPolicy policy = new DestinationPolicy(resolver, false, Set.of());

        DestinationPolicy.ResolvedDestination resolved = policy.resolve(URI.create("https://example.com:8443/webhook?key=value"));

        assertThat(resolved.host()).isEqualTo("example.com");
        assertThat(resolved.port()).isEqualTo(8443);
        assertThat(resolved.tls()).isTrue();
        assertThat(resolved.addresses()).containsExactly(publicIp);
        assertThat(resolved.requestTarget()).isEqualTo("/webhook?key=value");
    }
}
