package com.pulseguard.service.dto;

import com.pulseguard.service.model.MonitoredService;
import com.pulseguard.service.model.ServiceStatus;

import java.time.Instant;
import java.util.UUID;

public record ServiceResponse(
        UUID id,
        String name,
        String description,
        String url,
        int checkIntervalSeconds,
        int timeoutMs,
        boolean enabled,
        ServiceStatus status,
        Instant createdAt,
        Instant updatedAt
) {

    public static ServiceResponse from(MonitoredService service) {
        return new ServiceResponse(
                service.getId(),
                service.getName(),
                service.getDescription(),
                service.getUrl(),
                service.getCheckIntervalSeconds(),
                service.getTimeoutMs(),
                service.isEnabled(),
                service.getStatus(),
                service.getCreatedAt(),
                service.getUpdatedAt()
        );
    }
}
