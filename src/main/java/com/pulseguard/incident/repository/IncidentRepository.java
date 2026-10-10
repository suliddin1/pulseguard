package com.pulseguard.incident.repository;

import com.pulseguard.incident.model.Incident;
import com.pulseguard.incident.model.IncidentSeverity;
import com.pulseguard.incident.model.IncidentStatus;
import com.pulseguard.incident.model.IncidentType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IncidentRepository extends JpaRepository<Incident, UUID>, JpaSpecificationExecutor<Incident> {

    Optional<Incident> findByServiceIdAndIncidentTypeAndStatus(
            UUID serviceId,
            IncidentType incidentType,
            IncidentStatus status
    );

    List<Incident> findByServiceIdAndStatus(UUID serviceId, IncidentStatus status);

    Page<Incident> findByServiceId(UUID serviceId, Pageable pageable);

    Page<Incident> findByStatus(IncidentStatus status, Pageable pageable);

    long countByStatus(IncidentStatus status);

    long countByServiceId(UUID serviceId);
}
