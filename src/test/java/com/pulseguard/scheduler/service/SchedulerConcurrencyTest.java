package com.pulseguard.scheduler.service;

import com.pulseguard.healthcheck.repository.HealthCheckRepository;
import com.pulseguard.healthcheck.service.HealthCheckExecutionService;
import com.pulseguard.scheduler.config.SchedulerProperties;
import com.pulseguard.scheduler.metrics.SchedulerMetrics;
import com.pulseguard.service.model.MonitoredService;
import com.pulseguard.service.repository.ServiceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchedulerConcurrencyTest {

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private HealthCheckRepository healthCheckRepository;

    @Mock
    private HealthCheckExecutionService healthCheckExecutionService;

    private final DueCheckEvaluator dueCheckEvaluator = new DefaultDueCheckEvaluator();

    private ThreadPoolTaskExecutor realExecutor;
    private SchedulerProperties properties;
    private SchedulerMetrics metrics;
    private HealthCheckScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties = new SchedulerProperties();
        properties.setMaxConcurrentChecks(5);
        properties.setQueueCapacity(20);
        properties.setTerminationTimeoutSeconds(5);

        realExecutor = new ThreadPoolTaskExecutor();
        realExecutor.setCorePoolSize(3);
        realExecutor.setMaxPoolSize(5);
        realExecutor.setQueueCapacity(20);
        realExecutor.setThreadNamePrefix("test-checker-");
        realExecutor.initialize();

        metrics = new SchedulerMetrics(null);

        scheduler = new HealthCheckSchedulerImpl(
                serviceRepository,
                healthCheckRepository,
                healthCheckExecutionService,
                dueCheckEvaluator,
                properties,
                metrics,
                realExecutor,
                null
        );
    }

    @AfterEach
    void tearDown() {
        if (realExecutor != null) {
            realExecutor.shutdown();
        }
    }

    @Test
    @DisplayName("Multiple distinct services execute in parallel across worker threads")
    void multipleServicesExecuteConcurrently() throws InterruptedException {
        MonitoredService s1 = new MonitoredService("S1", null, "https://s1.example.com", 30, 3000);
        MonitoredService s2 = new MonitoredService("S2", null, "https://s2.example.com", 30, 3000);
        MonitoredService s3 = new MonitoredService("S3", null, "https://s3.example.com", 30, 3000);

        when(serviceRepository.findByEnabledTrue()).thenReturn(List.of(s1, s2, s3));
        when(healthCheckRepository.findLatestCheckTimesGroupedByService()).thenReturn(Collections.emptyList());

        // Coordination latches: ensure all 3 are executing simultaneously
        CountDownLatch allStartedLatch = new CountDownLatch(3);
        CountDownLatch releaseLatch = new CountDownLatch(1);

        doAnswer(invocation -> {
            allStartedLatch.countDown();
            releaseLatch.await(5, TimeUnit.SECONDS);
            return null;
        }).when(healthCheckExecutionService).executeHealthCheck(any(UUID.class));

        scheduler.scheduleDueChecks();

        // Await confirmation that all 3 tasks entered execution concurrently
        boolean allStarted = allStartedLatch.await(5, TimeUnit.SECONDS);
        assertThat(allStarted).isTrue();
        assertThat(scheduler.getInFlightCheckCount()).isEqualTo(3);

        // Release the tasks and verify they complete cleanly
        releaseLatch.countDown();
        boolean drained = awaitInFlightDrained(scheduler, 5);
        assertThat(drained).isTrue();
        assertThat(metrics.getCompletedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("One slow service does not block execution or completion of an unrelated fast service")
    void slowServiceDoesNotBlockFastService() throws InterruptedException {
        MonitoredService slowService = new MonitoredService("Slow", null, "https://slow.com", 30, 5000);
        MonitoredService fastService = new MonitoredService("Fast", null, "https://fast.com", 30, 1000);

        when(serviceRepository.findByEnabledTrue()).thenReturn(List.of(slowService, fastService));
        when(healthCheckRepository.findLatestCheckTimesGroupedByService()).thenReturn(Collections.emptyList());

        CountDownLatch slowBlocker = new CountDownLatch(1);
        CountDownLatch fastCompletedLatch = new CountDownLatch(1);
        AtomicBoolean fastCompleted = new AtomicBoolean(false);

        doAnswer(invocation -> {
            slowBlocker.await(5, TimeUnit.SECONDS);
            return null;
        }).when(healthCheckExecutionService).executeHealthCheck(slowService.getId());

        doAnswer(invocation -> {
            fastCompleted.set(true);
            fastCompletedLatch.countDown();
            return null;
        }).when(healthCheckExecutionService).executeHealthCheck(fastService.getId());

        scheduler.scheduleDueChecks();

        // The fast service must complete even while the slow service is blocked
        boolean fastFinished = fastCompletedLatch.await(3, TimeUnit.SECONDS);
        assertThat(fastFinished).isTrue();
        assertThat(fastCompleted.get()).isTrue();

        // Ensure fast service has cleanly exited in-flight tracking
        boolean fastDrained = awaitServiceNotInFlight(scheduler, fastService.getId(), 3);
        assertThat(fastDrained).isTrue();

        // At this moment, slow service is still in flight
        assertThat(scheduler.isCheckInFlight(slowService.getId())).isTrue();

        // Unblock slow service and verify clean shutdown
        slowBlocker.countDown();
        boolean drained = awaitInFlightDrained(scheduler, 5);
        assertThat(drained).isTrue();
        assertThat(metrics.getCompletedCount()).isEqualTo(2);
    }

    private boolean awaitServiceNotInFlight(HealthCheckScheduler sched, UUID serviceId, int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        while (System.currentTimeMillis() < deadline) {
            if (!sched.isCheckInFlight(serviceId)) {
                return true;
            }
            Thread.sleep(10);
        }
        return !sched.isCheckInFlight(serviceId);
    }

    private boolean awaitInFlightDrained(HealthCheckScheduler sched, int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        while (System.currentTimeMillis() < deadline) {
            if (sched.getInFlightCheckCount() == 0) {
                return true;
            }
            Thread.sleep(20);
        }
        return sched.getInFlightCheckCount() == 0;
    }
}
