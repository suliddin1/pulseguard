package com.pulseguard.healthcheck.prober;

import com.pulseguard.healthcheck.model.HealthCheckResult;

public record ProbeResult(
        HealthCheckResult result,
        long responseTimeMs,
        Integer httpStatusCode,
        String errorMessage
) {

    public static ProbeResult success(long responseTimeMs, int statusCode) {
        return new ProbeResult(HealthCheckResult.SUCCESS, responseTimeMs, statusCode, null);
    }

    public static ProbeResult failure(long responseTimeMs, Integer statusCode, String errorMessage) {
        return new ProbeResult(HealthCheckResult.FAILURE, responseTimeMs, statusCode, errorMessage);
    }

    public static ProbeResult timeout(long responseTimeMs, String errorMessage) {
        return new ProbeResult(HealthCheckResult.TIMEOUT, responseTimeMs, null, errorMessage);
    }
}
