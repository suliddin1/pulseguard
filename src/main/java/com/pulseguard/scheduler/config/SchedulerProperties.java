package com.pulseguard.scheduler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pulseguard.scheduler")
public class SchedulerProperties {

    /**
     * Whether the automated background health check scheduler is enabled.
     */
    private boolean enabled = true;

    /**
     * Interval in milliseconds between scheduler poll iterations.
     */
    private long pollingIntervalMs = 5000;

    /**
     * Initial delay in milliseconds before the first scheduler poll iteration.
     */
    private long initialDelayMs = 2000;

    /**
     * Maximum number of concurrent health checks that can execute simultaneously.
     */
    private int maxConcurrentChecks = 10;

    /**
     * Bounded queue capacity for pending health checks.
     */
    private int queueCapacity = 100;

    /**
     * Maximum time in seconds to wait for active tasks to terminate during graceful shutdown.
     */
    private int terminationTimeoutSeconds = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getPollingIntervalMs() {
        return pollingIntervalMs;
    }

    public void setPollingIntervalMs(long pollingIntervalMs) {
        this.pollingIntervalMs = pollingIntervalMs;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }

    public int getMaxConcurrentChecks() {
        return maxConcurrentChecks;
    }

    public void setMaxConcurrentChecks(int maxConcurrentChecks) {
        this.maxConcurrentChecks = maxConcurrentChecks;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getTerminationTimeoutSeconds() {
        return terminationTimeoutSeconds;
    }

    public void setTerminationTimeoutSeconds(int terminationTimeoutSeconds) {
        this.terminationTimeoutSeconds = terminationTimeoutSeconds;
    }
}
