package com.pulseguard.notification.security;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Produces messages that are safe to persist, log and return from the API.
 *
 * <p>Two layers of defence: {@link #describe(Throwable)} never forwards raw JDK exception messages (which can
 * contain host names, IPs or URLs), and {@link #redact(String)} scrubs URLs, bearer tokens and every
 * configured secret value from whatever text remains.
 */
public class Redactor {

    private static final Pattern URL = Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s\"'<>]+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final int MAX_LENGTH = 300;
    private static final String REDACTED = "[redacted]";

    private final List<String> secrets;

    public Redactor(Collection<String> secrets) {
        this.secrets = secrets.stream()
                .filter(s -> s != null && s.length() >= 4)
                .distinct()
                .toList();
    }

    public String redact(String text) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (String secret : secrets) {
            result = result.replace(secret, REDACTED);
        }
        result = URL.matcher(result).replaceAll("[redacted-url]");
        result = BEARER.matcher(result).replaceAll("Bearer " + REDACTED);
        return result.length() <= MAX_LENGTH ? result : result.substring(0, MAX_LENGTH - 3) + "...";
    }

    /** A short, fixed-vocabulary description of a delivery failure. */
    public String describe(Throwable failure) {
        String description;
        if (failure instanceof PermanentDeliveryException permanent) {
            // Messages of our own exception types are written to be safe.
            description = permanent.getMessage();
        } else if (failure instanceof SocketTimeoutException) {
            description = "timeout";
        } else if (failure instanceof UnknownHostException) {
            description = "unknown_host";
        } else if (failure instanceof ConnectException || failure instanceof NoRouteToHostException) {
            description = "connection_failed";
        } else if (failure instanceof SSLException) {
            description = hasCause(failure, CertificateException.class)
                    ? "tls_certificate_error"
                    : "tls_error";
        } else if (failure instanceof IOException) {
            description = "io_error:" + failure.getClass().getSimpleName();
        } else {
            description = "unexpected_error:" + failure.getClass().getSimpleName();
        }
        return redact(description);
    }

    /** True when {@code failure} or any cause is an instance of {@code type}. */
    public static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
