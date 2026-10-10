package com.pulseguard.incident.repository;

import com.pulseguard.incident.model.Incident;
import com.pulseguard.incident.model.IncidentSeverity;
import com.pulseguard.incident.model.IncidentStatus;
import com.pulseguard.incident.model.IncidentType;
import com.pulseguard.service.model.MonitoredService;
import com.pulseguard.service.repository.ServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class IncidentRepositoryTest {

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private TestEntityManager entityManager;

    private MonitoredService service;

    @BeforeEach
    void setUp() {
        service = new MonitoredService("Checkout Service", "Order flow", "https://checkout.com", 30, 3000);
        entityManager.persistAndFlush(service);
    }

    @Test
    @DisplayName("Persist and find incident by ID")
    void persistAndFindById() {
        Incident incident = new Incident(
                service,
                IncidentType.SERVICE_UNAVAILABLE,
                IncidentSeverity.CRITICAL,
                "Service unavailable",
                "HTTP 500 error",
                Instant.now()
        );

        Incident saved = incidentRepository.save(incident);
        entityManager.flush();
        entityManager.clear();

        Optional<Incident> found = incidentRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getSummary()).isEqualTo("Service unavailable");
        assertThat(found.get().getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(found.get().getOccurrenceCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Find open incident by service ID and type")
    void findByServiceIdAndTypeAndStatus() {
        Incident openIncident = new Incident(
                service,
                IncidentType.SERVICE_UNAVAILABLE,
                IncidentSeverity.CRITICAL,
                "Outage",
                "Connection refused",
                Instant.now()
        );
        incidentRepository.save(openIncident);
        entityManager.flush();
        entityManager.clear();

        Optional<Incident> foundOpen = incidentRepository.findByServiceIdAndIncidentTypeAndStatus(
                service.getId(), IncidentType.SERVICE_UNAVAILABLE, IncidentStatus.OPEN
        );
        assertThat(foundOpen).isPresent();

        Optional<Incident> foundResolved = incidentRepository.findByServiceIdAndIncidentTypeAndStatus(
                service.getId(), IncidentType.SERVICE_UNAVAILABLE, IncidentStatus.RESOLVED
        );
        assertThat(foundResolved).isEmpty();
    }

    @Test
    @DisplayName("Count by status and page open incidents")
    void countAndPageByStatus() {
        Incident inc1 = new Incident(service, IncidentType.SERVICE_UNAVAILABLE, IncidentSeverity.CRITICAL, "Inc 1", null, Instant.now());
        Incident inc2 = new Incident(service, IncidentType.HIGH_LATENCY, IncidentSeverity.WARNING, "Inc 2", null, Instant.now());
        inc2.resolve(Instant.now(), "Resolved");

        incidentRepository.save(inc1);
        incidentRepository.save(inc2);
        entityManager.flush();
        entityManager.clear();

        assertThat(incidentRepository.countByStatus(IncidentStatus.OPEN)).isEqualTo(1L);
        assertThat(incidentRepository.countByStatus(IncidentStatus.RESOLVED)).isEqualTo(1L);

        Page<Incident> openPage = incidentRepository.findByStatus(IncidentStatus.OPEN, PageRequest.of(0, 10));
        assertThat(openPage.getContent()).hasSize(1);
        assertThat(openPage.getContent().get(0).getSummary()).isEqualTo("Inc 1");
    }

    @Test
    @DisplayName("Filter incidents dynamically using IncidentSpecification")
    void filterUsingSpecification() {
        Instant t1 = Instant.now().minusSeconds(100);
        Instant t2 = Instant.now().minusSeconds(50);

        Incident incCritical = new Incident(service, IncidentType.SERVICE_UNAVAILABLE, IncidentSeverity.CRITICAL, "Critical Outage", null, t1);
        Incident incWarning = new Incident(service, IncidentType.HIGH_LATENCY, IncidentSeverity.WARNING, "High Latency", null, t2);

        incidentRepository.save(incCritical);
        incidentRepository.save(incWarning);
        entityManager.flush();
        entityManager.clear();

        Specification<Incident> spec = IncidentSpecification.filter(
                service.getId(),
                IncidentStatus.OPEN,
                IncidentSeverity.CRITICAL,
                IncidentType.SERVICE_UNAVAILABLE,
                null,
                null
        );

        Page<Incident> filtered = incidentRepository.findAll(spec, PageRequest.of(0, 10, Sort.by("startedAt").descending()));
        assertThat(filtered.getContent()).hasSize(1);
        assertThat(filtered.getContent().get(0).getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
    }

    @Test
    @DisplayName("Referential integrity: deleting monitored service cascades deletion of its incidents")
    void cascadeDeleteOnServiceRemoval() {
        Incident incident = new Incident(
                service,
                IncidentType.SERVICE_UNAVAILABLE,
                IncidentSeverity.CRITICAL,
                "Outage",
                "Details",
                Instant.now()
        );
        incidentRepository.save(incident);
        entityManager.flush();

        // Delete parent service
        serviceRepository.delete(service);
        entityManager.flush();
        entityManager.clear();

        assertThat(incidentRepository.findById(incident.getId())).isEmpty();
    }
}
