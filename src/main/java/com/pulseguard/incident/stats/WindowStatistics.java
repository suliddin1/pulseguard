package com.pulseguard.incident.stats;

import java.time.Instant;

/**
 * Immutable snapshot of rolling health check metrics over a specific time window.
 */
public record WindowStatistics(
        Instant windowStart,
        Instant windowEnd,
        int totalChecks,
        int successChecks,
        int failureChecks,
        double errorRate,
        double p95LatencyMs,
        double p99LatencyMs,
        boolean sufficientData
) {

    public static WindowStatistics insufficient(Instant windowStart, Instant windowEnd, int totalChecks) {
        return new WindowStatistics(windowStart, windowEnd, totalChecks, 0, 0, 0.0, 0.0, 0.0, false);
    }

    public static WindowStatistics of(
            Instant windowStart,
            Instant windowEnd,
            int totalChecks,
            int successChecks,
            int failureChecks,
            double errorRate,
            double p95LatencyMs,
            double p99LatencyMs
    ) {
        return new WindowStatistics(
                windowStart,
                windowEnd,
                totalChecks,
                successChecks,
                failureChecks,
                errorRate,
                p95LatencyMs,
                p99LatencyMs,
                true
        );
    }

    public boolean hasSufficientData() {
        return sufficientData;
    }
}
