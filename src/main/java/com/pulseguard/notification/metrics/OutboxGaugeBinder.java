package com.pulseguard.notification.metrics;

import com.pulseguard.notification.model.OutboxStatus;
import com.pulseguard.notification.repository.NotificationOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Backlog gauges, sampled from the database at scrape time. A failing query yields NaN, never an exception. */
@Component
public class OutboxGaugeBinder implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(OutboxGaugeBinder.class);

    private final NotificationOutboxRepository repository;

    public OutboxGaugeBinder(NotificationOutboxRepository repository) {
        this.repository = repository;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("pulseguard.notifications.outbox.pending", () -> count(OutboxStatus.PENDING))
                .description("Outbox rows waiting for delivery (including scheduled retries)")
                .register(registry);
        Gauge.builder("pulseguard.notifications.outbox.dead", () -> count(OutboxStatus.DEAD))
                .description("Dead-lettered outbox rows (delivery abandoned)")
                .register(registry);
    }

    private double count(OutboxStatus status) {
        try {
            return repository.countByStatus(status);
        } catch (RuntimeException ex) {
            log.debug("Could not sample outbox gauge for status {}: {}", status, ex.getClass().getSimpleName());
            return Double.NaN;
        }
    }
}
