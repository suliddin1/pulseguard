package com.pulseguard.incident.service;

import com.pulseguard.healthcheck.model.HealthCheck;
import com.pulseguard.healthcheck.model.HealthCheckResult;
import com.pulseguard.healthcheck.repository.HealthCheckRepository;
import com.pulseguard.incident.config.IncidentProperties;
import com.pulseguard.incident.metrics.IncidentMetrics;
import com.pulseguard.incident.model.Incident;
import com.pulseguard.incident.model.IncidentSeverity;
import com.pulseguard.incident.model.IncidentStatus;
import com.pulseguard.incident.model.IncidentType;
import com.pulseguard.incident.repository.IncidentRepository;
import com.pulseguard.service.model.MonitoredService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncidentDetectionServiceTest {

    @Mock
    private IncidentRepository incidentRepository;

    @Mock
    private HealthCheckRepository healthCheckRepository;

    @Mock
    private com.pulseguard.notification.service.NotificationEnqueuer notificationEnqueuer;

    private IncidentProperties incidentProperties;
    private IncidentMetrics incidentMetrics;
    private IncidentDetectionService detectionService;

    private MonitoredService service;

    @BeforeEach
    void setUp() {
        incidentProperties = new IncidentProperties();
        incidentProperties.setConsecutiveFailuresThreshold(3);
        incidentProperties.setConsecutiveSuccessesThreshold(1);
        incidentProperties.setInitialSeverity(IncidentSeverity.CRITICAL);

        incidentMetrics = new IncidentMetrics(null);

        detectionService = new IncidentDetectionServiceImpl(
                incidentRepository,
                healthCheckRepository,
                incidentProperties,
                incidentMetrics,
                notificationEnqueuer
        );

        service = new MonitoredService("Orders API", "Processing", "https://orders.com", 30, 3000);
    }

    @Test
    @DisplayName("Single failure does not trigger an incident when threshold is 3")
    void singleFailureDoesNotTriggerIncident() {
        HealthCheck failedCheck = new HealthCheck(service, 200, 500, HealthCheckResult.FAILURE, "Internal Server Error");

        when(healthCheckRepository.findTop10ByServiceIdOrderByCheckedAtDesc(service.getId()))
                .thenReturn(List.of(failedCheck));
        when(incidentRepository.findByServiceIdAndIncidentTypeAndStatus(
                service.getId(), IncidentType.SERVICE_UNAVAILABLE, IncidentStatus.OPEN))
                .thenReturn(Optional.empty());

        detectionService.evaluateCheck(service, failedCheck);

        verify(incidentRepository, never()).save(any());
        assertThat(incidentMetrics.getCreatedCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Consecutive failures reaching threshold (3) creates a new CRITICAL incident")
    void consecutiveFailuresReachingThresholdCreatesIncident() {
        HealthCheck c3 = new HealthCheck(service, 200, 500, HealthCheckResult.FAILURE, "HTTP 500 Error");
        HealthCheck c2 = new HealthCheck(service, 3000, null, HealthCheckResult.TIMEOUT, "Timeout after 3000ms");
        HealthCheck c1 = new HealthCheck(service, 150, 503, HealthCheckResult.FAILURE, "Service Unavailable");

        when(healthCheckRepository.findTop10ByServiceIdOrderByCheckedAtDesc(service.getId()))
                .thenReturn(List.of(c3, c2, c1));
        when(incidentRepository.findByServiceIdAndIncidentTypeAndStatus(
                service.getId(), IncidentType.SERVICE_UNAVAILABLE, IncidentStatus.OPEN))
                .thenReturn(Optional.empty());

        detectionService.evaluateCheck(service, c3);

        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());

        Incident created = captor.getValue();
        assertThat(created.getService()).isEqualTo(service);
        assertThat(created.getIncidentType()).isEqualTo(IncidentType.SERVICE_UNAVAILABLE);
        assertThat(created.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(created.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(created.getOccurrenceCount()).isEqualTo(3L);
        assertThat(incidentMetrics.getCreatedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("A successful check resets the failure streak, preventing incident creation")
    void successBetweenFailuresResetsStreak() {
        HealthCheck c4 = new HealthCheck(service, 200, 500, HealthCheckResult.FAILURE, "HTTP 500");
        HealthCheck c3 = new HealthCheck(service, 80, 200, HealthCheckResult.SUCCESS, null);
        HealthCheck c2 = new HealthCheck(service, 200, 500, HealthCheckResult.FAILURE, "HTTP 500");
        HealthCheck c1 = new HealthCheck(service, 200, 500, HealthCheckResult.FAILURE, "HTTP 500");

        // Top checks (newest first): c4(fail), c3(success), c2(fail), c1(fail) -> streak is 1
        when(healthCheckRepository.findTop10ByServiceIdOrderByCheckedAtDesc(service.getId()))
                .thenReturn(List.of(c4, c3, c2, c1));
        when(incidentRepository.findByServiceIdAndIncidentTypeAndStatus(
                service.getId(), IncidentType.SERVICE_UNAVAILABLE, IncidentStatus.OPEN))
                .thenReturn(Optional.empty());

        detectionService.evaluateCheck(service, c4);

        verify(incidentRepository, never()).save(any());
        assertThat(incidentMetrics.getCreatedCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Repeated failure when an incident is already open updates occurrence instead of creating duplicate")
    void repeatedFailureUpdatesExistingOpenIncident() {
        Incident openIncident = new Incident(
                service,
                IncidentType.SERVICE_UNAVAILABLE,
                IncidentSeverity.CRITICAL,
                "Service unavailable",
                "Initial failures",
                service.getCreatedAt()
        );

        HealthCheck ongoingFailure = new HealthCheck(service, 200, 502, HealthCheckResult.FAILURE, "Bad Gateway");

        when(healthCheckRepository.findTop10ByServiceIdOrderByCheckedAtDesc(service.getId()))
                .thenReturn(List.of(ongoingFailure));
        when(incidentRepository.findByServiceIdAndIncidentTypeAndStatus(
                service.getId(), IncidentType.SERVICE_UNAVAILABLE, IncidentStatus.OPEN))
                .thenReturn(Optional.of(openIncident));

        detectionService.evaluateCheck(service, ongoingFailure);

        verify(incidentRepository).save(openIncident);
        assertThat(openIncident.getOccurrenceCount()).isEqualTo(2L);
        assertThat(openIncident.getDetails()).isEqualTo("Bad Gateway");
        assertThat(incidentMetrics.getCreatedCount()).isEqualTo(0);
        assertThat(incidentMetrics.getOccurrencesCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Successful check resolves open incident when recovery threshold (1) is met")
    void successfulCheckResolvesOpenIncident() {
        Incident openIncident = new Incident(
                service,
                IncidentType.SERVICE_UNAVAILABLE,
                IncidentSeverity.CRITICAL,
                "Service unavailable",
                "3 failures",
                service.getCreatedAt()
        );

        HealthCheck recoveryCheck = new HealthCheck(service, 65, 200, HealthCheckResult.SUCCESS, null);

        when(incidentRepository.findByServiceIdAndIncidentTypeAndStatus(
                service.getId(), IncidentType.SERVICE_UNAVAILABLE, IncidentStatus.OPEN))
                .thenReturn(Optional.of(openIncident));
        when(healthCheckRepository.findTop10ByServiceIdOrderByCheckedAtDesc(service.getId()))
                .thenReturn(List.of(recoveryCheck));

        detectionService.evaluateCheck(service, recoveryCheck);

        verify(incidentRepository).save(openIncident);
        assertThat(openIncident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(openIncident.getResolvedAt()).isEqualTo(recoveryCheck.getCheckedAt());
        assertThat(openIncident.getDetails()).contains("Service recovered after 1 successful check(s)");
        assertThat(incidentMetrics.getResolvedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Repeated successful checks do not trigger duplicate resolutions when no open incident exists")
    void repeatedSuccessesDoNotTriggerDuplicateResolution() {
        HealthCheck successCheck = new HealthCheck(service, 50, 200, HealthCheckResult.SUCCESS, null);

        when(incidentRepository.findByServiceIdAndIncidentTypeAndStatus(
                service.getId(), IncidentType.SERVICE_UNAVAILABLE, IncidentStatus.OPEN))
                .thenReturn(Optional.empty());

        detectionService.evaluateCheck(service, successCheck);

        verify(incidentRepository, never()).save(any());
        assertThat(incidentMetrics.getResolvedCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("A new incident can be created after a previous incident was resolved")
    void newIncidentCreatedAfterPriorIncidentResolved() {
        // Given 3 consecutive failures and no currently OPEN incident
        HealthCheck c3 = new HealthCheck(service, 200, 500, HealthCheckResult.FAILURE, "HTTP 500");
        HealthCheck c2 = new HealthCheck(service, 200, 500, HealthCheckResult.FAILURE, "HTTP 500");
        HealthCheck c1 = new HealthCheck(service, 200, 500, HealthCheckResult.FAILURE, "HTTP 500");

        when(healthCheckRepository.findTop10ByServiceIdOrderByCheckedAtDesc(service.getId()))
                .thenReturn(List.of(c3, c2, c1));
        when(incidentRepository.findByServiceIdAndIncidentTypeAndStatus(
                service.getId(), IncidentType.SERVICE_UNAVAILABLE, IncidentStatus.OPEN))
                .thenReturn(Optional.empty());

        detectionService.evaluateCheck(service, c3);

        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incidentMetrics.getCreatedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Unexpected evaluation exception is caught and recorded without throwing")
    void evaluationExceptionIsHandledGracefully() {
        HealthCheck check = new HealthCheck(service, 100, 500, HealthCheckResult.FAILURE, "Error");

        when(healthCheckRepository.findTop10ByServiceIdOrderByCheckedAtDesc(service.getId()))
                .thenThrow(new RuntimeException("Simulated DB failure"));

        // Must not throw exception
        detectionService.evaluateCheck(service, check);

        assertThat(incidentMetrics.getFailuresCount()).isEqualTo(1);
    }
}
