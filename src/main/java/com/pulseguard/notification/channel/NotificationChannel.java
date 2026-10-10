package com.pulseguard.notification.channel;

import com.pulseguard.notification.model.NotificationChannelType;

/**
 * A destination for notifications. Implementations must be thread-safe, must never throw from
 * {@link #deliver(NotificationMessage)} (return a {@link DeliveryResult} instead) and must never put
 * secrets, URLs or headers into results or logs.
 *
 * <p>To add a channel (e.g. Email, PagerDuty Events API v2): add a value to {@link NotificationChannelType},
 * widen the CHECK constraint in a new Flyway migration, implement this interface as a Spring bean and add its
 * properties. The dispatcher, outbox, retry and metrics pick it up through the registry.
 */
public interface NotificationChannel {

    NotificationChannelType type();

    boolean isEnabled();

    DeliveryResult deliver(NotificationMessage message);
}
