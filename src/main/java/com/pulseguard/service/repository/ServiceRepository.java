package com.pulseguard.service.repository;

import com.pulseguard.service.model.MonitoredService;
import com.pulseguard.service.model.ServiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ServiceRepository extends JpaRepository<MonitoredService, UUID> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    Optional<MonitoredService> findByNameIgnoreCase(String name);

    Page<MonitoredService> findByEnabled(boolean enabled, Pageable pageable);

    Page<MonitoredService> findByStatus(ServiceStatus status, Pageable pageable);

    Page<MonitoredService> findByEnabledAndStatus(boolean enabled, ServiceStatus status, Pageable pageable);

    List<MonitoredService> findByEnabledTrue();

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT s FROM MonitoredService s WHERE s.id = :id")
    Optional<MonitoredService> findWithLockById(@org.springframework.data.repository.query.Param("id") UUID id);
}
