package com.pulseguard.scheduler.service;

import com.pulseguard.healthcheck.repository.HealthCheckRepository;
import com.pulseguard.healthcheck.service.HealthCheckExecutionService;
import com.pulseguard.scheduler.config.SchedulerProperties;
import com.pulseguard.scheduler.metrics.SchedulerMetrics;
import com.pulseguard.service.model.MonitoredService;
import com.pulseguard.service.repository.ServiceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

@Service
public class HealthCheckSchedulerImpl implements HealthCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckSchedulerImpl.class);

    private final ServiceRepository serviceRepository;
    private final HealthCheckRepository healthCheckRepository;
    private final HealthCheckExecutionService healthCheckExecutionService;
    private final DueCheckEvaluator dueCheckEvaluator;
    private final SchedulerProperties schedulerProperties;
    private final SchedulerMetrics schedulerMetrics;
    private final TaskExecutor taskExecutor;

    private final Set<UUID> runningChecks = ConcurrentHashMap.newKeySet();

    public HealthCheckSchedulerImpl(
            ServiceRepository serviceRepository,
            HealthCheckRepository healthCheckRepository,
            HealthCheckExecutionService healthCheckExecutionService,
            DueCheckEvaluator dueCheckEvaluator,
            SchedulerProperties schedulerProperties,
            SchedulerMetrics schedulerMetrics,
            @Qualifier("healthCheckTaskExecutor") TaskExecutor taskExecutor,
            @Autowired(required = false) MeterRegistry meterRegistry
    ) {
        this.serviceRepository = serviceRepository;
        this.healthCheckRepository = healthCheckRepository;
        this.healthCheckExecutionService = healthCheckExecutionService;
        this.dueCheckEvaluator = dueCheckEvaluator;
        this.schedulerProperties = schedulerProperties;
        this.schedulerMetrics = schedulerMetrics;
        this.taskExecutor = taskExecutor;

        if (meterRegistry != null) {
            this.schedulerMetrics.registerInFlightGauge(meterRegistry, runningChecks::size);
        }
    }

    @Override
    @Scheduled(
            fixedDelayString = "${pulseguard.scheduler.polling-interval-ms:5000}",
            initialDelayString = "${pulseguard.scheduler.initial-delay-ms:2000}"
    )
    public void scheduleDueChecks() {
        if (!schedulerProperties.isEnabled()) {
            log.trace("Automated health check scheduler is disabled via configuration");
            return;
        }

        Instant now = Instant.now();
        List<MonitoredService> enabledServices = serviceRepository.findByEnabledTrue();
        if (enabledServices.isEmpty()) {
            log.trace("No enabled services found for scheduled health checking");
            return;
        }

        Map<UUID, Instant> latestCheckTimes = fetchLatestCheckTimes();

        for (MonitoredService service : enabledServices) {
            UUID serviceId = service.getId();
            Instant lastCheckedAt = latestCheckTimes.get(serviceId);

            if (!dueCheckEvaluator.isDue(service, lastCheckedAt, now)) {
                continue;
            }

            // Same-service overlap protection: ensure only one check is in flight per service
            if (!runningChecks.add(serviceId)) {
                schedulerMetrics.recordSkippedAlreadyRunning();
                log.debug("Skipping check for service '{}' ({}) - check already in-flight",
                        service.getName(), serviceId);
                continue;
            }

            try {
                schedulerMetrics.recordScheduled();
                taskExecutor.execute(() -> executeSingleScheduledCheck(serviceId, service.getName()));
            } catch (RejectedExecutionException ex) {
                runningChecks.remove(serviceId);
                schedulerMetrics.recordRejectedCapacityExhausted();
                log.warn("Task executor capacity exhausted; rejected check for service '{}' ({})",
                        service.getName(), serviceId);
            } catch (Exception ex) {
                runningChecks.remove(serviceId);
                log.error("Failed to submit check for service '{}' ({})",
                        service.getName(), serviceId, ex);
            }
        }
    }

    private void executeSingleScheduledCheck(UUID serviceId, String serviceName) {
        schedulerMetrics.recordStarted();
        log.debug("Starting scheduled check for service '{}' ({})", serviceName, serviceId);
        try {
            healthCheckExecutionService.executeHealthCheck(serviceId);
            schedulerMetrics.recordCompleted();
            log.debug("Successfully completed scheduled check for service '{}' ({})", serviceName, serviceId);
        } catch (Exception ex) {
            schedulerMetrics.recordFailed();
            log.error("Scheduled check failed for service '{}' ({}): {}",
                    serviceName, serviceId, ex.getMessage(), ex);
        } finally {
            runningChecks.remove(serviceId);
        }
    }

    private Map<UUID, Instant> fetchLatestCheckTimes() {
        Map<UUID, Instant> map = new HashMap<>();
        List<Object[]> rows = healthCheckRepository.findLatestCheckTimesGroupedByService();
        for (Object[] row : rows) {
            if (row != null && row.length >= 2 && row[0] instanceof UUID id && row[1] instanceof Instant timestamp) {
                map.put(id, timestamp);
            }
        }
        return map;
    }

    @Override
    public boolean isCheckInFlight(UUID serviceId) {
        return runningChecks.contains(serviceId);
    }

    @Override
    public int getInFlightCheckCount() {
        return runningChecks.size();
    }
}
