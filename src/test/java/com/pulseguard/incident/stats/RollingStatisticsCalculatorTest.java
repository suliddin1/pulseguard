package com.pulseguard.incident.stats;

import com.pulseguard.healthcheck.model.HealthCheck;
import com.pulseguard.healthcheck.model.HealthCheckResult;
import com.pulseguard.service.model.MonitoredService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RollingStatisticsCalculatorTest {

    private MonitoredService service;
    private Instant start;
    private Instant end;

    @BeforeEach
    void setUp() {
        service = new MonitoredService("Analytics API", "Stats", "https://stats.com", 30, 3000);
        end = Instant.now();
        start = end.minusSeconds(900);
    }

    @Test
    @DisplayName("Returns insufficientData when sample count is less than minSamples")
    void insufficientDataWhenLessThanMinSamples() {
        List<HealthCheck> checks = List.of(
                new HealthCheck(service, 50, 200, HealthCheckResult.SUCCESS, null),
                new HealthCheck(service, 60, 200, HealthCheckResult.SUCCESS, null)
        );

        WindowStatistics stats = RollingStatisticsCalculator.calculate(checks, start, end, 10);
        assertThat(stats.hasSufficientData()).isFalse();
        assertThat(stats.totalChecks()).isEqualTo(2);
        assertThat(stats.errorRate()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Calculates zero error rate and accurate p95 when all checks succeed")
    void allSuccessfulChecks() {
        List<HealthCheck> checks = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            checks.add(new HealthCheck(service, i * 10L, 200, HealthCheckResult.SUCCESS, null));
        }

        WindowStatistics stats = RollingStatisticsCalculator.calculate(checks, start, end, 10);
        assertThat(stats.hasSufficientData()).isTrue();
        assertThat(stats.totalChecks()).isEqualTo(20);
        assertThat(stats.successChecks()).isEqualTo(20);
        assertThat(stats.failureChecks()).isEqualTo(0);
        assertThat(stats.errorRate()).isEqualTo(0.0);
        // 20 values: 10, 20, ..., 200.
        // idx = 0.95 * 19 = 18.05. lower=18 (190), upper=19 (200). 190 + 0.05 * 10 = 190.5
        assertThat(stats.p95LatencyMs()).isCloseTo(190.5, within(0.01));
    }

    @Test
    @DisplayName("Calculates 100% error rate and 0 latency when all checks fail")
    void allFailedChecks() {
        List<HealthCheck> checks = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            checks.add(new HealthCheck(service, 0, 500, HealthCheckResult.FAILURE, "Internal Error"));
        }

        WindowStatistics stats = RollingStatisticsCalculator.calculate(checks, start, end, 10);
        assertThat(stats.hasSufficientData()).isTrue();
        assertThat(stats.totalChecks()).isEqualTo(15);
        assertThat(stats.successChecks()).isEqualTo(0);
        assertThat(stats.failureChecks()).isEqualTo(15);
        assertThat(stats.errorRate()).isEqualTo(1.0);
        assertThat(stats.p95LatencyMs()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Calculates correct error rate and latency on mixed success and failure checks")
    void mixedChecks() {
        List<HealthCheck> checks = new ArrayList<>();
        // 16 successes with latencies 100ms
        for (int i = 0; i < 16; i++) {
            checks.add(new HealthCheck(service, 100L, 200, HealthCheckResult.SUCCESS, null));
        }
        // 4 failures
        for (int i = 0; i < 4; i++) {
            checks.add(new HealthCheck(service, 0L, 503, HealthCheckResult.FAILURE, "Unavailable"));
        }

        WindowStatistics stats = RollingStatisticsCalculator.calculate(checks, start, end, 10);
        assertThat(stats.hasSufficientData()).isTrue();
        assertThat(stats.totalChecks()).isEqualTo(20);
        assertThat(stats.successChecks()).isEqualTo(16);
        assertThat(stats.failureChecks()).isEqualTo(4);
        assertThat(stats.errorRate()).isEqualTo(0.20);
        assertThat(stats.p95LatencyMs()).isEqualTo(100.0);
    }
}
