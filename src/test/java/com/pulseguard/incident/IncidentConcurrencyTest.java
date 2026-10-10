package com.pulseguard.incident;

import com.pulseguard.healthcheck.model.HealthCheckResult;
import com.pulseguard.healthcheck.prober.HttpHealthProber;
import com.pulseguard.healthcheck.prober.ProbeResult;
import com.pulseguard.healthcheck.service.HealthCheckExecutionService;
import com.pulseguard.incident.model.IncidentStatus;
import com.pulseguard.incident.repository.IncidentRepository;
import com.pulseguard.service.dto.CreateServiceRequest;
import com.pulseguard.service.dto.ServiceResponse;
import com.pulseguard.service.service.ServiceManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class IncidentConcurrencyTest {

    @Autowired
    private ServiceManagementService serviceManagementService;

    @Autowired
    private HealthCheckExecutionService healthCheckExecutionService;

    @Autowired
    private IncidentRepository incidentRepository;

    @MockBean
    private HttpHealthProber httpHealthProber;

    @Test
    @DisplayName("Concurrent failing health checks for the same service never produce duplicate open incidents")
    void concurrentChecksDoNotProduceDuplicateOpenIncidents() throws InterruptedException {
        // Register a monitored service
        CreateServiceRequest createRequest = new CreateServiceRequest(
                "High Concurrency API",
                "Load tested endpoint",
                "https://concurrent.example.com/health",
                30,
                3000
        );
        ServiceResponse service = serviceManagementService.createService(createRequest);
        UUID serviceId = service.id();

        // Stub prober to simulate failure
        when(httpHealthProber.probe(anyString(), anyInt()))
                .thenReturn(new ProbeResult(HealthCheckResult.FAILURE, 503, 100, "Service Unavailable"));

        int concurrentThreads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentThreads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishGate = new CountDownLatch(concurrentThreads);
        AtomicInteger successCounter = new AtomicInteger(0);

        for (int i = 0; i < concurrentThreads; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    healthCheckExecutionService.executeHealthCheck(serviceId);
                    successCounter.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    finishGate.countDown();
                }
            });
        }

        // Release all concurrent threads simultaneously
        startGate.countDown();
        boolean completed = finishGate.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCounter.get()).isEqualTo(concurrentThreads);

        // Verify that exactly ONE open incident exists for this service
        var openIncidents = incidentRepository.findByServiceIdAndStatus(serviceId, IncidentStatus.OPEN);
        assertThat(openIncidents).hasSize(1);

        var incident = openIncidents.get(0);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        // Occurrence count must account for the failures
        assertThat(incident.getOccurrenceCount()).isGreaterThanOrEqualTo(3L);
    }
}
