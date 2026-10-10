package com.pulseguard.healthcheck.repository;

import com.pulseguard.healthcheck.model.HealthCheck;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HealthCheckRepository extends JpaRepository<HealthCheck, UUID> {

    Page<HealthCheck> findByServiceId(UUID serviceId, Pageable pageable);

    Page<HealthCheck> findByServiceIdAndCheckedAtBetween(
            UUID serviceId,
            Instant from,
            Instant to,
            Pageable pageable
    );

    Page<HealthCheck> findByServiceIdAndCheckedAtGreaterThanEqual(
            UUID serviceId,
            Instant from,
            Pageable pageable
    );

    Page<HealthCheck> findByServiceIdAndCheckedAtLessThanEqual(
            UUID serviceId,
            Instant to,
            Pageable pageable
    );

    long countByServiceId(UUID serviceId);

    Optional<HealthCheck> findTopByServiceIdOrderByCheckedAtDesc(UUID serviceId);

    List<HealthCheck> findTop10ByServiceIdOrderByCheckedAtDesc(UUID serviceId);

    @Query("SELECT h.service.id, MAX(h.checkedAt) FROM HealthCheck h GROUP BY h.service.id")
    List<Object[]> findLatestCheckTimesGroupedByService();
}
