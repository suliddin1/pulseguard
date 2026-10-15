package com.pulseguard.incident.model;

import com.pulseguard.service.model.MonitoredService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncidentSeverityEscalationTest {

    private MonitoredService service;
    private Incident incident;

    @BeforeEach
    void setUp() {
        service = new MonitoredService("Payments API", "Billing", "https://payments.com", 30, 3000);
        incident = new Incident(
                service,
                IncidentType.HIGH_LATENCY,
                IncidentSeverity.WARNING,
                "High latency on Payments API",
                "Initial p95=300ms",
                Instant.now().minusSeconds(300)
        );
    }

    @Test
    @DisplayName("Escalates severity from WARNING to CRITICAL and updates details")
    void escalateFromWarningToCritical() {
        assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.WARNING);

        Instant now = Instant.now();
        incident.escalate(IncidentSeverity.CRITICAL, now, "Latency exceeded critical threshold: p95=1200ms");

        assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(incident.getDetails()).isEqualTo("Latency exceeded critical threshold: p95=1200ms");
        assertThat(incident.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("Escalation cannot downgrade CRITICAL back to WARNING (severity monotonicity)")
    void cannotDowngradeCriticalToWarning() {
        incident.escalate(IncidentSeverity.CRITICAL, Instant.now(), "Escalated to critical");
        assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);

        // Attempt downgrade
        incident.escalate(IncidentSeverity.WARNING, Instant.now(), "Attempted downgrade");

        // Must remain CRITICAL
        assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(incident.getDetails()).isEqualTo("Escalated to critical");
    }

    @Test
    @DisplayName("Cannot escalate an incident that is already RESOLVED")
    void cannotEscalateResolvedIncident() {
        incident.resolve(Instant.now(), "Resolved");

        assertThatThrownBy(() -> incident.escalate(IncidentSeverity.CRITICAL, Instant.now(), "Escalate after resolve"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot escalate a non-open incident");
    }
}
