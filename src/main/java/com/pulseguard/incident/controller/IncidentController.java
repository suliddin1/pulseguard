package com.pulseguard.incident.controller;

import com.pulseguard.incident.dto.IncidentResponse;
import com.pulseguard.incident.model.IncidentSeverity;
import com.pulseguard.incident.model.IncidentStatus;
import com.pulseguard.incident.model.IncidentType;
import com.pulseguard.incident.service.IncidentManagementService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class IncidentController {

    private final IncidentManagementService incidentManagementService;

    public IncidentController(IncidentManagementService incidentManagementService) {
        this.incidentManagementService = incidentManagementService;
    }

    @GetMapping("/services/{serviceId}/incidents")
    public ResponseEntity<Page<IncidentResponse>> getIncidentsForService(
            @PathVariable UUID serviceId,
            @PageableDefault(size = 20, sort = "startedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<IncidentResponse> incidents = incidentManagementService.getIncidentsForService(serviceId, pageable);
        return ResponseEntity.ok(incidents);
    }

    @GetMapping("/incidents")
    public ResponseEntity<Page<IncidentResponse>> getIncidents(
            @RequestParam(required = false) UUID serviceId,
            @RequestParam(required = false) IncidentStatus status,
            @RequestParam(required = false) IncidentSeverity severity,
            @RequestParam(required = false) IncidentType type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20, sort = "startedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<IncidentResponse> incidents = incidentManagementService.getIncidents(
                serviceId, status, severity, type, from, to, pageable
        );
        return ResponseEntity.ok(incidents);
    }

    @GetMapping("/incidents/open")
    public ResponseEntity<Page<IncidentResponse>> getOpenIncidents(
            @PageableDefault(size = 20, sort = "startedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<IncidentResponse> incidents = incidentManagementService.getOpenIncidents(pageable);
        return ResponseEntity.ok(incidents);
    }

    @GetMapping("/incidents/{id}")
    public ResponseEntity<IncidentResponse> getIncidentById(@PathVariable UUID id) {
        IncidentResponse incident = incidentManagementService.getIncidentById(id);
        return ResponseEntity.ok(incident);
    }
}
