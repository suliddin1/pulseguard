package com.pulseguard.notification.security;

import java.io.IOException;

/**
 * A delivery failure that will never succeed on retry (policy violation, oversized payload, bad URL, ...).
 * Messages must be safe to persist and log: they must never contain URLs, hosts-with-credentials or headers.
 */
public class PermanentDeliveryException extends IOException {

    public PermanentDeliveryException(String message) {
        super(message);
    }
}
