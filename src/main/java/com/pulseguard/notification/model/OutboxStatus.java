package com.pulseguard.notification.model;

public enum OutboxStatus {
    /** Waiting for delivery; becomes eligible when {@code nextAttemptAt} has passed. */
    PENDING,
    /** Claimed by a dispatcher worker under a time-limited lease. */
    PROCESSING,
    /** Destination accepted the notification (2xx). Terminal. */
    DELIVERED,
    /** Permanently failed or retries exhausted (dead letter). Terminal; diagnostics are retained. */
    DEAD
}
