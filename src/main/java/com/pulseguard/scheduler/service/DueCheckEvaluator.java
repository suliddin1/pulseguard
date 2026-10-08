package com.pulseguard.scheduler.service;

import com.pulseguard.service.model.MonitoredService;

import java.time.Instant;

public interface DueCheckEvaluator {

    /**
     * Determines whether a monitored service is due for another health check.
     *
     * @param service the monitored service to evaluate
     * @param lastCheckedAt the timestamp of the service's most recent check execution, or null if never checked
     * @param now the current reference timestamp
     * @return true if the service is enabled and due for execution; false otherwise
     */
    boolean isDue(MonitoredService service, Instant lastCheckedAt, Instant now);

    /**
     * Calculates the next expected due timestamp for a service.
     *
     * @param service the monitored service
     * @param lastCheckedAt the timestamp of the latest check, or null if never checked
     * @param now the current reference timestamp
     * @return the calculated next due time
     */
    Instant calculateNextDueTime(MonitoredService service, Instant lastCheckedAt, Instant now);
}
