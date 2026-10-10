package com.pulseguard.incident.model;

import com.pulseguard.service.model.MonitoredService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncidentTest {

    @Test
    @DisplayName("Constructor sets valid initial state and defaults")
    void constructorInitialState() {
        MonitoredService service = new MonitoredService("Payments", "API", "https://pay.com", 30, 3000);
        Instant startedAt = Instant.now().minusSeconds(10);

        Incident incident = new Incident(
                service,
                IncidentType.SERVICE_UNAVAILABLE,
                IncidentSeverity.CRITICAL,
                "Service unavailable",
                "HTTP 500 error",
                startedAt
        );

        assertThat(incident.getId()).isNotNull();
        assertThat(incident.getService()).isEqualTo(service);
        assertThat(incident.getIncidentType()).isEqualTo(IncidentType.SERVICE_UNAVAILABLE);
        assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.isOpen()).isTrue();
        assertThat(incident.isResolved()).isFalse();
        assertThat(incident.getSummary()).isEqualTo("Service unavailable");
        assertThat(incident.getDetails()).isEqualTo("HTTP 500 error");
        assertThat(incident.getStartedAt()).isEqualTo(startedAt);
        assertThat(incident.getLastOccurrenceAt()).isEqualTo(startedAt);
        assertThat(incident.getResolvedAt()).isNull();
        assertThat(incident.getOccurrenceCount()).isEqualTo(1L);
        assertThat(incident.getCreatedAt()).isNotNull();
        assertThat(incident.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("recordOccurrence increments count and updates lastOccurrenceAt and details")
    void recordOccurrenceUpdatesState() {
        MonitoredService service = new MonitoredService("Payments", "API", "https://pay.com", 30, 3000);
        Incident incident = new Incident(
                service,
                IncidentType.SERVICE_UNAVAILABLE,
                IncidentSeverity.CRITICAL,
                "Summary",
                "Initial error",
                Instant.now().minusSeconds(60)
        );

        Instant occurrenceTime = Instant.now();
        incident.recordOccurrence(occurrenceTime, "Updated error details");

        assertThat(incident.getOccurrenceCount()).isEqualTo(2L);
        assertThat(incident.getLastOccurrenceAt()).isEqualTo(occurrenceTime);
        assertThat(incident.getDetails()).isEqualTo("Updated error details");
        assertThat(incident.isOpen()).isTrue();
    }

    @Test
    @DisplayName("resolve transitions status to RESOLVED and sets resolvedAt")
    void resolveTransitionsState() {
        MonitoredService service = new MonitoredService("Payments", "API", "https://pay.com", 30, 3000);
        Incident incident = new Incident(
                service,
                IncidentType.SERVICE_UNAVAILABLE,
                IncidentSeverity.CRITICAL,
                "Summary",
                "Initial error",
                Instant.now().minusSeconds(120)
        );

        Instant resolutionTime = Instant.now();
        incident.resolve(resolutionTime, "Recovered after HTTP 200 OK");

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incident.isOpen()).isFalse();
        assertThat(incident.isResolved()).isTrue();
        assertThat(incident.getResolvedAt()).isEqualTo(resolutionTime);
        assertThat(incident.getDetails()).isEqualTo("Recovered after HTTP 200 OK");
    }

    @Test
    @DisplayName("Constructor enforces required parameters")
    void constructorEnforcesParameters() {
        MonitoredService service = new MonitoredService("Payments", "API", "https://pay.com", 30, 3000);

        assertThatThrownBy(() -> new Incident(null, IncidentType.SERVICE_UNAVAILABLE, IncidentSeverity.CRITICAL, "Summary", "Details", Instant.now()))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new Incident(service, null, IncidentSeverity.CRITICAL, "Summary", "Details", Instant.now()))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new Incident(service, IncidentType.SERVICE_UNAVAILABLE, null, "Summary", "Details", Instant.now()))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new Incident(service, IncidentType.SERVICE_UNAVAILABLE, IncidentSeverity.CRITICAL, null, "Details", Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Summary and details are truncated when exceeding maximum column lengths")
    void truncatesLengthyFields() {
        MonitoredService service = new MonitoredService("Payments", "API", "https://pay.com", 30, 3000);
        String longSummary = "S".repeat(300);
        String longDetails = "D".repeat(2500);

        Incident incident = new Incident(
                service,
                IncidentType.SERVICE_UNAVAILABLE,
                IncidentSeverity.CRITICAL,
                longSummary,
                longDetails,
                Instant.now()
        );

        assertThat(incident.getSummary()).hasSize(255);
        assertThat(incident.getSummary()).endsWith("...");
        assertThat(incident.getDetails()).hasSize(2000);
        assertThat(incident.getDetails()).endsWith("...");
    }
}
