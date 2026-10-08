package com.pulseguard.scheduler.service;

import com.pulseguard.service.model.MonitoredService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DueCheckEvaluatorTest {

    private DueCheckEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new DefaultDueCheckEvaluator();
    }

    @Test
    @DisplayName("isDue: Service is due immediately when never checked before")
    void isDue_WhenNeverChecked_ReturnsTrue() {
        MonitoredService service = new MonitoredService(
                "Fresh Service",
                null,
                "https://fresh.example.com",
                60,
                5000
        );

        boolean due = evaluator.isDue(service, null, Instant.now());
        assertThat(due).isTrue();
    }

    @Test
    @DisplayName("isDue: Service is not due when disabled, even if never checked")
    void isDue_WhenDisabled_ReturnsFalse() {
        MonitoredService service = new MonitoredService(
                "Disabled Service",
                null,
                "https://disabled.example.com",
                60,
                5000
        );
        service.disable();

        boolean due = evaluator.isDue(service, null, Instant.now());
        assertThat(due).isFalse();
    }

    @Test
    @DisplayName("isDue: Service is not due when check interval has not elapsed")
    void isDue_WhenIntervalNotElapsed_ReturnsFalse() {
        MonitoredService service = new MonitoredService(
                "Active Service",
                null,
                "https://active.example.com",
                60, // 60s interval
                5000
        );

        Instant now = Instant.now();
        Instant lastCheckedAt = now.minus(20, ChronoUnit.SECONDS); // checked 20s ago

        boolean due = evaluator.isDue(service, lastCheckedAt, now);
        assertThat(due).isFalse();
    }

    @Test
    @DisplayName("isDue: Service is due when check interval has elapsed")
    void isDue_WhenIntervalElapsed_ReturnsTrue() {
        MonitoredService service = new MonitoredService(
                "Active Service",
                null,
                "https://active.example.com",
                60, // 60s interval
                5000
        );

        Instant now = Instant.now();
        Instant lastCheckedAt = now.minus(65, ChronoUnit.SECONDS); // checked 65s ago

        boolean due = evaluator.isDue(service, lastCheckedAt, now);
        assertThat(due).isTrue();
    }

    @Test
    @DisplayName("isDue: Service is due on exact interval boundary")
    void isDue_OnExactBoundary_ReturnsTrue() {
        MonitoredService service = new MonitoredService(
                "Boundary Service",
                null,
                "https://boundary.example.com",
                30,
                5000
        );

        Instant now = Instant.now();
        Instant lastCheckedAt = now.minus(30, ChronoUnit.SECONDS);

        boolean due = evaluator.isDue(service, lastCheckedAt, now);
        assertThat(due).isTrue();
    }

    @Test
    @DisplayName("isDue: Returns false when service is null")
    void isDue_WhenNullService_ReturnsFalse() {
        boolean due = evaluator.isDue(null, null, Instant.now());
        assertThat(due).isFalse();
    }

    @Test
    @DisplayName("isDue: Evaluates services with different intervals independently")
    void isDue_DifferentIntervals() {
        MonitoredService fastService = new MonitoredService(
                "Fast Service", null, "https://fast.example.com", 15, 3000
        );
        MonitoredService slowService = new MonitoredService(
                "Slow Service", null, "https://slow.example.com", 300, 5000
        );

        Instant now = Instant.now();
        Instant checked30sAgo = now.minus(30, ChronoUnit.SECONDS);

        assertThat(evaluator.isDue(fastService, checked30sAgo, now)).isTrue();
        assertThat(evaluator.isDue(slowService, checked30sAgo, now)).isFalse();
    }

    @Test
    @DisplayName("calculateNextDueTime: Correctly offsets from last check time")
    void calculateNextDueTime() {
        MonitoredService service = new MonitoredService(
                "Test Service", null, "https://test.example.com", 45, 3000
        );

        Instant now = Instant.now();
        Instant nextDueNeverChecked = evaluator.calculateNextDueTime(service, null, now);
        assertThat(nextDueNeverChecked).isEqualTo(now);

        Instant lastCheck = now.minus(10, ChronoUnit.SECONDS);
        Instant nextDue = evaluator.calculateNextDueTime(service, lastCheck, now);
        assertThat(nextDue).isEqualTo(lastCheck.plusSeconds(45));
    }
}
