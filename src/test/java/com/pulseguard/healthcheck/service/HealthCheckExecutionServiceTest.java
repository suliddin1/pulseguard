package com.pulseguard.healthcheck.service;

import com.pulseguard.common.exception.ResourceNotFoundException;
import com.pulseguard.common.exception.ServiceDisabledException;
import com.pulseguard.healthcheck.dto.HealthCheckResponse;
import com.pulseguard.healthcheck.model.HealthCheck;
import com.pulseguard.healthcheck.model.HealthCheckResult;
import com.pulseguard.healthcheck.prober.HttpHealthProber;
import com.pulseguard.healthcheck.prober.ProbeResult;
import com.pulseguard.healthcheck.repository.HealthCheckRepository;
import com.pulseguard.service.model.MonitoredService;
import com.pulseguard.service.model.ServiceStatus;
import com.pulseguard.service.repository.ServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthCheckExecutionServiceTest {

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private HealthCheckRepository healthCheckRepository;

    @Mock
    private HttpHealthProber httpHealthProber;

    private HealthCheckExecutionService healthCheckExecutionService;

    @BeforeEach
    void setUp() {
        healthCheckExecutionService = new HealthCheckExecutionServiceImpl(
                serviceRepository,
                healthCheckRepository,
                httpHealthProber
        );
    }

    @Test
    @DisplayName("executeHealthCheck: Probe succeeds, transitions service to HEALTHY, and persists check")
    void executeHealthCheck_Success() {
        MonitoredService service = new MonitoredService(
                "Auth API",
                null,
                "https://auth.example.com/health",
                30,
                3000
        );
        UUID serviceId = service.getId();

        when(serviceRepository.findById(serviceId)).thenReturn(Optional.of(service));
        when(httpHealthProber.probe(service.getUrl(), service.getTimeoutMs()))
                .thenReturn(ProbeResult.success(120L, 200));
        when(healthCheckRepository.save(any(HealthCheck.class))).thenAnswer(invocation -> invocation.getArgument(0));

        HealthCheckResponse response = healthCheckExecutionService.executeHealthCheck(serviceId);

        assertThat(response).isNotNull();
        assertThat(response.serviceId()).isEqualTo(serviceId);
        assertThat(response.result()).isEqualTo(HealthCheckResult.SUCCESS);
        assertThat(response.httpStatusCode()).isEqualTo(200);
        assertThat(response.responseTimeMs()).isEqualTo(120L);

        // Verify service status transition
        assertThat(service.getStatus()).isEqualTo(ServiceStatus.HEALTHY);

        ArgumentCaptor<HealthCheck> captor = ArgumentCaptor.forClass(HealthCheck.class);
        verify(healthCheckRepository).save(captor.capture());
        assertThat(captor.getValue().getResult()).isEqualTo(HealthCheckResult.SUCCESS);
    }

    @Test
    @DisplayName("executeHealthCheck: Probe fails, transitions service to UNHEALTHY, and persists check")
    void executeHealthCheck_Failure() {
        MonitoredService service = new MonitoredService(
                "Payment API",
                null,
                "https://payment.example.com/health",
                60,
                5000
        );
        service.updateStatus(ServiceStatus.HEALTHY);
        UUID serviceId = service.getId();

        when(serviceRepository.findById(serviceId)).thenReturn(Optional.of(service));
        when(httpHealthProber.probe(service.getUrl(), service.getTimeoutMs()))
                .thenReturn(ProbeResult.failure(250L, 503, "HTTP 503"));
        when(healthCheckRepository.save(any(HealthCheck.class))).thenAnswer(invocation -> invocation.getArgument(0));

        HealthCheckResponse response = healthCheckExecutionService.executeHealthCheck(serviceId);

        assertThat(response.result()).isEqualTo(HealthCheckResult.FAILURE);
        assertThat(response.httpStatusCode()).isEqualTo(503);
        assertThat(service.getStatus()).isEqualTo(ServiceStatus.UNHEALTHY);
    }

    @Test
    @DisplayName("executeHealthCheck: Probe times out, transitions service to UNHEALTHY")
    void executeHealthCheck_Timeout() {
        MonitoredService service = new MonitoredService(
                "Slow API",
                null,
                "https://slow.example.com/health",
                60,
                5000
        );
        UUID serviceId = service.getId();

        when(serviceRepository.findById(serviceId)).thenReturn(Optional.of(service));
        when(httpHealthProber.probe(service.getUrl(), service.getTimeoutMs()))
                .thenReturn(ProbeResult.timeout(5000L, "Timed out"));
        when(healthCheckRepository.save(any(HealthCheck.class))).thenAnswer(invocation -> invocation.getArgument(0));

        HealthCheckResponse response = healthCheckExecutionService.executeHealthCheck(serviceId);

        assertThat(response.result()).isEqualTo(HealthCheckResult.TIMEOUT);
        assertThat(response.httpStatusCode()).isNull();
        assertThat(service.getStatus()).isEqualTo(ServiceStatus.UNHEALTHY);
    }

    @Test
    @DisplayName("executeHealthCheck: Throws ResourceNotFoundException when service does not exist")
    void executeHealthCheck_NotFound() {
        UUID nonExistentId = UUID.randomUUID();
        when(serviceRepository.findById(nonExistentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> healthCheckExecutionService.executeHealthCheck(nonExistentId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(nonExistentId.toString());

        verify(httpHealthProber, never()).probe(any(), any(Integer.class));
        verify(healthCheckRepository, never()).save(any());
    }

    @Test
    @DisplayName("executeHealthCheck: Throws ServiceDisabledException when service is disabled")
    void executeHealthCheck_DisabledService() {
        MonitoredService service = new MonitoredService(
                "Disabled Service",
                null,
                "https://disabled.example.com",
                60,
                5000
        );
        service.disable();
        UUID serviceId = service.getId();

        when(serviceRepository.findById(serviceId)).thenReturn(Optional.of(service));

        assertThatThrownBy(() -> healthCheckExecutionService.executeHealthCheck(serviceId))
                .isInstanceOf(ServiceDisabledException.class)
                .hasMessageContaining("disabled");

        verify(httpHealthProber, never()).probe(any(), any(Integer.class));
        verify(healthCheckRepository, never()).save(any());
    }

    @Test
    @DisplayName("getHistoricalChecks: Retrieves paginated checks for valid service")
    void getHistoricalChecks_Success() {
        MonitoredService service = new MonitoredService(
                "Auth API",
                null,
                "https://auth.example.com",
                60,
                5000
        );
        UUID serviceId = service.getId();

        HealthCheck check = new HealthCheck(service, 100L, 200, HealthCheckResult.SUCCESS, null);
        Pageable pageable = PageRequest.of(0, 20);
        Page<HealthCheck> page = new PageImpl<>(List.of(check), pageable, 1);

        when(serviceRepository.existsById(serviceId)).thenReturn(true);
        when(healthCheckRepository.findByServiceId(eq(serviceId), any(Pageable.class))).thenReturn(page);

        Page<HealthCheckResponse> result = healthCheckExecutionService.getHistoricalChecks(serviceId, null, null, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).serviceName()).isEqualTo("Auth API");
        assertThat(result.getContent().get(0).result()).isEqualTo(HealthCheckResult.SUCCESS);
    }

    @Test
    @DisplayName("getHistoricalChecks: Queries time range when from and to are provided")
    void getHistoricalChecks_WithTimeRange() {
        UUID serviceId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant from = now.minus(1, ChronoUnit.HOURS);
        Instant to = now;

        when(serviceRepository.existsById(serviceId)).thenReturn(true);
        when(healthCheckRepository.findByServiceIdAndCheckedAtBetween(eq(serviceId), eq(from), eq(to), any()))
                .thenReturn(Page.empty());

        Page<HealthCheckResponse> result = healthCheckExecutionService.getHistoricalChecks(
                serviceId, from, to, PageRequest.of(0, 10)
        );

        assertThat(result).isNotNull();
        verify(healthCheckRepository).findByServiceIdAndCheckedAtBetween(eq(serviceId), eq(from), eq(to), any());
    }

    @Test
    @DisplayName("getHistoricalChecks: Throws IllegalArgumentException when from is after to")
    void getHistoricalChecks_InvalidDateRange() {
        UUID serviceId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant from = now;
        Instant to = now.minus(1, ChronoUnit.HOURS);

        when(serviceRepository.existsById(serviceId)).thenReturn(true);

        assertThatThrownBy(() -> healthCheckExecutionService.getHistoricalChecks(
                serviceId, from, to, PageRequest.of(0, 10)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be before or equal to");
    }

    @Test
    @DisplayName("getHistoricalChecks: Throws ResourceNotFoundException when service does not exist")
    void getHistoricalChecks_NotFound() {
        UUID nonExistentId = UUID.randomUUID();
        when(serviceRepository.existsById(nonExistentId)).thenReturn(false);

        assertThatThrownBy(() -> healthCheckExecutionService.getHistoricalChecks(
                nonExistentId, null, null, PageRequest.of(0, 10)
        )).isInstanceOf(ResourceNotFoundException.class);
    }
}
