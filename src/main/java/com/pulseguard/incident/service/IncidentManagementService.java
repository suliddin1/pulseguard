package com.pulseguard.incident.service;

import com.pulseguard.incident.dto.IncidentResponse;
import com.pulseguard.incident.model.IncidentSeverity;
import com.pulseguard.incident.model.IncidentStatus;
import com.pulseguard.incident.model.IncidentType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.UUID;

public interface IncidentManagementService {

    /**
     * Retrieves paginated incidents for a specific monitored service.
     */
    Page<IncidentResponse> getIncidentsForService(UUID serviceId, Pageable pageable);

    /**
     * Retrieves paginated global incidents with optional filters.
     */
    Page<IncidentResponse> getIncidents(
            UUID serviceId,
            IncidentStatus status,
            IncidentSeverity severity,
            IncidentType incidentType,
            Instant from,
            Instant to,
            Pageable pageable
    );

    /**
     * Retrieves a single incident by its unique ID.
     */
    IncidentResponse getIncidentById(UUID incidentId);

    /**
     * Retrieves paginated open incidents.
     */
    Page<IncidentResponse> getOpenIncidents(Pageable pageable);
}
