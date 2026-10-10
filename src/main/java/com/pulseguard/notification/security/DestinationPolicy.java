package com.pulseguard.notification.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Outbound destination policy (SSRF defense).
 *
 * <p>{@link #resolve(URI)} resolves the host <b>once</b>, validates <b>every</b> returned address, and hands
 * the validated addresses to the HTTP client, which must connect to exactly those addresses. Because the
 * connection never triggers a second DNS lookup, a DNS-rebinding attacker cannot return a public address
 * for the check and a private one for the connection.
 */
public class DestinationPolicy {

    private final HostResolver resolver;
    private final boolean allowUnsafe;
    private final Set<String> allowedHosts;

    public DestinationPolicy(HostResolver resolver, boolean allowUnsafe, Set<String> allowedHosts) {
        this.resolver = resolver;
        this.allowUnsafe = allowUnsafe;
        this.allowedHosts = allowedHosts == null ? Set.of() : allowedHosts.stream()
                .map(h -> h.trim().toLowerCase(Locale.ROOT))
                .filter(h -> !h.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAllowUnsafe() {
        return allowUnsafe;
    }

    /** A validated destination: connect to {@code addresses} only, never re-resolve {@code host}. */
    public record ResolvedDestination(
            String host,
            int port,
            boolean tls,
            List<InetAddress> addresses,
            String requestTarget
    ) {
    }

    /**
     * Static (DNS-free) validation: scheme, host presence, userinfo, host allow-list and literal IPs.
     * Safe to call at startup. Exception messages never include the URL.
     */
    public void validateStatic(URI uri) throws DestinationNotAllowedException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && allowUnsafe)) {
            throw new DestinationNotAllowedException(scheme.equals("http")
                    ? "plain http destinations are not allowed"
                    : "unsupported or missing URL scheme");
        }
        if (uri.getRawUserInfo() != null) {
            throw new DestinationNotAllowedException("credentials in the URL are not allowed");
        }
        String host = hostOf(uri);
        if (host == null || host.isEmpty()) {
            throw new DestinationNotAllowedException("URL has no host");
        }
        if (!allowedHosts.isEmpty() && !allowedHosts.contains(host)) {
            throw new DestinationNotAllowedException("destination host is not in the allowed host list");
        }
        if (!allowUnsafe && isIpLiteral(host)) {
            try {
                // Literal: no DNS lookup happens.
                for (InetAddress address : InetAddress.getAllByName(host)) {
                    requirePublic(address);
                }
            } catch (UnknownHostException ex) {
                throw new DestinationNotAllowedException("destination host is not valid");
            }
        }
    }

    /** Full validation including DNS resolution. */
    public ResolvedDestination resolve(URI uri) throws DestinationNotAllowedException, UnknownHostException {
        validateStatic(uri);
        String host = hostOf(uri);
        boolean tls = "https".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() > 0 ? uri.getPort() : (tls ? 443 : 80);

        InetAddress[] resolved = resolver.resolve(host);
        if (resolved == null || resolved.length == 0) {
            throw new UnknownHostException("no addresses");
        }
        List<InetAddress> addresses = new ArrayList<>(Arrays.asList(resolved));
        if (!allowUnsafe) {
            // Every address must be public: a mixed answer is treated as hostile.
            for (InetAddress address : addresses) {
                requirePublic(address);
            }
        }

        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String target = uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        return new ResolvedDestination(host, port, tls, List.copyOf(addresses), target);
    }

    /** Host without IPv6 brackets, lower-cased, no trailing dot. */
    static String hostOf(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            return null;
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        host = host.toLowerCase(Locale.ROOT);
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }

    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.chars().allMatch(c -> Character.isDigit(c) || c == '.');
    }

    private static void requirePublic(InetAddress address) throws DestinationNotAllowedException {
        if (isBlocked(address)) {
            throw new DestinationNotAllowedException("destination resolves to a non-public address");
        }
    }

    /** True for loopback, any-local, link-local, private, multicast, reserved and special-purpose ranges. */
    public static boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int a0 = b[0] & 0xff;
            int a1 = b[1] & 0xff;
            int a2 = b[2] & 0xff;
            return a0 == 0                                          // 0.0.0.0/8
                    || (a0 == 100 && (a1 & 0xc0) == 64)              // 100.64.0.0/10 CGNAT
                    || (a0 == 192 && a1 == 0 && a2 == 0)             // 192.0.0.0/24
                    || (a0 == 192 && a1 == 0 && a2 == 2)             // TEST-NET-1
                    || (a0 == 198 && (a1 == 18 || a1 == 19))         // 198.18.0.0/15 benchmarking
                    || (a0 == 198 && a1 == 51 && a2 == 100)          // TEST-NET-2
                    || (a0 == 203 && a1 == 0 && a2 == 113)           // TEST-NET-3
                    || a0 >= 240;                                    // 240.0.0.0/4 reserved + broadcast
        }
        if (b.length == 16) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            boolean first12Zero = true;
            for (int i = 0; i < 12; i++) {
                if (b[i] != 0) {
                    first12Zero = false;
                    break;
                }
            }
            return first12Zero                                                       // ::/96 (v4-compatible, ::1)
                    || (b0 & 0xfe) == 0xfc                                           // fc00::/7 unique local
                    || (b0 == 0x00 && b1 == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b) // 64:ff9b::/16 NAT64
                    || (b0 == 0x20 && b1 == 0x02)                                    // 2002::/16 6to4
                    || (b0 == 0x20 && b1 == 0x01 && b[2] == 0 && b[3] == 0)          // 2001::/32 Teredo
                    || (b0 == 0x20 && b1 == 0x01 && b[2] == 0x0d && (b[3] & 0xff) == 0xb8) // 2001:db8::/32 docs
                    || (b0 == 0x01 && b1 == 0x00 && b[2] == 0 && b[3] == 0 && b[4] == 0 && b[5] == 0 && b[6] == 0 && b[7] == 0); // 100::/64 discard
        }
        return true;
    }
}
