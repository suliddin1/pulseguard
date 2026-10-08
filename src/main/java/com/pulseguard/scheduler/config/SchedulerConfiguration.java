package com.pulseguard.scheduler.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(SchedulerProperties.class)
public class SchedulerConfiguration {

    @Bean(name = "healthCheckTaskExecutor")
    public ThreadPoolTaskExecutor healthCheckTaskExecutor(SchedulerProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int maxConcurrency = Math.max(1, properties.getMaxConcurrentChecks());
        int corePoolSize = Math.max(1, maxConcurrency / 2);

        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxConcurrency);
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("pg-healthcheck-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.getTerminationTimeoutSeconds());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
