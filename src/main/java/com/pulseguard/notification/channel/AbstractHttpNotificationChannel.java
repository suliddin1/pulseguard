package com.pulseguard.notification.channel;

import com.pulseguard.notification.http.OutboundHttpClient;
import com.pulseguard.notification.security.PermanentDeliveryException;
import com.pulseguard.notification.security.Redactor;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

/** Shared HTTP delivery and failure classification for HTTP-based channels. */
abstract class AbstractHttpNotificationChannel implements NotificationChannel {

    private final OutboundHttpClient httpClient;
    private final Redactor redactor;

    protected AbstractHttpNotificationChannel(OutboundHttpClient httpClient, Redactor redactor) {
        this.httpClient = httpClient;
        this.redactor = redactor;
    }

    protected static URI parse(String url) throws PermanentDeliveryException {
        if (url == null || url.isBlank()) {
            throw new PermanentDeliveryException("destination is not configured");
        }
        try {
            return new URI(url);
        } catch (URISyntaxException ex) {
            // Do not include the URL (or the parser's message, which echoes it).
            throw new PermanentDeliveryException("destination URL is malformed");
        }
    }

    protected DeliveryResult post(URI uri, Map<String, String> headers, byte[] body) {
        try {
            return DeliveryResult.fromHttpStatus(httpClient.post(uri, headers, body));
        } catch (PermanentDeliveryException ex) {
            return DeliveryResult.permanent(null, redactor.describe(ex));
        } catch (IOException ex) {
            // Certificate problems are configuration errors: retrying will not fix them.
            if (ex instanceof javax.net.ssl.SSLException
                    && Redactor.hasCause(ex, java.security.cert.CertificateException.class)) {
                return DeliveryResult.permanent(null, redactor.describe(ex));
            }
            return DeliveryResult.retryable(null, redactor.describe(ex));
        } catch (RuntimeException ex) {
            return DeliveryResult.retryable(null, redactor.describe(ex));
        }
    }

    protected Redactor redactor() {
        return redactor;
    }
}
