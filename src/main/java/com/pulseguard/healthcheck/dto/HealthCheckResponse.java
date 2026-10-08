package com.pulseguard.healthcheck.dto;

import com.pulseguard.healthcheck.model.HealthCheck;
import com.pulseguard.healthcheck.model.HealthCheckResult;

import java.time.Instant;
import java.util.UUID;

public record HealthCheckResponse(
        UUID id,
        UUID serviceId,
        String serviceName,
        Instant checkedAt,
        long responseTimeMs,
        Integer httpStatusCode,
        HealthCheckResult result,
        String errorMessage
) {

    public static HealthCheckResponse from(HealthCheck check) {
        return new HealthCheckResponse(
                check.getId(),
                check.getService().getId(),
                check.getService().getName(),
                check.getCheckedAt(),
                check.getResponseTimeMs(),
                check.getHttpStatusCode(),
                check.getResult(),
                check.getErrorMessage()
        );
    }
}
