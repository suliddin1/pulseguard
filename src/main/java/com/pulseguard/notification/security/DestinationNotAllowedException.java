package com.pulseguard.notification.security;

/** The destination violates the outbound security policy (scheme, address range, host rules). */
public class DestinationNotAllowedException extends PermanentDeliveryException {

    public DestinationNotAllowedException(String message) {
        super(message);
    }
}
