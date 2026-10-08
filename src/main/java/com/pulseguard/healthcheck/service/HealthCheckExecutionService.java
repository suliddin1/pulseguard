package com.pulseguard.healthcheck.service;

import com.pulseguard.healthcheck.dto.HealthCheckResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.UUID;

public interface HealthCheckExecutionService {

    /**
     * Executes an on-demand health check probe against the specified service,
     * persists the execution result, and updates the service health status.
     *
     * @param serviceId the unique identifier of the monitored service
     * @return a {@link HealthCheckResponse} containing execution details
     */
    HealthCheckResponse executeHealthCheck(UUID serviceId);

    /**
     * Retrieves a paginated history of health check records for the specified service,
     * with optional time-range filtering.
     *
     * @param serviceId the unique identifier of the monitored service
     * @param from optional start timestamp (inclusive)
     * @param to optional end timestamp (inclusive)
     * @param pageable pagination and sorting parameters
     * @return a paginated list of {@link HealthCheckResponse} records
     */
    Page<HealthCheckResponse> getHistoricalChecks(UUID serviceId, Instant from, Instant to, Pageable pageable);
}
