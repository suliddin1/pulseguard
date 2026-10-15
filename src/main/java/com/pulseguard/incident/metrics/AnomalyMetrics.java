package com.pulseguard.incident.metrics;

import com.pulseguard.incident.model.IncidentType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class AnomalyMetrics {

    private final MeterRegistry registry;

    public AnomalyMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordEvaluation(String outcome) {
        if (registry == null) return;
        Counter.builder("pulseguard.anomaly.evaluations.total")
                .description("Total number of anomaly evaluations executed")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void recordAnomalyDetected(IncidentType type) {
        if (registry == null) return;
        Counter.builder("pulseguard.anomaly.detected.total")
                .description("Total number of anomalies detected")
                .tag("type", type.name())
                .register(registry)
                .increment();
    }

    public void recordEscalation(IncidentType type) {
        if (registry == null) return;
        Counter.builder("pulseguard.anomaly.escalations.total")
                .description("Total number of incident severity escalations")
                .tag("type", type.name())
                .register(registry)
                .increment();
    }

    public void recordInsufficientData() {
        if (registry == null) return;
        Counter.builder("pulseguard.anomaly.insufficient_data.total")
                .description("Total evaluations where sample count was below threshold")
                .register(registry)
                .increment();
    }

    public void recordEvaluationFailure() {
        if (registry == null) return;
        Counter.builder("pulseguard.anomaly.evaluation.failures.total")
                .description("Total number of anomaly evaluation execution failures")
                .register(registry)
                .increment();
    }
}
