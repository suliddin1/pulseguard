package com.pulseguard.scheduler.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Component
public class SchedulerMetrics {

    private final AtomicLong scheduledCount = new AtomicLong();
    private final AtomicLong startedCount = new AtomicLong();
    private final AtomicLong completedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();
    private final AtomicLong skippedCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();

    private final Counter micrometerScheduled;
    private final Counter micrometerStarted;
    private final Counter micrometerCompleted;
    private final Counter micrometerFailed;
    private final Counter micrometerSkipped;
    private final Counter micrometerRejected;

    @Autowired
    public SchedulerMetrics(@Autowired(required = false) MeterRegistry meterRegistry) {
        if (meterRegistry != null) {
            this.micrometerScheduled = Counter.builder("pulseguard.scheduler.checks.scheduled")
                    .description("Total number of health check tasks scheduled")
                    .register(meterRegistry);
            this.micrometerStarted = Counter.builder("pulseguard.scheduler.checks.started")
                    .description("Total number of health check tasks started execution")
                    .register(meterRegistry);
            this.micrometerCompleted = Counter.builder("pulseguard.scheduler.checks.completed")
                    .description("Total number of health check tasks successfully completed")
                    .register(meterRegistry);
            this.micrometerFailed = Counter.builder("pulseguard.scheduler.checks.failed")
                    .description("Total number of health check tasks that threw an unhandled error")
                    .register(meterRegistry);
            this.micrometerSkipped = Counter.builder("pulseguard.scheduler.checks.skipped")
                    .description("Total number of checks skipped because a check for the service was already running")
                    .register(meterRegistry);
            this.micrometerRejected = Counter.builder("pulseguard.scheduler.checks.rejected")
                    .description("Total number of checks rejected due to executor capacity exhaustion")
                    .register(meterRegistry);
        } else {
            this.micrometerScheduled = null;
            this.micrometerStarted = null;
            this.micrometerCompleted = null;
            this.micrometerFailed = null;
            this.micrometerSkipped = null;
            this.micrometerRejected = null;
        }
    }

    public void registerInFlightGauge(MeterRegistry meterRegistry, Supplier<Number> inFlightSupplier) {
        if (meterRegistry != null) {
            Gauge.builder("pulseguard.scheduler.checks.in_flight", inFlightSupplier)
                    .description("Current number of active health checks executing concurrently")
                    .register(meterRegistry);
        }
    }

    public void recordScheduled() {
        scheduledCount.incrementAndGet();
        if (micrometerScheduled != null) {
            micrometerScheduled.increment();
        }
    }

    public void recordStarted() {
        startedCount.incrementAndGet();
        if (micrometerStarted != null) {
            micrometerStarted.increment();
        }
    }

    public void recordCompleted() {
        completedCount.incrementAndGet();
        if (micrometerCompleted != null) {
            micrometerCompleted.increment();
        }
    }

    public void recordFailed() {
        failedCount.incrementAndGet();
        if (micrometerFailed != null) {
            micrometerFailed.increment();
        }
    }

    public void recordSkippedAlreadyRunning() {
        skippedCount.incrementAndGet();
        if (micrometerSkipped != null) {
            micrometerSkipped.increment();
        }
    }

    public void recordRejectedCapacityExhausted() {
        rejectedCount.incrementAndGet();
        if (micrometerRejected != null) {
            micrometerRejected.increment();
        }
    }

    public long getScheduledCount() {
        return scheduledCount.get();
    }

    public long getStartedCount() {
        return startedCount.get();
    }

    public long getCompletedCount() {
        return completedCount.get();
    }

    public long getFailedCount() {
        return failedCount.get();
    }

    public long getSkippedCount() {
        return skippedCount.get();
    }

    public long getRejectedCount() {
        return rejectedCount.get();
    }
}
