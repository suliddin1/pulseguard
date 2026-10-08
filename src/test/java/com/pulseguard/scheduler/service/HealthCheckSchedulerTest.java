package com.pulseguard.scheduler.service;

import com.pulseguard.healthcheck.repository.HealthCheckRepository;
import com.pulseguard.healthcheck.service.HealthCheckExecutionService;
import com.pulseguard.scheduler.config.SchedulerProperties;
import com.pulseguard.scheduler.metrics.SchedulerMetrics;
import com.pulseguard.service.model.MonitoredService;
import com.pulseguard.service.repository.ServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthCheckSchedulerTest {

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private HealthCheckRepository healthCheckRepository;

    @Mock
    private HealthCheckExecutionService healthCheckExecutionService;

    @Mock
    private TaskExecutor mockTaskExecutor;

    private final DueCheckEvaluator dueCheckEvaluator = new DefaultDueCheckEvaluator();
    private SchedulerProperties schedulerProperties;
    private SchedulerMetrics schedulerMetrics;

    @BeforeEach
    void setUp() {
        schedulerProperties = new SchedulerProperties();
        schedulerProperties.setEnabled(true);
        schedulerProperties.setMaxConcurrentChecks(5);
        schedulerProperties.setQueueCapacity(20);
        schedulerMetrics = new SchedulerMetrics(null);
    }

    @Test
    @DisplayName("scheduleDueChecks: Submits due service to executor and executes check")
    void scheduleDueChecks_Success() {
        MonitoredService service = new MonitoredService(
                "Payment Service", null, "https://payment.example.com", 60, 5000
        );
        UUID serviceId = service.getId();

        when(serviceRepository.findByEnabledTrue()).thenReturn(List.of(service));
        when(healthCheckRepository.findLatestCheckTimesGroupedByService()).thenReturn(Collections.emptyList());

        // Use synchronous executor for deterministic execution
        HealthCheckScheduler scheduler = new HealthCheckSchedulerImpl(
                serviceRepository,
                healthCheckRepository,
                healthCheckExecutionService,
                dueCheckEvaluator,
                schedulerProperties,
                schedulerMetrics,
                new SyncTaskExecutor(),
                null
        );

        scheduler.scheduleDueChecks();

        verify(healthCheckExecutionService).executeHealthCheck(serviceId);
        assertThat(schedulerMetrics.getScheduledCount()).isEqualTo(1);
        assertThat(schedulerMetrics.getStartedCount()).isEqualTo(1);
        assertThat(schedulerMetrics.getCompletedCount()).isEqualTo(1);
        assertThat(schedulerMetrics.getFailedCount()).isEqualTo(0);
        assertThat(scheduler.isCheckInFlight(serviceId)).isFalse();
    }

    @Test
    @DisplayName("scheduleDueChecks: Does not submit when service is not due")
    void scheduleDueChecks_NotDue() {
        MonitoredService service = new MonitoredService(
                "Auth Service", null, "https://auth.example.com", 60, 5000
        );

        when(serviceRepository.findByEnabledTrue()).thenReturn(List.of(service));
        // Service was checked just now -> not due yet
        when(healthCheckRepository.findLatestCheckTimesGroupedByService())
                .thenReturn(Collections.singletonList(new Object[]{service.getId(), Instant.now()}));

        HealthCheckScheduler scheduler = new HealthCheckSchedulerImpl(
                serviceRepository,
                healthCheckRepository,
                healthCheckExecutionService,
                dueCheckEvaluator,
                schedulerProperties,
                schedulerMetrics,
                new SyncTaskExecutor(),
                null
        );

        scheduler.scheduleDueChecks();

        verify(healthCheckExecutionService, never()).executeHealthCheck(any());
        assertThat(schedulerMetrics.getScheduledCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("scheduleDueChecks: Does nothing when scheduler is disabled via configuration")
    void scheduleDueChecks_WhenDisabledInProperties() {
        schedulerProperties.setEnabled(false);

        HealthCheckScheduler scheduler = new HealthCheckSchedulerImpl(
                serviceRepository,
                healthCheckRepository,
                healthCheckExecutionService,
                dueCheckEvaluator,
                schedulerProperties,
                schedulerMetrics,
                new SyncTaskExecutor(),
                null
        );

        scheduler.scheduleDueChecks();

        verify(serviceRepository, never()).findByEnabledTrue();
        assertThat(schedulerMetrics.getScheduledCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Same-service overlap protection: Skips second check if first is already in flight")
    void scheduleDueChecks_SameServiceOverlapProtection() {
        MonitoredService service = new MonitoredService(
                "Slow API", null, "https://slow.example.com", 30, 5000
        );
        UUID serviceId = service.getId();

        when(serviceRepository.findByEnabledTrue()).thenReturn(List.of(service));
        when(healthCheckRepository.findLatestCheckTimesGroupedByService()).thenReturn(Collections.emptyList());

        // Configure mock task executor to hold the task without completing it immediately
        HealthCheckScheduler scheduler = new HealthCheckSchedulerImpl(
                serviceRepository,
                healthCheckRepository,
                healthCheckExecutionService,
                dueCheckEvaluator,
                schedulerProperties,
                schedulerMetrics,
                mockTaskExecutor,
                null
        );

        // First schedule attempt submits to executor
        scheduler.scheduleDueChecks();
        assertThat(scheduler.isCheckInFlight(serviceId)).isTrue();
        assertThat(schedulerMetrics.getScheduledCount()).isEqualTo(1);
        assertThat(schedulerMetrics.getSkippedCount()).isEqualTo(0);

        // Second schedule attempt detects check is in-flight -> skips!
        scheduler.scheduleDueChecks();
        assertThat(schedulerMetrics.getScheduledCount()).isEqualTo(1); // not incremented
        assertThat(schedulerMetrics.getSkippedCount()).isEqualTo(1);   // recorded as skipped

        verify(mockTaskExecutor, times(1)).execute(any());
    }

    @Test
    @DisplayName("Executor rejection: Gracefully catches rejection, records metric, and releases in-flight slot")
    void scheduleDueChecks_ExecutorRejectionHandled() {
        MonitoredService service = new MonitoredService(
                "Overflow API", null, "https://overflow.example.com", 30, 5000
        );
        UUID serviceId = service.getId();

        when(serviceRepository.findByEnabledTrue()).thenReturn(List.of(service));
        when(healthCheckRepository.findLatestCheckTimesGroupedByService()).thenReturn(Collections.emptyList());
        doThrow(new RejectedExecutionException("Queue full")).when(mockTaskExecutor).execute(any());

        HealthCheckScheduler scheduler = new HealthCheckSchedulerImpl(
                serviceRepository,
                healthCheckRepository,
                healthCheckExecutionService,
                dueCheckEvaluator,
                schedulerProperties,
                schedulerMetrics,
                mockTaskExecutor,
                null
        );

        scheduler.scheduleDueChecks();

        // In-flight slot is cleanly released
        assertThat(scheduler.isCheckInFlight(serviceId)).isFalse();
        assertThat(schedulerMetrics.getRejectedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Failure isolation: An unexpected exception during check is recorded and releases in-flight slot")
    void scheduleDueChecks_TaskFailureReleasesSlot() {
        MonitoredService service = new MonitoredService(
                "Crashing API", null, "https://crash.example.com", 30, 5000
        );
        UUID serviceId = service.getId();

        when(serviceRepository.findByEnabledTrue()).thenReturn(List.of(service));
        when(healthCheckRepository.findLatestCheckTimesGroupedByService()).thenReturn(Collections.emptyList());
        doThrow(new RuntimeException("Simulated runtime error"))
                .when(healthCheckExecutionService).executeHealthCheck(serviceId);

        HealthCheckScheduler scheduler = new HealthCheckSchedulerImpl(
                serviceRepository,
                healthCheckRepository,
                healthCheckExecutionService,
                dueCheckEvaluator,
                schedulerProperties,
                schedulerMetrics,
                new SyncTaskExecutor(),
                null
        );

        // Scheduler does not throw or terminate
        scheduler.scheduleDueChecks();

        assertThat(scheduler.isCheckInFlight(serviceId)).isFalse();
        assertThat(schedulerMetrics.getStartedCount()).isEqualTo(1);
        assertThat(schedulerMetrics.getFailedCount()).isEqualTo(1);
        assertThat(schedulerMetrics.getCompletedCount()).isEqualTo(0);
    }
}
