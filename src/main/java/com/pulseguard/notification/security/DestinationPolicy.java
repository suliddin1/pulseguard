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
                .map(DestinationPolicy::normalizeHost)
                .filter(h -> h != null && !h.isEmpty())
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
        if (isNonCanonicalIpv4Literal(host)) {
            throw new DestinationNotAllowedException("non-canonical IPv4 literal is not allowed");
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

    /** Host without IPv6 brackets, lower-cased, no trailing dots. */
    static String hostOf(URI uri) {
        return normalizeHost(uri.getHost());
    }

    private static String normalizeHost(String rawHost) {
        if (rawHost == null) {
            return null;
        }
        String host = rawHost.trim();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        host = host.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host;
    }

    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.chars().allMatch(c -> Character.isDigit(c) || c == '.');
    }

    /**
     * Detects non-canonical IPv4 encodings (hex like 0x7f.0.0.1 or 0x7f000001, octal like 0177.0.0.1,
     * or fewer than 4 numeric dot-separated segments like 127.1 or 2130706433).
     */
    private static boolean isNonCanonicalIpv4Literal(String host) {
        if (host.indexOf(':') >= 0) {
            return false;
        }
        String[] parts = host.split("\\.", -1);
        boolean allNumericOrHex = true;
        boolean hasHexOrOctal = false;
        for (String part : parts) {
            if (part.isEmpty()) {
                return true;
            }
            if (part.startsWith("0x")) {
                if (part.length() <= 2) {
                    allNumericOrHex = false;
                    break;
                }
                for (int i = 2; i < part.length(); i++) {
                    char c = part.charAt(i);
                    if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                        allNumericOrHex = false;
                        break;
                    }
                }
                hasHexOrOctal = true;
            } else if (part.chars().allMatch(Character::isDigit)) {
                if (part.length() > 1 && part.startsWith("0")) {
                    hasHexOrOctal = true;
                }
            } else {
                allNumericOrHex = false;
                break;
            }
        }
        return allNumericOrHex && (hasHexOrOctal || parts.length != 4);
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
            return isBlockedIpv4Bytes(b[0] & 0xff, b[1] & 0xff, b[2] & 0xff, b[3] & 0xff);
        }
        if (b.length == 16) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            boolean first10Zero = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) {
                    first10Zero = false;
                    break;
                }
            }
            boolean first12Zero = first10Zero && b[10] == 0 && b[11] == 0;
            // IPv4-mapped IPv6 (::ffff:a.b.c.d)
            if (first10Zero && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                return isBlockedIpv4Bytes(b[12] & 0xff, b[13] & 0xff, b[14] & 0xff, b[15] & 0xff);
            }
            // SIIT IPv4-translated IPv6 (::ffff:0:a.b.c.d)
            boolean first8Zero = true;
            for (int i = 0; i < 8; i++) {
                if (b[i] != 0) {
                    first8Zero = false;
                    break;
                }
            }
            if (first8Zero && (b[8] & 0xff) == 0xff && (b[9] & 0xff) == 0xff && b[10] == 0 && b[11] == 0) {
                return true;
            }
            return first12Zero                                                       // ::/96 (v4-compatible, ::1, ::)
                    || (b0 & 0xfe) == 0xfc                                           // fc00::/7 unique local
                    || (b0 == 0xfe && (b1 & 0xc0) == 0x80)                           // fe80::/10 link-local
                    || (b0 == 0xfe && (b1 & 0xc0) == 0xc0)                           // fec0::/10 site-local
                    || b0 == 0xff                                                    // ff00::/8 multicast
                    || (b0 == 0x00 && b1 == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b) // 64:ff9b::/32 NAT64
                    || (b0 == 0x20 && b1 == 0x02)                                    // 2002::/16 6to4
                    || (b0 == 0x20 && b1 == 0x01 && b[2] == 0 && b[3] == 0)          // 2001::/32 Teredo
                    || (b0 == 0x20 && b1 == 0x01 && b[2] == 0x0d && (b[3] & 0xff) == 0xb8) // 2001:db8::/32 docs
                    || (b0 == 0x01 && b1 == 0x00 && b[2] == 0 && b[3] == 0 && b[4] == 0 && b[5] == 0 && b[6] == 0 && b[7] == 0); // 100::/64 discard
        }
        return true;
    }

    private static boolean isBlockedIpv4Bytes(int a0, int a1, int a2, int a3) {
        return a0 == 0                                          // 0.0.0.0/8
                || a0 == 10                                     // 10.0.0.0/8 private
                || a0 == 127                                    // 127.0.0.0/8 loopback
                || (a0 == 169 && a1 == 254)                     // 169.254.0.0/16 link-local
                || (a0 == 172 && a1 >= 16 && a1 <= 31)          // 172.16.0.0/12 private
                || (a0 == 192 && a1 == 168)                     // 192.168.0.0/16 private
                || (a0 == 100 && (a1 & 0xc0) == 64)             // 100.64.0.0/10 CGNAT
                || (a0 == 192 && a1 == 0 && a2 == 0)            // 192.0.0.0/24
                || (a0 == 192 && a1 == 0 && a2 == 2)            // TEST-NET-1
                || (a0 == 198 && (a1 == 18 || a1 == 19))        // 198.18.0.0/15 benchmarking
                || (a0 == 198 && a1 == 51 && a2 == 100)         // TEST-NET-2
                || (a0 == 203 && a1 == 0 && a2 == 113)          // TEST-NET-3
                || a0 >= 224;                                   // 224.0.0.0/4 multicast + 240.0.0.0/4 reserved + broadcast
    }
}
