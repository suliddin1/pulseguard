package com.pulseguard.incident.service;

import com.pulseguard.healthcheck.model.HealthCheck;
import com.pulseguard.service.model.MonitoredService;

public interface IncidentDetectionService {

    /**
     * Evaluates a completed health check result for incident creation,
     * occurrence updating, or resolution.
     *
     * @param service the monitored service
     * @param healthCheck the health check that was executed and persisted
     */
    void evaluateCheck(MonitoredService service, HealthCheck healthCheck);
}
