package com.pulseguard.notification.http;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/**
 * Minimal outbound HTTP abstraction used by notification channels. Implementations must enforce the
 * destination security policy and must never follow redirects.
 */
public interface OutboundHttpClient {

    /**
     * POSTs {@code body} and returns the response status. The response body is not read.
     *
     * @throws java.io.IOException on connection/TLS/timeout failures (retryable unless it is a
     *                             {@link com.pulseguard.notification.security.PermanentDeliveryException})
     */
    int post(URI uri, Map<String, String> headers, byte[] body) throws IOException;
}
