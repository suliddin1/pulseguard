package com.pulseguard.incident.service;

import com.pulseguard.healthcheck.model.HealthCheck;
import com.pulseguard.healthcheck.model.HealthCheckResult;
import com.pulseguard.healthcheck.repository.HealthCheckRepository;
import com.pulseguard.incident.config.AnomalyProperties;
import com.pulseguard.incident.metrics.AnomalyMetrics;
import com.pulseguard.incident.metrics.IncidentMetrics;
import com.pulseguard.incident.model.Incident;
import com.pulseguard.incident.model.IncidentSeverity;
import com.pulseguard.incident.model.IncidentStatus;
import com.pulseguard.incident.model.IncidentType;
import com.pulseguard.incident.repository.IncidentRepository;
import com.pulseguard.notification.service.NotificationEnqueuer;
import com.pulseguard.service.model.MonitoredService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnomalyDetectionServiceTest {

    @Mock
    private HealthCheckRepository healthCheckRepository;

    @Mock
    private IncidentRepository incidentRepository;

    @Mock
    private NotificationEnqueuer notificationEnqueuer;

    private AnomalyProperties anomalyProperties;
    private AnomalyMetrics anomalyMetrics;
    private IncidentMetrics incidentMetrics;
    private AnomalyDetectionServiceImpl anomalyDetectionService;

    private MonitoredService service;

    @BeforeEach
    void setUp() {
        anomalyProperties = new AnomalyProperties();
        anomalyProperties.setEnabled(true);
        anomalyProperties.setMinSamples(10);
        anomalyProperties.setLatencyRegressionMultiplier(2.0);
        anomalyProperties.setMinAnomalyLatencyMs(200);
        anomalyProperties.setLatencyThresholdMs(2000);
        anomalyProperties.setCriticalLatencyMultiplier(3.5);
        anomalyProperties.setCriticalLatencyThresholdMs(5000);
        anomalyProperties.setErrorRateThreshold(0.25);
        anomalyProperties.setCriticalErrorRateThreshold(0.60);
        anomalyProperties.setConsecutiveBreachesThreshold(3);
        anomalyProperties.setConsecutiveSuccessesThreshold(2);
        anomalyProperties.setEscalationConsecutiveBreachesThreshold(4);

        anomalyMetrics = new AnomalyMetrics(null);
        incidentMetrics = new IncidentMetrics(null);

        anomalyDetectionService = new AnomalyDetectionServiceImpl(
                healthCheckRepository,
                incidentRepository,
                notificationEnqueuer,
                anomalyProperties,
                anomalyMetrics,
                incidentMetrics
        );

        service = new MonitoredService("Users API", "Profile service", "https://users.com", 30, 3000);
    }

    @Test
    @DisplayName("Skips anomaly evaluation when anomaly engine is disabled")
    void skipsWhenDisabled() {
        anomalyProperties.setEnabled(false);
        HealthCheck check = new HealthCheck(service, 100, 200, HealthCheckResult.SUCCESS, null);

        anomalyDetectionService.evaluateAnomalies(service, check);

        verify(healthCheckRepository, never()).findByServiceIdAndCheckedAtBetween(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Does not alert when sample size is below minSamples (INSUFFICIENT_DATA)")
    void insufficientDataDoesNotAlert() {
        HealthCheck check = new HealthCheck(service, 1500, 200, HealthCheckResult.SUCCESS, null);

        // Only 3 checks returned in observation window (minSamples is 10)
        when(healthCheckRepository.findByServiceIdAndCheckedAtBetween(eq(service.getId()), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(check, check, check)));

        anomalyDetectionService.evaluateAnomalies(service, check);

        verify(incidentRepository, never()).save(any());
        verify(notificationEnqueuer, never()).incidentOpened(any());
    }

    @Test
    @DisplayName("Latency regression with anti-flapping: breaches below threshold do not create incident; reaching threshold creates WARNING incident")
    void latencyRegressionAntiFlapping() {
        // Baseline: 10 checks at 100ms (p95 = 100ms)
        List<HealthCheck> baselineChecks = createChecks(10, 100, HealthCheckResult.SUCCESS);

        // Current: 10 checks at 250ms (p95 = 250ms, 2.5x > 2x regression, below 3.5x critical)
        List<HealthCheck> anomalousChecks = createChecks(10, 250, HealthCheckResult.SUCCESS);

        when(healthCheckRepository.findByServiceIdAndCheckedAtBetween(eq(service.getId()), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(anomalousChecks))
                .thenReturn(new PageImpl<>(baselineChecks));

        when(incidentRepository.findByServiceIdAndIncidentTypeAndStatus(
                service.getId(), IncidentType.HIGH_LATENCY, IncidentStatus.OPEN))
                .thenReturn(Optional.empty());

        HealthCheck latest = anomalousChecks.get(0);

        // Evaluation 1: breach streak = 1 (< 3)
        anomalyDetectionService.evaluateAnomalies(service, latest);
        verify(incidentRepository, never()).save(any());

        // Evaluation 2: breach streak = 2 (< 3)
        when(healthCheckRepository.findByServiceIdAndCheckedAtBetween(eq(service.getId()), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(anomalousChecks))
                .thenReturn(new PageImpl<>(baselineChecks));
        anomalyDetectionService.evaluateAnomalies(service, latest);
        verify(incidentRepository, never()).save(any());

        // Evaluation 3: breach streak = 3 (>= threshold 3) -> creates WARNING incident!
        when(healthCheckRepository.findByServiceIdAndCheckedAtBetween(eq(service.getId()), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(anomalousChecks))
                .thenReturn(new PageImpl<>(baselineChecks));
        when(incidentRepository.save(any(Incident.class))).thenAnswer(invocation -> invocation.getArgument(0));

        anomalyDetectionService.evaluateAnomalies(service, latest);

        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        Incident created = captor.getValue();
        assertThat(created.getIncidentType()).isEqualTo(IncidentType.HIGH_LATENCY);
        assertThat(created.getSeverity()).isEqualTo(IncidentSeverity.WARNING);
        assertThat(created.getStatus()).isEqualTo(IncidentStatus.OPEN);
        verify(notificationEnqueuer).incidentOpened(created);
    }

    @Test
    @DisplayName("Prolonged latency degradation escalates severity from WARNING to CRITICAL and notifies outbox")
    void prolongedDegradationEscalatesSeverity() {
        Incident openIncident = new Incident(
                service,
                IncidentType.HIGH_LATENCY,
                IncidentSeverity.WARNING,
                "High latency",
                "Details",
                Instant.now().minusSeconds(600)
        );
        // Simulate occurrence count already at 3
        openIncident.recordOccurrence(Instant.now(), "occ 2");
        openIncident.recordOccurrence(Instant.now(), "occ 3");

        List<HealthCheck> baselineChecks = createChecks(10, 100, HealthCheckResult.SUCCESS);
        List<HealthCheck> anomalousChecks = createChecks(10, 250, HealthCheckResult.SUCCESS);

        when(healthCheckRepository.findByServiceIdAndCheckedAtBetween(eq(service.getId()), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(anomalousChecks))
                .thenReturn(new PageImpl<>(baselineChecks));
        when(incidentRepository.findByServiceIdAndIncidentTypeAndStatus(
                service.getId(), IncidentType.HIGH_LATENCY, IncidentStatus.OPEN))
                .thenReturn(Optional.of(openIncident));
        when(incidentRepository.save(any(Incident.class))).thenAnswer(invocation -> invocation.getArgument(0));

        HealthCheck latest = anomalousChecks.get(0);
        // Occurrence 4 will reach escalation threshold (4) -> escalates to CRITICAL!
        anomalyDetectionService.evaluateAnomalies(service, latest);

        assertThat(openIncident.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
        verify(notificationEnqueuer).incidentEscalated(openIncident);
    }

    @Test
    @DisplayName("Latency recovery requires consecutive healthy evaluations to resolve open incident")
    void latencyRecoveryAntiFlapping() {
        Incident openIncident = new Incident(
                service,
                IncidentType.HIGH_LATENCY,
                IncidentSeverity.WARNING,
                "High latency",
                "Details",
                Instant.now().minusSeconds(600)
        );

        List<HealthCheck> baselineChecks = createChecks(10, 50, HealthCheckResult.SUCCESS);
        List<HealthCheck> healthyChecks = createChecks(10, 55, HealthCheckResult.SUCCESS); // Normal latency

        when(healthCheckRepository.findByServiceIdAndCheckedAtBetween(eq(service.getId()), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(healthyChecks))
                .thenReturn(new PageImpl<>(baselineChecks));
        when(incidentRepository.findByServiceIdAndIncidentTypeAndStatus(
                service.getId(), IncidentType.HIGH_LATENCY, IncidentStatus.OPEN))
                .thenReturn(Optional.of(openIncident));

        HealthCheck latest = healthyChecks.get(0);

        // Recovery evaluation 1: recovery streak = 1 (< 2 required)
        anomalyDetectionService.evaluateAnomalies(service, latest);
        assertThat(openIncident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        verify(notificationEnqueuer, never()).incidentResolved(any());

        // Recovery evaluation 2: recovery streak = 2 (>= 2 required) -> resolves incident!
        when(healthCheckRepository.findByServiceIdAndCheckedAtBetween(eq(service.getId()), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(healthyChecks))
                .thenReturn(new PageImpl<>(baselineChecks));
        when(incidentRepository.save(any(Incident.class))).thenAnswer(invocation -> invocation.getArgument(0));

        anomalyDetectionService.evaluateAnomalies(service, latest);

        assertThat(openIncident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        verify(notificationEnqueuer).incidentResolved(openIncident);
    }

    @Test
    @DisplayName("High error rate (40% > 25% threshold) creates HIGH_ERROR_RATE incident after breach streak")
    void highErrorRateIncidentCreation() {
        List<HealthCheck> mixedChecks = new ArrayList<>();
        // 6 successes, 4 failures = 40% error rate
        for (int i = 0; i < 6; i++) {
            mixedChecks.add(new HealthCheck(service, 50, 200, HealthCheckResult.SUCCESS, null));
        }
        for (int i = 0; i < 4; i++) {
            mixedChecks.add(new HealthCheck(service, 0, 500, HealthCheckResult.FAILURE, "Internal Server Error"));
        }

        List<HealthCheck> baselineChecks = createChecks(10, 50, HealthCheckResult.SUCCESS);

        when(healthCheckRepository.findByServiceIdAndCheckedAtBetween(eq(service.getId()), any(), any(), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(mixedChecks));

        when(incidentRepository.findByServiceIdAndIncidentTypeAndStatus(
                eq(service.getId()), any(), eq(IncidentStatus.OPEN)))
                .thenReturn(Optional.empty());

        when(incidentRepository.save(any(Incident.class))).thenAnswer(invocation -> invocation.getArgument(0));

        HealthCheck latest = mixedChecks.get(0);

        // 3 consecutive breach evaluations
        for (int i = 0; i < 3; i++) {
            anomalyDetectionService.evaluateAnomalies(service, latest);
        }

        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        Incident created = captor.getValue();
        assertThat(created.getIncidentType()).isEqualTo(IncidentType.HIGH_ERROR_RATE);
        assertThat(created.getSeverity()).isEqualTo(IncidentSeverity.WARNING);
        verify(notificationEnqueuer).incidentOpened(created);
    }

    @Test
    @DisplayName("Suppresses HIGH_ERROR_RATE incident creation when SERVICE_UNAVAILABLE is already active")
    void suppressesErrorRateWhenServiceUnavailable() {
        List<HealthCheck> failedChecks = createChecks(10, 0, HealthCheckResult.FAILURE);
        List<HealthCheck> baselineChecks = createChecks(10, 50, HealthCheckResult.SUCCESS);

        when(healthCheckRepository.findByServiceIdAndCheckedAtBetween(eq(service.getId()), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(failedChecks))
                .thenReturn(new PageImpl<>(baselineChecks));

        when(incidentRepository.findByServiceIdAndIncidentTypeAndStatus(
                service.getId(), IncidentType.HIGH_ERROR_RATE, IncidentStatus.OPEN))
                .thenReturn(Optional.empty());

        // SERVICE_UNAVAILABLE is active
        Incident unavailableIncident = new Incident(
                service, IncidentType.SERVICE_UNAVAILABLE, IncidentSeverity.CRITICAL, "Service Down", "Details", Instant.now()
        );
        when(incidentRepository.findByServiceIdAndIncidentTypeAndStatus(
                service.getId(), IncidentType.SERVICE_UNAVAILABLE, IncidentStatus.OPEN))
                .thenReturn(Optional.of(unavailableIncident));

        HealthCheck latest = failedChecks.get(0);
        for (int i = 0; i < 4; i++) {
            anomalyDetectionService.evaluateAnomalies(service, latest);
        }

        verify(incidentRepository, never()).save(any());
        verify(notificationEnqueuer, never()).incidentOpened(any());
    }

    private List<HealthCheck> createChecks(int count, long latencyMs, HealthCheckResult result) {
        List<HealthCheck> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new HealthCheck(service, latencyMs, result == HealthCheckResult.SUCCESS ? 200 : 500, result, null));
        }
        return list;
    }
}
