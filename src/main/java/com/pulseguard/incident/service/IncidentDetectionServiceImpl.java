package com.pulseguard.incident.service;

import com.pulseguard.healthcheck.model.HealthCheck;
import com.pulseguard.healthcheck.model.HealthCheckResult;
import com.pulseguard.healthcheck.repository.HealthCheckRepository;
import com.pulseguard.incident.config.IncidentProperties;
import com.pulseguard.incident.metrics.IncidentMetrics;
import com.pulseguard.incident.model.Incident;
import com.pulseguard.incident.model.IncidentStatus;
import com.pulseguard.incident.model.IncidentType;
import com.pulseguard.incident.repository.IncidentRepository;
import com.pulseguard.service.model.MonitoredService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class IncidentDetectionServiceImpl implements IncidentDetectionService {

    private static final Logger log = LoggerFactory.getLogger(IncidentDetectionServiceImpl.class);

    private final IncidentRepository incidentRepository;
    private final HealthCheckRepository healthCheckRepository;
    private final IncidentProperties incidentProperties;
    private final IncidentMetrics incidentMetrics;

    public IncidentDetectionServiceImpl(
            IncidentRepository incidentRepository,
            HealthCheckRepository healthCheckRepository,
            IncidentProperties incidentProperties,
            IncidentMetrics incidentMetrics
    ) {
        this.incidentRepository = incidentRepository;
        this.healthCheckRepository = healthCheckRepository;
        this.incidentProperties = incidentProperties;
        this.incidentMetrics = incidentMetrics;
    }

    @Override
    public void evaluateCheck(MonitoredService service, HealthCheck healthCheck) {
        if (service == null || healthCheck == null) {
            return;
        }

        try {
            if (healthCheck.getResult() == HealthCheckResult.SUCCESS) {
                evaluateRecovery(service, healthCheck);
            } else {
                evaluateFailure(service, healthCheck);
            }
        } catch (Exception ex) {
            incidentMetrics.recordProcessingFailure();
            log.error("Failed to process incident evaluation for service '{}' (ID: {}): {}",
                    service.getName(), service.getId(), ex.getMessage(), ex);
        }
    }

    private void evaluateFailure(MonitoredService service, HealthCheck healthCheck) {
        UUID serviceId = service.getId();
        int threshold = incidentProperties.getConsecutiveFailuresThreshold();

        List<HealthCheck> recentChecks = healthCheckRepository.findTop10ByServiceIdOrderByCheckedAtDesc(serviceId);

        int consecutiveFailures = 0;
        Instant firstFailureInStreak = healthCheck.getCheckedAt();
        for (HealthCheck check : recentChecks) {
            if (check.getResult() != HealthCheckResult.SUCCESS) {
                consecutiveFailures++;
                firstFailureInStreak = check.getCheckedAt();
            } else {
                break;
            }
        }

        log.debug("Service '{}' ({}) has {} consecutive failures (threshold: {})",
                service.getName(), serviceId, consecutiveFailures, threshold);

        Optional<Incident> openIncidentOpt = incidentRepository.findByServiceIdAndIncidentTypeAndStatus(
                serviceId, IncidentType.SERVICE_UNAVAILABLE, IncidentStatus.OPEN
        );

        if (openIncidentOpt.isPresent()) {
            Incident openIncident = openIncidentOpt.get();
            openIncident.recordOccurrence(healthCheck.getCheckedAt(), healthCheck.getErrorMessage());
            incidentRepository.save(openIncident);
            incidentMetrics.recordOccurrence();
            log.info("Recorded incident occurrence for service '{}' (Incident ID: {}). Count: {}",
                    service.getName(), openIncident.getId(), openIncident.getOccurrenceCount());
        } else if (consecutiveFailures >= threshold) {
            String summary = String.format("Service '%s' unavailable: %d consecutive health check failures",
                    service.getName(), consecutiveFailures);
            String details = healthCheck.getErrorMessage() != null
                    ? healthCheck.getErrorMessage()
                    : "Consecutive checks failed with status " + healthCheck.getResult();

            Incident newIncident = new Incident(
                    service,
                    IncidentType.SERVICE_UNAVAILABLE,
                    incidentProperties.getInitialSeverity(),
                    summary,
                    details,
                    firstFailureInStreak
            );

            // Reflect the full failure streak in the occurrence count
            for (int i = 1; i < consecutiveFailures; i++) {
                newIncident.recordOccurrence(healthCheck.getCheckedAt(), details);
            }

            try {
                Incident saved = incidentRepository.save(newIncident);
                incidentMetrics.recordCreated();
                log.warn("Created new incident for service '{}' (Incident ID: {}): severity={}, summary='{}'",
                        service.getName(), saved.getId(), saved.getSeverity(), saved.getSummary());
            } catch (DataIntegrityViolationException ex) {
                log.warn("Concurrent incident creation caught by unique constraint for service '{}', falling back to occurrence update",
                        service.getName());
                incidentRepository.findByServiceIdAndIncidentTypeAndStatus(
                        serviceId, IncidentType.SERVICE_UNAVAILABLE, IncidentStatus.OPEN
                ).ifPresent(existing -> {
                    existing.recordOccurrence(healthCheck.getCheckedAt(), healthCheck.getErrorMessage());
                    incidentRepository.save(existing);
                    incidentMetrics.recordOccurrence();
                });
            }
        }
    }

    private void evaluateRecovery(MonitoredService service, HealthCheck healthCheck) {
        UUID serviceId = service.getId();

        Optional<Incident> openIncidentOpt = incidentRepository.findByServiceIdAndIncidentTypeAndStatus(
                serviceId, IncidentType.SERVICE_UNAVAILABLE, IncidentStatus.OPEN
        );

        if (openIncidentOpt.isEmpty()) {
            return;
        }

        int recoveryThreshold = incidentProperties.getConsecutiveSuccessesThreshold();
        List<HealthCheck> recentChecks = healthCheckRepository.findTop10ByServiceIdOrderByCheckedAtDesc(serviceId);

        int consecutiveSuccesses = 0;
        for (HealthCheck check : recentChecks) {
            if (check.getResult() == HealthCheckResult.SUCCESS) {
                consecutiveSuccesses++;
            } else {
                break;
            }
        }

        log.debug("Service '{}' ({}) has {} consecutive successes for recovery (threshold: {})",
                service.getName(), serviceId, consecutiveSuccesses, recoveryThreshold);

        if (consecutiveSuccesses >= recoveryThreshold) {
            Incident openIncident = openIncidentOpt.get();
            String resolutionDetails = String.format(
                    "Service recovered after %d successful check(s). HTTP status: %s, latency: %dms",
                    consecutiveSuccesses,
                    healthCheck.getHttpStatusCode() != null ? healthCheck.getHttpStatusCode() : "N/A",
                    healthCheck.getResponseTimeMs()
            );

            openIncident.resolve(healthCheck.getCheckedAt(), resolutionDetails);
            incidentRepository.save(openIncident);
            incidentMetrics.recordResolved();

            log.info("Resolved incident for service '{}' (Incident ID: {}). Resolved at: {}",
                    service.getName(), openIncident.getId(), openIncident.getResolvedAt());
        }
    }
}
