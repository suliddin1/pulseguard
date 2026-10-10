package com.pulseguard.incident.repository;

import com.pulseguard.incident.model.Incident;
import com.pulseguard.incident.model.IncidentSeverity;
import com.pulseguard.incident.model.IncidentStatus;
import com.pulseguard.incident.model.IncidentType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class IncidentSpecification {

    private IncidentSpecification() {
        // Utility class
    }

    public static Specification<Incident> filter(
            UUID serviceId,
            IncidentStatus status,
            IncidentSeverity severity,
            IncidentType incidentType,
            Instant from,
            Instant to
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (serviceId != null) {
                predicates.add(cb.equal(root.get("service").get("id"), serviceId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (severity != null) {
                predicates.add(cb.equal(root.get("severity"), severity));
            }
            if (incidentType != null) {
                predicates.add(cb.equal(root.get("incidentType"), incidentType));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("startedAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("startedAt"), to));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
