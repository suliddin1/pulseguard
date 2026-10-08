package com.pulseguard.healthcheck.repository;

import com.pulseguard.healthcheck.model.HealthCheck;
import com.pulseguard.healthcheck.model.HealthCheckResult;
import com.pulseguard.service.model.MonitoredService;
import com.pulseguard.service.repository.ServiceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class HealthCheckRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private HealthCheckRepository healthCheckRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    @Test
    @DisplayName("Should persist HealthCheck and retrieve it by ID")
    void shouldPersistAndFindById() {
        MonitoredService service = new MonitoredService(
                "Cache Service",
                null,
                "https://cache.example.com/health",
                60,
                5000
        );
        entityManager.persist(service);

        HealthCheck check = new HealthCheck(
                service,
                88L,
                200,
                HealthCheckResult.SUCCESS,
                null
        );
        HealthCheck saved = entityManager.persistFlushFind(check);

        Optional<HealthCheck> found = healthCheckRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getService().getId()).isEqualTo(service.getId());
        assertThat(found.get().getResponseTimeMs()).isEqualTo(88L);
        assertThat(found.get().getHttpStatusCode()).isEqualTo(200);
        assertThat(found.get().getResult()).isEqualTo(HealthCheckResult.SUCCESS);
    }

    @Test
    @DisplayName("Should retrieve historical checks ordered by checkedAt descending")
    void shouldFindHistoricalChecksNewestFirst() {
        MonitoredService service = new MonitoredService(
                "Orders API",
                null,
                "https://orders.example.com",
                60,
                5000
        );
        entityManager.persist(service);

        HealthCheck check1 = new HealthCheck(service, 100L, 200, HealthCheckResult.SUCCESS, null);
        entityManager.persist(check1);

        HealthCheck check2 = new HealthCheck(service, 250L, 503, HealthCheckResult.FAILURE, "HTTP 503");
        entityManager.persist(check2);

        entityManager.flush();

        Page<HealthCheck> page = healthCheckRepository.findByServiceId(
                service.getId(),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "checkedAt"))
        );

        assertThat(page.getContent()).hasSize(2);
        // The most recently created check should appear first or have a timestamp >= the first
        assertThat(page.getContent().get(0).getCheckedAt())
                .isAfterOrEqualTo(page.getContent().get(1).getCheckedAt());
    }

    @Test
    @DisplayName("Should filter checks by checkedAt time window")
    void shouldFilterByDateRange() {
        MonitoredService service = new MonitoredService(
                "Worker Service",
                null,
                "https://worker.example.com",
                60,
                5000
        );
        entityManager.persist(service);

        HealthCheck check = new HealthCheck(service, 50L, 200, HealthCheckResult.SUCCESS, null);
        entityManager.persistAndFlush(check);

        Instant from = Instant.now().minus(10, ChronoUnit.MINUTES);
        Instant to = Instant.now().plus(10, ChronoUnit.MINUTES);

        Page<HealthCheck> result = healthCheckRepository.findByServiceIdAndCheckedAtBetween(
                service.getId(),
                from,
                to,
                PageRequest.of(0, 10)
        );

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getId()).isEqualTo(check.getId());
    }

    @Test
    @DisplayName("Should cascade delete health checks when monitored service is deleted")
    void shouldCascadeDeleteWithService() {
        MonitoredService service = new MonitoredService(
                "Temporary Service",
                null,
                "https://temp.example.com",
                60,
                5000
        );
        UUID serviceId = service.getId();
        entityManager.persist(service);

        HealthCheck check = new HealthCheck(service, 40L, 200, HealthCheckResult.SUCCESS, null);
        UUID checkId = check.getId();
        entityManager.persistAndFlush(check);

        // Verify check exists
        assertThat(healthCheckRepository.findById(checkId)).isPresent();

        // Delete parent service
        serviceRepository.deleteById(serviceId);
        entityManager.flush();
        entityManager.clear();

        // Verify check is removed via cascade
        assertThat(healthCheckRepository.findById(checkId)).isEmpty();
    }
}
