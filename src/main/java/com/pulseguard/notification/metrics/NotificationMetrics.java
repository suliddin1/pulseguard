package com.pulseguard.notification.metrics;

import com.pulseguard.notification.model.NotificationChannelType;
import com.pulseguard.notification.model.NotificationEventType;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Notification metrics. Tags are restricted to small fixed vocabularies (channel, event type, failure kind,
 * dead-letter reason): never service names, incident ids, URLs or error text.
 */
@Component
public class NotificationMetrics {

    /** Fixed dead-letter reason vocabulary. */
    public enum DeadReason {
        RETRIES_EXHAUSTED,
        PERMANENT_FAILURE,
        CHANNEL_DISABLED,
        PAYLOAD_UNREADABLE,
        LEASE_ATTEMPTS_EXCEEDED
    }

    private final MeterRegistry registry;

    public NotificationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void queued(NotificationChannelType channel, NotificationEventType eventType) {
        registry.counter("pulseguard.notifications.queued",
                "channel", channel.name(), "event_type", eventType.name()).increment();
    }

    public void attempt(NotificationChannelType channel) {
        registry.counter("pulseguard.notifications.attempts", "channel", channel.name()).increment();
    }

    public void delivered(NotificationChannelType channel) {
        registry.counter("pulseguard.notifications.delivered", "channel", channel.name()).increment();
    }

    /** A failed attempt; {@code retryable} distinguishes transient from permanent failures. */
    public void failed(NotificationChannelType channel, boolean retryable) {
        registry.counter("pulseguard.notifications.failed",
                "channel", channel.name(), "kind", retryable ? "retryable" : "permanent").increment();
    }

    public void retry(NotificationChannelType channel) {
        registry.counter("pulseguard.notifications.retries", "channel", channel.name()).increment();
    }

    public void dead(NotificationChannelType channel, DeadReason reason) {
        registry.counter("pulseguard.notifications.dead",
                "channel", channel.name(), "reason", reason.name().toLowerCase(java.util.Locale.ROOT)).increment();
    }

    public void rateLimited(NotificationChannelType channel) {
        registry.counter("pulseguard.notifications.rate_limited", "channel", channel.name()).increment();
    }

    public void enqueueFailure() {
        registry.counter("pulseguard.notifications.enqueue_failures").increment();
    }
}
