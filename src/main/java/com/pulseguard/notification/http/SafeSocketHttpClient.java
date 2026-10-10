package com.pulseguard.notification.http;

import com.pulseguard.notification.security.DestinationPolicy;
import com.pulseguard.notification.security.DestinationPolicy.ResolvedDestination;
import com.pulseguard.notification.security.PermanentDeliveryException;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Deliberately small HTTP/1.1 POST client built directly on sockets.
 *
 * <p>Why not {@code java.net.http.HttpClient}: it re-resolves the host at connect time, so a DNS-rebinding
 * attacker could pass a policy check with a public address and then be connected to a private one. Here the
 * policy resolves once and this client connects to exactly the validated {@link InetAddress}; for TLS the
 * original host name is still used for SNI and certificate/host-name verification.
 *
 * <p>Properties: no redirect following (3xx is returned to the caller), bounded request size, bounded
 * response header read, connect/read timeouts and a hard total deadline. Only the status line is read.
 */
public class SafeSocketHttpClient implements OutboundHttpClient {

    private static final int MAX_STATUS_LINE_BYTES = 8 * 1024;

    /** Opens the TCP connection to a <i>validated</i> address. Overridable for tests. */
    @FunctionalInterface
    public interface Connector {
        Socket connect(InetAddress address, int port, int connectTimeoutMs) throws IOException;
    }

    private static final Connector PLAIN_CONNECTOR = (address, port, timeoutMs) -> {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(address, port), timeoutMs);
            return socket;
        } catch (IOException | RuntimeException ex) {
            closeQuietly(socket);
            throw ex;
        }
    };

    private final DestinationPolicy policy;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final Duration totalTimeout;
    private final int maxPayloadBytes;
    private final SSLSocketFactory sslSocketFactory;
    private final Connector connector;

    public SafeSocketHttpClient(
            DestinationPolicy policy,
            Duration connectTimeout,
            Duration readTimeout,
            Duration totalTimeout,
            int maxPayloadBytes,
            SSLSocketFactory sslSocketFactory
    ) {
        this(policy, connectTimeout, readTimeout, totalTimeout, maxPayloadBytes, sslSocketFactory, PLAIN_CONNECTOR);
    }

    public SafeSocketHttpClient(
            DestinationPolicy policy,
            Duration connectTimeout,
            Duration readTimeout,
            Duration totalTimeout,
            int maxPayloadBytes,
            SSLSocketFactory sslSocketFactory,
            Connector connector
    ) {
        this.policy = policy;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.totalTimeout = totalTimeout;
        this.maxPayloadBytes = maxPayloadBytes;
        this.sslSocketFactory = sslSocketFactory;
        this.connector = connector;
    }

    @Override
    public int post(URI uri, Map<String, String> headers, byte[] body) throws IOException {
        if (body.length > maxPayloadBytes) {
            throw new PermanentDeliveryException("request body exceeds the configured size limit");
        }
        byte[] requestHead = buildRequestHead(uri, headers, body.length);

        // Single DNS resolution + validation of ALL addresses. Nothing below re-resolves the host.
        ResolvedDestination destination = policy.resolve(uri);

        long deadlineNanos = System.nanoTime() + totalTimeout.toNanos();
        IOException lastFailure = null;
        List<InetAddress> addresses = destination.addresses();
        for (InetAddress address : addresses) {
            Socket plain;
            try {
                plain = connector.connect(address, destination.port(), toMillis(connectTimeout, deadlineNanos));
            } catch (IOException ex) {
                // Connect-phase failure: nothing was sent, so trying the next validated address is safe.
                lastFailure = ex;
                continue;
            }
            Socket active = plain;
            try {
                if (destination.tls()) {
                    active = upgradeToTls(plain, destination);
                }
                // Once the request may have been written we never replay it on another address.
                return exchange(active, requestHead, body, deadlineNanos);
            } finally {
                closeQuietly(active);
                closeQuietly(plain);
            }
        }
        throw lastFailure != null ? lastFailure : new IOException("connection failed");
    }

    private Socket upgradeToTls(Socket plain, ResolvedDestination destination) throws IOException {
        SSLSocket ssl = (SSLSocket) sslSocketFactory.createSocket(plain, destination.host(), destination.port(), true);
        try {
            SSLParameters parameters = ssl.getSSLParameters();
            // Verify the certificate against the ORIGINAL host name even though we dialed a pinned IP.
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            if (!looksLikeIp(destination.host())) {
                parameters.setServerNames(List.of(new SNIHostName(destination.host())));
            }
            ssl.setSSLParameters(parameters);
            ssl.setSoTimeout((int) Math.min(Integer.MAX_VALUE, readTimeout.toMillis()));
            ssl.startHandshake();
            return ssl;
        } catch (IOException | RuntimeException ex) {
            closeQuietly(ssl);
            throw ex;
        }
    }

    private int exchange(Socket socket, byte[] head, byte[] body, long deadlineNanos) throws IOException {
        OutputStream out = socket.getOutputStream();
        socket.setSoTimeout(toMillis(readTimeout, deadlineNanos));
        out.write(head);
        out.write(body);
        out.flush();

        InputStream in = socket.getInputStream();
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            socket.setSoTimeout(toMillis(readTimeout, deadlineNanos));
            int next = in.read();
            if (next < 0) {
                throw new IOException("connection closed before a response status was received");
            }
            if (next == '\n') {
                break;
            }
            if (line.size() >= MAX_STATUS_LINE_BYTES) {
                throw new PermanentDeliveryException("response status line exceeds the size limit");
            }
            line.write(next);
        }
        return parseStatus(line.toString(StandardCharsets.ISO_8859_1));
    }

    static int parseStatus(String statusLine) throws PermanentDeliveryException {
        String line = statusLine.trim();
        // "HTTP/1.1 200 OK"
        String[] parts = line.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/1.") || parts[1].length() != 3) {
            throw new PermanentDeliveryException("malformed HTTP response status line");
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException ex) {
            throw new PermanentDeliveryException("malformed HTTP response status code");
        }
    }

    private byte[] buildRequestHead(URI uri, Map<String, String> headers, int contentLength) throws IOException {
        // Request target and Host come from the same parsed URI the policy validates.
        String host = uri.getHost();
        if (host == null) {
            throw new PermanentDeliveryException("URL has no host");
        }
        String rawPath = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String target = uri.getRawQuery() == null ? rawPath : rawPath + "?" + uri.getRawQuery();
        requireNoControlChars(target, "request target");

        boolean tls = "https".equalsIgnoreCase(uri.getScheme());
        int defaultPort = tls ? 443 : 80;
        String hostHeader = uri.getPort() > 0 && uri.getPort() != defaultPort ? host + ":" + uri.getPort() : host;
        requireNoControlChars(hostHeader, "host");

        StringBuilder sb = new StringBuilder(256);
        sb.append("POST ").append(target).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(hostHeader).append("\r\n");
        sb.append("Connection: close\r\n");
        sb.append("Content-Length: ").append(contentLength).append("\r\n");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String name = header.getKey();
            String value = header.getValue();
            if (name == null || name.isEmpty() || name.indexOf(':') >= 0) {
                throw new PermanentDeliveryException("invalid header name");
            }
            requireNoControlChars(name, "header name");
            requireNoControlChars(value, "header value");
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.equals("host") || lower.equals("content-length") || lower.equals("connection")
                    || lower.equals("transfer-encoding")) {
                continue; // framing headers are owned by this client
            }
            sb.append(name).append(": ").append(value).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void requireNoControlChars(String value, String what) throws PermanentDeliveryException {
        if (value == null) {
            throw new PermanentDeliveryException("missing " + what);
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f || c > 0xff) {
                throw new PermanentDeliveryException("illegal character in " + what);
            }
        }
    }

    private static boolean looksLikeIp(String host) {
        return host.indexOf(':') >= 0 || host.chars().allMatch(c -> Character.isDigit(c) || c == '.');
    }

    /** Remaining time to the deadline, capped by {@code cap}, as a positive millisecond value. */
    private static int toMillis(Duration cap, long deadlineNanos) throws SocketTimeoutException {
        long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        if (remainingMs <= 0) {
            throw new SocketTimeoutException("total delivery deadline exceeded");
        }
        return (int) Math.max(1, Math.min(Math.min(cap.toMillis(), remainingMs), Integer.MAX_VALUE));
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
