package com.pulseguard.incident.service;

import com.pulseguard.common.exception.ResourceNotFoundException;
import com.pulseguard.incident.dto.IncidentResponse;
import com.pulseguard.incident.model.Incident;
import com.pulseguard.incident.model.IncidentSeverity;
import com.pulseguard.incident.model.IncidentStatus;
import com.pulseguard.incident.model.IncidentType;
import com.pulseguard.incident.repository.IncidentRepository;
import com.pulseguard.incident.repository.IncidentSpecification;
import com.pulseguard.service.repository.ServiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class IncidentManagementServiceImpl implements IncidentManagementService {

    private static final Logger log = LoggerFactory.getLogger(IncidentManagementServiceImpl.class);
    private static final int MAX_PAGE_SIZE = 100;
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "startedAt");

    private final IncidentRepository incidentRepository;
    private final ServiceRepository serviceRepository;

    public IncidentManagementServiceImpl(
            IncidentRepository incidentRepository,
            ServiceRepository serviceRepository
    ) {
        this.incidentRepository = incidentRepository;
        this.serviceRepository = serviceRepository;
    }

    @Override
    public Page<IncidentResponse> getIncidentsForService(UUID serviceId, Pageable pageable) {
        log.debug("Fetching incidents for service ID '{}'", serviceId);

        if (!serviceRepository.existsById(serviceId)) {
            throw new ResourceNotFoundException("Service", serviceId);
        }

        Pageable effectivePageable = createBoundedPageable(pageable);
        return incidentRepository.findByServiceId(serviceId, effectivePageable)
                .map(IncidentResponse::from);
    }

    @Override
    public Page<IncidentResponse> getIncidents(
            UUID serviceId,
            IncidentStatus status,
            IncidentSeverity severity,
            IncidentType incidentType,
            Instant from,
            Instant to,
            Pageable pageable
    ) {
        log.debug("Querying global incidents with filters: serviceId={}, status={}, severity={}, type={}, from={}, to={}",
                serviceId, status, severity, incidentType, from, to);

        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("'from' timestamp must be before or equal to 'to' timestamp");
        }

        if (serviceId != null && !serviceRepository.existsById(serviceId)) {
            throw new ResourceNotFoundException("Service", serviceId);
        }

        Pageable effectivePageable = createBoundedPageable(pageable);
        Specification<Incident> spec = IncidentSpecification.filter(
                serviceId, status, severity, incidentType, from, to
        );

        return incidentRepository.findAll(spec, effectivePageable)
                .map(IncidentResponse::from);
    }

    @Override
    public IncidentResponse getIncidentById(UUID incidentId) {
        log.debug("Fetching incident by ID '{}'", incidentId);

        Incident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("Incident", incidentId));

        return IncidentResponse.from(incident);
    }

    @Override
    public Page<IncidentResponse> getOpenIncidents(Pageable pageable) {
        log.debug("Fetching all currently open incidents");

        Pageable effectivePageable = createBoundedPageable(pageable);
        return incidentRepository.findByStatus(IncidentStatus.OPEN, effectivePageable)
                .map(IncidentResponse::from);
    }

    private Pageable createBoundedPageable(Pageable pageable) {
        int boundedPageSize = Math.min(pageable.getPageSize(), MAX_PAGE_SIZE);
        Sort sort = pageable.getSort().isSorted() ? pageable.getSort() : DEFAULT_SORT;
        return PageRequest.of(pageable.getPageNumber(), boundedPageSize, sort);
    }
}
