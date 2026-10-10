package com.pulseguard.incident.dto;

import com.pulseguard.incident.model.Incident;
import com.pulseguard.incident.model.IncidentSeverity;
import com.pulseguard.incident.model.IncidentStatus;
import com.pulseguard.incident.model.IncidentType;

import java.time.Instant;
import java.util.UUID;

public record IncidentResponse(
        UUID id,
        UUID serviceId,
        String serviceName,
        IncidentType incidentType,
        IncidentSeverity severity,
        IncidentStatus status,
        String summary,
        String details,
        Instant startedAt,
        Instant lastOccurrenceAt,
        Instant resolvedAt,
        long occurrenceCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static IncidentResponse from(Incident incident) {
        if (incident == null) {
            return null;
        }
        return new IncidentResponse(
                incident.getId(),
                incident.getService() != null ? incident.getService().getId() : null,
                incident.getService() != null ? incident.getService().getName() : null,
                incident.getIncidentType(),
                incident.getSeverity(),
                incident.getStatus(),
                incident.getSummary(),
                incident.getDetails(),
                incident.getStartedAt(),
                incident.getLastOccurrenceAt(),
                incident.getResolvedAt(),
                incident.getOccurrenceCount(),
                incident.getCreatedAt(),
                incident.getUpdatedAt()
        );
    }
}
