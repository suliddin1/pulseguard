package com.pulseguard.notification.service;

/**
 * The outbox write failed. Deliberately not swallowed by incident evaluation: the incident change, the
 * health check and the outbox rows share one transaction, so failing here rolls everything back rather than
 * silently losing an alert.
 */
public class NotificationEnqueueException extends RuntimeException {

    public NotificationEnqueueException(String message, Throwable cause) {
        super(message, cause);
    }
}
