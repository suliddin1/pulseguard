package com.pulseguard.incident.service;

import com.pulseguard.healthcheck.model.HealthCheck;
import com.pulseguard.healthcheck.repository.HealthCheckRepository;
import com.pulseguard.incident.config.AnomalyProperties;
import com.pulseguard.incident.metrics.AnomalyMetrics;
import com.pulseguard.incident.metrics.IncidentMetrics;
import com.pulseguard.incident.model.Incident;
import com.pulseguard.incident.model.IncidentSeverity;
import com.pulseguard.incident.model.IncidentStatus;
import com.pulseguard.incident.model.IncidentType;
import com.pulseguard.incident.repository.IncidentRepository;
import com.pulseguard.incident.stats.RollingStatisticsCalculator;
import com.pulseguard.incident.stats.WindowStatistics;
import com.pulseguard.notification.service.NotificationEnqueueException;
import com.pulseguard.notification.service.NotificationEnqueuer;
import com.pulseguard.service.model.MonitoredService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Transactional
public class AnomalyDetectionServiceImpl implements AnomalyDetectionService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetectionServiceImpl.class);

    private final HealthCheckRepository healthCheckRepository;
    private final IncidentRepository incidentRepository;
    private final NotificationEnqueuer notificationEnqueuer;
    private final AnomalyProperties anomalyProperties;
    private final AnomalyMetrics anomalyMetrics;
    private final IncidentMetrics incidentMetrics;

    private final ConcurrentHashMap<UUID, StreakTracker> serviceStreaks = new ConcurrentHashMap<>();

    public AnomalyDetectionServiceImpl(
            HealthCheckRepository healthCheckRepository,
            IncidentRepository incidentRepository,
            NotificationEnqueuer notificationEnqueuer,
            AnomalyProperties anomalyProperties,
            AnomalyMetrics anomalyMetrics,
            IncidentMetrics incidentMetrics
    ) {
        this.healthCheckRepository = healthCheckRepository;
        this.incidentRepository = incidentRepository;
        this.notificationEnqueuer = notificationEnqueuer;
        this.anomalyProperties = anomalyProperties;
        this.anomalyMetrics = anomalyMetrics;
        this.incidentMetrics = incidentMetrics;
    }

    @Override
    public void evaluateAnomalies(MonitoredService service, HealthCheck latestCheck) {
        if (service == null || latestCheck == null) {
            return;
        }

        if (!anomalyProperties.isEnabled()) {
            anomalyMetrics.recordEvaluation("SKIPPED");
            return;
        }

        try {
            Instant now = latestCheck.getCheckedAt();
            UUID serviceId = service.getId();

            // Strictly isolated observation and baseline windows
            Instant obsStart = now.minus(anomalyProperties.getObservationWindow());
            Instant obsEnd = now.plusSeconds(1);

            Instant baseStart = obsStart.minus(anomalyProperties.getBaselineWindow());
            Instant baseEnd = obsStart;

            PageRequest bound = PageRequest.of(0, anomalyProperties.getMaxHistoryChecks(), Sort.by(Sort.Direction.DESC, "checkedAt"));

            Page<HealthCheck> obsPage = healthCheckRepository.findByServiceIdAndCheckedAtBetween(serviceId, obsStart, obsEnd, bound);
            List<HealthCheck> obsChecks = obsPage.getContent();

            WindowStatistics currentStats = RollingStatisticsCalculator.calculate(
                    obsChecks, obsStart, obsEnd, anomalyProperties.getMinSamples()
            );

            if (!currentStats.hasSufficientData()) {
                anomalyMetrics.recordInsufficientData();
                anomalyMetrics.recordEvaluation("INSUFFICIENT_DATA");
                log.debug("Insufficient samples ({}/{}) for anomaly detection on service '{}'",
                        currentStats.totalChecks(), anomalyProperties.getMinSamples(), service.getName());
                return;
            }

            Page<HealthCheck> basePage = healthCheckRepository.findByServiceIdAndCheckedAtBetween(serviceId, baseStart, baseEnd, bound);
            List<HealthCheck> baseChecks = basePage.getContent();

            WindowStatistics baselineStats = RollingStatisticsCalculator.calculate(
                    baseChecks, baseStart, baseEnd, anomalyProperties.getMinSamples()
            );

            StreakTracker tracker = serviceStreaks.computeIfAbsent(serviceId, id -> new StreakTracker());

            boolean latencyAnomalous = evaluateLatencyRule(service, latestCheck, tracker, currentStats, baselineStats);
            boolean errorRateAnomalous = evaluateErrorRateRule(service, latestCheck, tracker, currentStats);

            if (latencyAnomalous || errorRateAnomalous) {
                anomalyMetrics.recordEvaluation("ANOMALOUS");
            } else {
                anomalyMetrics.recordEvaluation("NORMAL");
            }

        } catch (NotificationEnqueueException ex) {
            anomalyMetrics.recordEvaluationFailure();
            log.error("Notification enqueue failed during anomaly evaluation for service '{}': {}",
                    service.getName(), ex.getMessage(), ex);
            throw ex;
        } catch (Exception ex) {
            anomalyMetrics.recordEvaluationFailure();
            log.error("Failed to evaluate anomalies for service '{}': {}", service.getName(), ex.getMessage(), ex);
        }
    }

    private boolean evaluateLatencyRule(
            MonitoredService service,
            HealthCheck latestCheck,
            StreakTracker tracker,
            WindowStatistics currentStats,
            WindowStatistics baselineStats
    ) {
        if (currentStats.successChecks() == 0) {
            // No successful checks to calculate latency percentiles
            return false;
        }

        double p95 = currentStats.p95LatencyMs();
        boolean regressionAnomaly = false;
        boolean criticalRegression = false;

        if (baselineStats.hasSufficientData() && baselineStats.p95LatencyMs() > 0) {
            double baseP95 = baselineStats.p95LatencyMs();
            double factor = p95 / baseP95;
            if (factor >= anomalyProperties.getLatencyRegressionMultiplier()
                    && p95 >= anomalyProperties.getMinAnomalyLatencyMs()) {
                regressionAnomaly = true;
            }
            if (factor >= anomalyProperties.getCriticalLatencyMultiplier()
                    && p95 >= anomalyProperties.getMinAnomalyLatencyMs()) {
                criticalRegression = true;
            }
        }

        boolean absoluteAnomaly = p95 >= anomalyProperties.getLatencyThresholdMs();
        boolean absoluteCritical = p95 >= anomalyProperties.getCriticalLatencyThresholdMs();

        boolean isAnomalous = regressionAnomaly || absoluteAnomaly;
        boolean isCritical = criticalRegression || absoluteCritical;

        UUID serviceId = service.getId();
        Optional<Incident> openIncidentOpt = incidentRepository.findByServiceIdAndIncidentTypeAndStatus(
                serviceId, IncidentType.HIGH_LATENCY, IncidentStatus.OPEN
        );
        boolean hasOpenIncident = openIncidentOpt != null && openIncidentOpt.isPresent();

        if (isAnomalous) {
            tracker.latencyRecoveries.set(0);
            int breaches = tracker.latencyBreaches.incrementAndGet();
            anomalyMetrics.recordAnomalyDetected(IncidentType.HIGH_LATENCY);

            if (hasOpenIncident) {
                Incident incident = openIncidentOpt.get();
                String details = formatLatencyDetails(currentStats, baselineStats);
                incident.recordOccurrence(latestCheck.getCheckedAt(), details);

                boolean shouldEscalate = (incident.getSeverity() == IncidentSeverity.WARNING)
                        && (isCritical
                        || breaches >= anomalyProperties.getEscalationConsecutiveBreachesThreshold()
                        || incident.getOccurrenceCount() >= anomalyProperties.getEscalationConsecutiveBreachesThreshold());

                if (shouldEscalate) {
                    String escalationDetails = String.format(
                            "Escalated to CRITICAL: sustained p95 latency of %.1fms across %d breach evaluations",
                            p95, incident.getOccurrenceCount()
                    );
                    incident.escalate(IncidentSeverity.CRITICAL, latestCheck.getCheckedAt(), escalationDetails);
                    incidentRepository.save(incident);
                    anomalyMetrics.recordEscalation(IncidentType.HIGH_LATENCY);
                    notificationEnqueuer.incidentEscalated(incident);
                    log.warn("Escalated HIGH_LATENCY incident {} to CRITICAL for service '{}'",
                            incident.getId(), service.getName());
                } else {
                    incidentRepository.save(incident);
                    incidentMetrics.recordOccurrence();
                    notificationEnqueuer.incidentOccurrence(incident);
                }
            } else if (breaches >= anomalyProperties.getConsecutiveBreachesThreshold()) {
                IncidentSeverity initialSeverity = isCritical ? IncidentSeverity.CRITICAL : IncidentSeverity.WARNING;
                String summary = String.format("High latency detected on '%s': p95=%.1fms", service.getName(), p95);
                String details = formatLatencyDetails(currentStats, baselineStats);

                Incident incident = new Incident(
                        service,
                        IncidentType.HIGH_LATENCY,
                        initialSeverity,
                        summary,
                        details,
                        latestCheck.getCheckedAt()
                );

                try {
                    Incident saved = incidentRepository.save(incident);
                    incidentMetrics.recordCreated();
                    notificationEnqueuer.incidentOpened(saved);
                    tracker.latencyBreaches.set(0);
                    log.warn("Created HIGH_LATENCY incident for service '{}': severity={}, p95=%.1fms",
                            service.getName(), saved.getSeverity(), p95);
                } catch (DataIntegrityViolationException ex) {
                    log.warn("Concurrent HIGH_LATENCY incident caught by unique constraint for service '{}'", service.getName());
                    incidentRepository.findByServiceIdAndIncidentTypeAndStatus(
                            serviceId, IncidentType.HIGH_LATENCY, IncidentStatus.OPEN
                    ).ifPresent(existing -> {
                        existing.recordOccurrence(latestCheck.getCheckedAt(), details);
                        incidentRepository.save(existing);
                        incidentMetrics.recordOccurrence();
                        notificationEnqueuer.incidentOccurrence(existing);
                    });
                }
            }
        } else {
            tracker.latencyBreaches.set(0);
            if (hasOpenIncident) {
                int recoveries = tracker.latencyRecoveries.incrementAndGet();
                if (recoveries >= anomalyProperties.getConsecutiveSuccessesThreshold()) {
                    Incident incident = openIncidentOpt.get();
                    String resolution = String.format(
                            "Latency normalized after %d consecutive healthy evaluation windows: p95=%.1fms",
                            recoveries, p95
                    );
                    incident.resolve(latestCheck.getCheckedAt(), resolution);
                    incidentRepository.save(incident);
                    incidentMetrics.recordResolved();
                    notificationEnqueuer.incidentResolved(incident);
                    tracker.latencyRecoveries.set(0);
                    log.info("Resolved HIGH_LATENCY incident for service '{}' (ID: {})", service.getName(), incident.getId());
                }
            }
        }

        return isAnomalous;
    }

    private boolean evaluateErrorRateRule(
            MonitoredService service,
            HealthCheck latestCheck,
            StreakTracker tracker,
            WindowStatistics currentStats
    ) {
        double errorRate = currentStats.errorRate();
        boolean isAnomalous = errorRate >= anomalyProperties.getErrorRateThreshold();
        boolean isCritical = errorRate >= anomalyProperties.getCriticalErrorRateThreshold();

        UUID serviceId = service.getId();
        Optional<Incident> openIncidentOpt = incidentRepository.findByServiceIdAndIncidentTypeAndStatus(
                serviceId, IncidentType.HIGH_ERROR_RATE, IncidentStatus.OPEN
        );
        boolean hasOpenIncident = openIncidentOpt != null && openIncidentOpt.isPresent();

        // Suppress opening redundant HIGH_ERROR_RATE incident if SERVICE_UNAVAILABLE is already active
        Optional<Incident> unavailableOpt = incidentRepository.findByServiceIdAndIncidentTypeAndStatus(
                serviceId, IncidentType.SERVICE_UNAVAILABLE, IncidentStatus.OPEN
        );
        boolean isServiceUnavailable = unavailableOpt != null && unavailableOpt.isPresent();

        if (isAnomalous) {
            tracker.errorRateRecoveries.set(0);
            int breaches = tracker.errorRateBreaches.incrementAndGet();
            anomalyMetrics.recordAnomalyDetected(IncidentType.HIGH_ERROR_RATE);

            if (hasOpenIncident) {
                Incident incident = openIncidentOpt.get();
                String details = formatErrorRateDetails(currentStats);
                incident.recordOccurrence(latestCheck.getCheckedAt(), details);

                boolean shouldEscalate = (incident.getSeverity() == IncidentSeverity.WARNING)
                        && (isCritical
                        || breaches >= anomalyProperties.getEscalationConsecutiveBreachesThreshold()
                        || incident.getOccurrenceCount() >= anomalyProperties.getEscalationConsecutiveBreachesThreshold());

                if (shouldEscalate) {
                    String escalationDetails = String.format(
                            "Escalated to CRITICAL: sustained error rate of %.1f%% across %d breach evaluations",
                            errorRate * 100.0, incident.getOccurrenceCount()
                    );
                    incident.escalate(IncidentSeverity.CRITICAL, latestCheck.getCheckedAt(), escalationDetails);
                    incidentRepository.save(incident);
                    anomalyMetrics.recordEscalation(IncidentType.HIGH_ERROR_RATE);
                    notificationEnqueuer.incidentEscalated(incident);
                    log.warn("Escalated HIGH_ERROR_RATE incident {} to CRITICAL for service '{}'",
                            incident.getId(), service.getName());
                } else {
                    incidentRepository.save(incident);
                    incidentMetrics.recordOccurrence();
                    notificationEnqueuer.incidentOccurrence(incident);
                }
            } else if (!isServiceUnavailable && breaches >= anomalyProperties.getConsecutiveBreachesThreshold()) {
                IncidentSeverity initialSeverity = isCritical ? IncidentSeverity.CRITICAL : IncidentSeverity.WARNING;
                String summary = String.format("High error rate detected on '%s': %.1f%% failed checks",
                        service.getName(), errorRate * 100.0);
                String details = formatErrorRateDetails(currentStats);

                Incident incident = new Incident(
                        service,
                        IncidentType.HIGH_ERROR_RATE,
                        initialSeverity,
                        summary,
                        details,
                        latestCheck.getCheckedAt()
                );

                try {
                    Incident saved = incidentRepository.save(incident);
                    incidentMetrics.recordCreated();
                    notificationEnqueuer.incidentOpened(saved);
                    tracker.errorRateBreaches.set(0);
                    log.warn("Created HIGH_ERROR_RATE incident for service '{}': severity={}, errorRate=%.1f%%",
                            service.getName(), saved.getSeverity(), errorRate * 100.0);
                } catch (DataIntegrityViolationException ex) {
                    log.warn("Concurrent HIGH_ERROR_RATE incident caught by unique constraint for service '{}'", service.getName());
                    incidentRepository.findByServiceIdAndIncidentTypeAndStatus(
                            serviceId, IncidentType.HIGH_ERROR_RATE, IncidentStatus.OPEN
                    ).ifPresent(existing -> {
                        existing.recordOccurrence(latestCheck.getCheckedAt(), details);
                        incidentRepository.save(existing);
                        incidentMetrics.recordOccurrence();
                        notificationEnqueuer.incidentOccurrence(existing);
                    });
                }
            }
        } else {
            tracker.errorRateBreaches.set(0);
            if (hasOpenIncident) {
                int recoveries = tracker.errorRateRecoveries.incrementAndGet();
                if (recoveries >= anomalyProperties.getConsecutiveSuccessesThreshold()) {
                    Incident incident = openIncidentOpt.get();
                    String resolution = String.format(
                            "Error rate normalized after %d consecutive healthy evaluation windows: error rate=%.1f%%",
                            recoveries, errorRate * 100.0
                    );
                    incident.resolve(latestCheck.getCheckedAt(), resolution);
                    incidentRepository.save(incident);
                    incidentMetrics.recordResolved();
                    notificationEnqueuer.incidentResolved(incident);
                    tracker.errorRateRecoveries.set(0);
                    log.info("Resolved HIGH_ERROR_RATE incident for service '{}' (ID: {})", service.getName(), incident.getId());
                }
            }
        }

        return isAnomalous;
    }

    private String formatLatencyDetails(WindowStatistics currentStats, WindowStatistics baselineStats) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Current window: p95=%.1fms, p99=%.1fms (samples: %d). ",
                currentStats.p95LatencyMs(), currentStats.p99LatencyMs(), currentStats.totalChecks()));
        if (baselineStats.hasSufficientData()) {
            sb.append(String.format("Baseline window: p95=%.1fms, p99=%.1fms (samples: %d).",
                    baselineStats.p95LatencyMs(), baselineStats.p99LatencyMs(), baselineStats.totalChecks()));
        } else {
            sb.append("Baseline window: insufficient historical data.");
        }
        return sb.toString();
    }

    private String formatErrorRateDetails(WindowStatistics currentStats) {
        return String.format("Current window error rate: %.1f%% (%d failures out of %d checks).",
                currentStats.errorRate() * 100.0, currentStats.failureChecks(), currentStats.totalChecks());
    }

    static class StreakTracker {
        final AtomicInteger latencyBreaches = new AtomicInteger(0);
        final AtomicInteger latencyRecoveries = new AtomicInteger(0);
        final AtomicInteger errorRateBreaches = new AtomicInteger(0);
        final AtomicInteger errorRateRecoveries = new AtomicInteger(0);
    }
}
