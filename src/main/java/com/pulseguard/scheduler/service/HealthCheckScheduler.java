package com.pulseguard.scheduler.service;

import java.util.UUID;

public interface HealthCheckScheduler {

    /**
     * Polls the registry for enabled services that are due for a health check and submits
     * them to the bounded thread pool executor.
     */
    void scheduleDueChecks();

    /**
     * Checks if a health check task is currently in flight for the given service.
     *
     * @param serviceId the service unique identifier
     * @return true if currently running, false otherwise
     */
    boolean isCheckInFlight(UUID serviceId);

    /**
     * Returns the count of currently active in-flight health checks.
     *
     * @return number of concurrent checks running
     */
    int getInFlightCheckCount();
}
