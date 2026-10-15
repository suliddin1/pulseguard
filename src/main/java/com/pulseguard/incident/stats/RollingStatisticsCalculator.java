package com.pulseguard.incident.stats;

import com.pulseguard.healthcheck.model.HealthCheck;
import com.pulseguard.healthcheck.model.HealthCheckResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Calculates rolling health check metrics (p95, p99, error rate) over a historical observation window.
 */
public final class RollingStatisticsCalculator {

    private RollingStatisticsCalculator() {
    }

    /**
     * Aggregates health checks into window statistics.
     *
     * @param checks non-null list of health check records in the window
     * @param windowStart start timestamp of the window
     * @param windowEnd end timestamp of the window
     * @param minSamples minimum required checks to produce a valid statistical result
     * @return {@link WindowStatistics} containing computed metrics or marked as insufficient
     */
    public static WindowStatistics calculate(
            List<HealthCheck> checks,
            Instant windowStart,
            Instant windowEnd,
            int minSamples
    ) {
        if (checks == null || checks.size() < minSamples) {
            int count = checks != null ? checks.size() : 0;
            return WindowStatistics.insufficient(windowStart, windowEnd, count);
        }

        int total = checks.size();
        int failures = 0;
        int successes = 0;
        List<Long> latencies = new ArrayList<>();

        for (HealthCheck check : checks) {
            if (check.getResult() == HealthCheckResult.SUCCESS) {
                successes++;
                latencies.add(Math.max(0L, check.getResponseTimeMs()));
            } else {
                failures++;
            }
        }

        double errorRate = total > 0 ? (double) failures / (double) total : 0.0;
        Collections.sort(latencies);

        double p95 = 0.0;
        double p99 = 0.0;
        if (!latencies.isEmpty()) {
            p95 = PercentileCalculator.calculatePercentile(latencies, 95.0);
            p99 = PercentileCalculator.calculatePercentile(latencies, 99.0);
        }

        return WindowStatistics.of(
                windowStart,
                windowEnd,
                total,
                successes,
                failures,
                errorRate,
                p95,
                p99
        );
    }
}
