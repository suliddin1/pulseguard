package com.pulseguard.service.repository;

import com.pulseguard.service.model.MonitoredService;
import com.pulseguard.service.model.ServiceStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class ServiceRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ServiceRepository serviceRepository;

    @Test
    @DisplayName("Should persist and find service by ID")
    void shouldPersistAndFindById() {
        MonitoredService service = new MonitoredService(
                "Payment Service",
                "Processes transactions",
                "https://payment.example.com/health",
                45,
                4000
        );

        MonitoredService saved = entityManager.persistFlushFind(service);

        Optional<MonitoredService> found = serviceRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Payment Service");
        assertThat(found.get().getUrl()).isEqualTo("https://payment.example.com/health");
        assertThat(found.get().getCheckIntervalSeconds()).isEqualTo(45);
        assertThat(found.get().getTimeoutMs()).isEqualTo(4000);
        assertThat(found.get().isEnabled()).isTrue();
        assertThat(found.get().getStatus()).isEqualTo(ServiceStatus.UNKNOWN);
    }

    @Test
    @DisplayName("Should check existence of service by case-insensitive name")
    void shouldCheckExistsByNameIgnoreCase() {
        MonitoredService service = new MonitoredService(
                "Search API",
                null,
                "https://search.example.com/health",
                60,
                5000
        );
        entityManager.persistFlushFind(service);

        assertThat(serviceRepository.existsByNameIgnoreCase("SEARCH API")).isTrue();
        assertThat(serviceRepository.existsByNameIgnoreCase("search api")).isTrue();
        assertThat(serviceRepository.existsByNameIgnoreCase("NonExistent")).isFalse();
    }

    @Test
    @DisplayName("Should check existence by name excluding specific ID")
    void shouldCheckExistsByNameIgnoreCaseAndIdNot() {
        MonitoredService service1 = new MonitoredService(
                "Order Service",
                null,
                "https://order.example.com/health",
                60,
                5000
        );
        MonitoredService saved1 = entityManager.persistFlushFind(service1);

        MonitoredService service2 = new MonitoredService(
                "User Service",
                null,
                "https://user.example.com/health",
                60,
                5000
        );
        MonitoredService saved2 = entityManager.persistFlushFind(service2);

        // Check if "Order Service" exists excluding saved1 -> should be false
        assertThat(serviceRepository.existsByNameIgnoreCaseAndIdNot("Order Service", saved1.getId())).isFalse();

        // Check if "Order Service" exists excluding saved2 -> should be true
        assertThat(serviceRepository.existsByNameIgnoreCaseAndIdNot("Order Service", saved2.getId())).isTrue();
    }

    @Test
    @DisplayName("Should find services filtered by enabled status and health status")
    void shouldFindServicesByFilters() {
        MonitoredService service1 = new MonitoredService(
                "S1",
                null,
                "https://s1.example.com/health",
                60,
                5000
        );
        service1.updateStatus(ServiceStatus.HEALTHY);
        entityManager.persist(service1);

        MonitoredService service2 = new MonitoredService(
                "S2",
                null,
                "https://s2.example.com/health",
                60,
                5000
        );
        service2.disable();
        service2.updateStatus(ServiceStatus.UNHEALTHY);
        entityManager.persist(service2);

        entityManager.flush();

        Page<MonitoredService> enabledServices = serviceRepository.findByEnabled(true, PageRequest.of(0, 10));
        assertThat(enabledServices.getContent()).hasSize(1);
        assertThat(enabledServices.getContent().get(0).getName()).isEqualTo("S1");

        Page<MonitoredService> unhealthyServices = serviceRepository.findByStatus(ServiceStatus.UNHEALTHY, PageRequest.of(0, 10));
        assertThat(unhealthyServices.getContent()).hasSize(1);
        assertThat(unhealthyServices.getContent().get(0).getName()).isEqualTo("S2");

        List<MonitoredService> activeServices = serviceRepository.findByEnabledTrue();
        assertThat(activeServices).hasSize(1);
        assertThat(activeServices.get(0).getName()).isEqualTo("S1");
    }
}
