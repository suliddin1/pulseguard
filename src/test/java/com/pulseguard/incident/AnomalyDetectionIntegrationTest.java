package com.pulseguard.incident;

import com.pulseguard.healthcheck.model.HealthCheck;
import com.pulseguard.healthcheck.model.HealthCheckResult;
import com.pulseguard.healthcheck.prober.HttpHealthProber;
import com.pulseguard.healthcheck.prober.ProbeResult;
import com.pulseguard.healthcheck.repository.HealthCheckRepository;
import com.pulseguard.healthcheck.service.HealthCheckExecutionService;
import com.pulseguard.incident.config.AnomalyProperties;
import com.pulseguard.incident.model.Incident;
import com.pulseguard.incident.model.IncidentSeverity;
import com.pulseguard.incident.model.IncidentStatus;
import com.pulseguard.incident.model.IncidentType;
import com.pulseguard.incident.repository.IncidentRepository;
import com.pulseguard.notification.config.NotificationProperties;
import com.pulseguard.notification.model.NotificationEventType;
import com.pulseguard.notification.model.NotificationOutbox;
import com.pulseguard.notification.repository.NotificationOutboxRepository;
import com.pulseguard.service.dto.CreateServiceRequest;
import com.pulseguard.service.dto.ServiceResponse;
import com.pulseguard.service.model.MonitoredService;
import com.pulseguard.service.repository.ServiceRepository;
import com.pulseguard.service.service.ServiceManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class AnomalyDetectionIntegrationTest {

    @Autowired
    private ServiceManagementService serviceManagementService;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private HealthCheckExecutionService healthCheckExecutionService;

    @Autowired
    private HealthCheckRepository healthCheckRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private NotificationOutboxRepository notificationOutboxRepository;

    @Autowired
    private NotificationProperties notificationProperties;

    @Autowired
    private AnomalyProperties anomalyProperties;

    @MockBean
    private HttpHealthProber httpHealthProber;

    private UUID serviceId;
    private MonitoredService serviceEntity;

    @BeforeEach
    void setUp() {
        anomalyProperties.setEnabled(true);
        anomalyProperties.setObservationWindow(Duration.ofSeconds(2));
        anomalyProperties.setBaselineWindow(Duration.ofHours(1));
        anomalyProperties.setMinSamples(5);
        anomalyProperties.setConsecutiveBreachesThreshold(2);
        anomalyProperties.setConsecutiveSuccessesThreshold(2);
        anomalyProperties.setEscalationConsecutiveBreachesThreshold(3);
        anomalyProperties.setLatencyRegressionMultiplier(2.0);
        anomalyProperties.setMinAnomalyLatencyMs(150);
        anomalyProperties.setLatencyThresholdMs(2000);
        anomalyProperties.setCriticalLatencyMultiplier(3.5);

        notificationProperties.getWebhook().setEnabled(true);
        notificationProperties.getWebhook().setUrl("https://webhook.example.com/alerts");

        CreateServiceRequest createRequest = new CreateServiceRequest(
                "Orders Search API",
                "Elasticsearch proxy",
                "https://search.example.com/health",
                30,
                3000
        );
        ServiceResponse response = serviceManagementService.createService(createRequest);
        serviceId = response.id();
        serviceEntity = serviceRepository.findById(serviceId).orElseThrow();

        // Seed 6 baseline health checks in the isolated baseline window (10 minutes ago)
        Instant baselineTime = Instant.now().minus(Duration.ofMinutes(10));
        for (int i = 0; i < 6; i++) {
            healthCheckRepository.save(new HealthCheck(
                    serviceEntity,
                    baselineTime.plusSeconds(i * 10),
                    100L,
                    200,
                    HealthCheckResult.SUCCESS,
                    null
            ));
        }
    }

    @Test
    @DisplayName("End-to-end anomaly detection: regression triggers WARNING incident, sustained degradation escalates to CRITICAL, and healthy checks resolve incident")
    void fullAnomalyLifecycleIntegration() throws InterruptedException {
        // Step 1: Pre-populate observation window with 5 healthy checks (100ms)
        when(httpHealthProber.probe(anyString(), anyInt()))
                .thenReturn(ProbeResult.success(100L, 200));

        for (int i = 0; i < 5; i++) {
            healthCheckExecutionService.executeHealthCheck(serviceId);
        }

        // Verify no incident yet
        assertThat(incidentRepository.findByServiceIdAndStatus(serviceId, IncidentStatus.OPEN)).isEmpty();

        // Step 2: Latency regression phase — slow successful checks (250ms, 2.5x baseline p95)
        // 2.5x > 2.0x regression multiplier, but < 3.5x critical multiplier -> triggers WARNING
        when(httpHealthProber.probe(anyString(), anyInt()))
                .thenReturn(ProbeResult.success(250L, 200));

        // 1st degraded check -> breach streak = 1 (< threshold 2)
        healthCheckExecutionService.executeHealthCheck(serviceId);
        assertThat(incidentRepository.findByServiceIdAndStatus(serviceId, IncidentStatus.OPEN)).isEmpty();

        // 2nd degraded check -> breach streak = 2 (>= threshold 2) -> HIGH_LATENCY incident opened!
        healthCheckExecutionService.executeHealthCheck(serviceId);

        List<Incident> openIncidents = incidentRepository.findByServiceIdAndStatus(serviceId, IncidentStatus.OPEN);
        assertThat(openIncidents).hasSize(1);
        Incident incident = openIncidents.get(0);
        assertThat(incident.getIncidentType()).isEqualTo(IncidentType.HIGH_LATENCY);
        assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.WARNING);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);

        // Step 3: Sustained degradation phase -> escalate to CRITICAL
        // 3rd degraded check -> occurrence count 2 (< escalation threshold 3)
        healthCheckExecutionService.executeHealthCheck(serviceId);
        Incident after3rd = incidentRepository.findById(incident.getId()).orElseThrow();
        assertThat(after3rd.getSeverity()).isEqualTo(IncidentSeverity.WARNING);

        // 4th degraded check -> occurrence count 3 (>= escalation threshold 3) -> escalates to CRITICAL!
        healthCheckExecutionService.executeHealthCheck(serviceId);
        Incident after4th = incidentRepository.findById(incident.getId()).orElseThrow();
        assertThat(after4th.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);

        // Step 4: Recovery phase — age out degraded checks from observation window
        Thread.sleep(2200);

        when(httpHealthProber.probe(anyString(), anyInt()))
                .thenReturn(ProbeResult.success(100L, 200));

        // Execute 5 healthy checks to establish the new healthy observation window
        for (int i = 0; i < 5; i++) {
            healthCheckExecutionService.executeHealthCheck(serviceId);
        }

        // Execute recovery streak check (consecutiveSuccessesThreshold is 2)
        healthCheckExecutionService.executeHealthCheck(serviceId);

        // Incident must now be RESOLVED
        List<Incident> openAfterRecovery = incidentRepository.findByServiceIdAndStatus(serviceId, IncidentStatus.OPEN);
        assertThat(openAfterRecovery).isEmpty();

        Incident resolvedIncident = incidentRepository.findById(incident.getId()).orElseThrow();
        assertThat(resolvedIncident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(resolvedIncident.getResolvedAt()).isNotNull();

        // Step 5: Verify outbox delivery queue contains OPENED, ESCALATED, and RESOLVED events
        List<NotificationOutbox> outboxRows = notificationOutboxRepository.findByIncidentIdOrderByCreatedAtAsc(incident.getId());
        List<NotificationEventType> recordedEvents = outboxRows.stream()
                .map(NotificationOutbox::getEventType)
                .toList();

        assertThat(recordedEvents).contains(
                NotificationEventType.INCIDENT_OPENED,
                NotificationEventType.INCIDENT_ESCALATED,
                NotificationEventType.INCIDENT_RESOLVED
        );
    }
}
