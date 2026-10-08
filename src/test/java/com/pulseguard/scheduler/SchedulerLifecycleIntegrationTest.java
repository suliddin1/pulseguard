package com.pulseguard.scheduler;

import com.pulseguard.scheduler.config.SchedulerProperties;
import com.pulseguard.scheduler.metrics.SchedulerMetrics;
import com.pulseguard.scheduler.service.HealthCheckScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "pulseguard.scheduler.enabled=true",
        "pulseguard.scheduler.max-concurrent-checks=8",
        "pulseguard.scheduler.queue-capacity=40"
})
class SchedulerLifecycleIntegrationTest {

    @Autowired
    private SchedulerProperties properties;

    @Autowired
    private SchedulerMetrics metrics;

    @Autowired
    private HealthCheckScheduler scheduler;

    @Autowired
    @Qualifier("healthCheckTaskExecutor")
    private ThreadPoolTaskExecutor taskExecutor;

    @Test
    @DisplayName("Application context initializes scheduler, properties, and bounded thread pool bean")
    void contextLoadsSchedulerSubsystem() {
        assertThat(properties).isNotNull();
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getMaxConcurrentChecks()).isEqualTo(8);
        assertThat(properties.getQueueCapacity()).isEqualTo(40);

        assertThat(metrics).isNotNull();
        assertThat(scheduler).isNotNull();

        assertThat(taskExecutor).isNotNull();
        assertThat(taskExecutor.getMaxPoolSize()).isEqualTo(8);
        assertThat(taskExecutor.getQueueCapacity()).isEqualTo(40);
        assertThat(taskExecutor.getThreadNamePrefix()).isEqualTo("pg-healthcheck-");
    }
}
