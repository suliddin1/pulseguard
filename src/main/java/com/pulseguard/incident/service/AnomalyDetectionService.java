package com.pulseguard.incident.service;

import com.pulseguard.healthcheck.model.HealthCheck;
import com.pulseguard.service.model.MonitoredService;

public interface AnomalyDetectionService {

    /**
     * Evaluates statistical metrics for the given service upon a new health check observation.
     * Must be invoked within the health check execution transaction.
     *
     * @param service monitored service
     * @param latestCheck the newly persisted health check
     */
    void evaluateAnomalies(MonitoredService service, HealthCheck latestCheck);
}
