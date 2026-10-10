package com.pulseguard.incident.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Component
public class IncidentMetrics {

    private final AtomicLong createdCount = new AtomicLong(0);
    private final AtomicLong resolvedCount = new AtomicLong(0);
    private final AtomicLong occurrencesCount = new AtomicLong(0);
    private final AtomicLong failuresCount = new AtomicLong(0);

    private final Counter createdCounter;
    private final Counter resolvedCounter;
    private final Counter occurrencesCounter;
    private final Counter failuresCounter;

    public IncidentMetrics(@Autowired(required = false) MeterRegistry meterRegistry) {
        if (meterRegistry != null) {
            this.createdCounter = Counter.builder("pulseguard.incidents.created")
                    .description("Total number of incidents created")
                    .register(meterRegistry);
            this.resolvedCounter = Counter.builder("pulseguard.incidents.resolved")
                    .description("Total number of incidents resolved")
                    .register(meterRegistry);
            this.occurrencesCounter = Counter.builder("pulseguard.incidents.occurrences")
                    .description("Total number of incident occurrences recorded")
                    .register(meterRegistry);
            this.failuresCounter = Counter.builder("pulseguard.incidents.failures")
                    .description("Total number of incident detection processing failures")
                    .register(meterRegistry);
        } else {
            this.createdCounter = null;
            this.resolvedCounter = null;
            this.occurrencesCounter = null;
            this.failuresCounter = null;
        }
    }

    public void registerOpenIncidentsGauge(MeterRegistry meterRegistry, Supplier<Number> openIncidentsSupplier) {
        if (meterRegistry != null && openIncidentsSupplier != null) {
            Gauge.builder("pulseguard.incidents.open", openIncidentsSupplier)
                    .description("Current number of open incidents")
                    .register(meterRegistry);
        }
    }

    public void recordCreated() {
        createdCount.incrementAndGet();
        if (createdCounter != null) {
            createdCounter.increment();
        }
    }

    public void recordResolved() {
        resolvedCount.incrementAndGet();
        if (resolvedCounter != null) {
            resolvedCounter.increment();
        }
    }

    public void recordOccurrence() {
        occurrencesCount.incrementAndGet();
        if (occurrencesCounter != null) {
            occurrencesCounter.increment();
        }
    }

    public void recordProcessingFailure() {
        failuresCount.incrementAndGet();
        if (failuresCounter != null) {
            failuresCounter.increment();
        }
    }

    public long getCreatedCount() {
        return createdCount.get();
    }

    public long getResolvedCount() {
        return resolvedCount.get();
    }

    public long getOccurrencesCount() {
        return occurrencesCount.get();
    }

    public long getFailuresCount() {
        return failuresCount.get();
    }

    public void reset() {
        createdCount.set(0);
        resolvedCount.set(0);
        occurrencesCount.set(0);
        failuresCount.set(0);
    }
}
