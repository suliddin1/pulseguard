package com.pulseguard.healthcheck.service;

import com.pulseguard.common.exception.ResourceNotFoundException;
import com.pulseguard.common.exception.ServiceDisabledException;
import com.pulseguard.healthcheck.dto.HealthCheckResponse;
import com.pulseguard.healthcheck.model.HealthCheck;
import com.pulseguard.healthcheck.prober.HttpHealthProber;
import com.pulseguard.healthcheck.prober.ProbeResult;
import com.pulseguard.healthcheck.repository.HealthCheckRepository;
import com.pulseguard.service.model.MonitoredService;
import com.pulseguard.service.repository.ServiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class HealthCheckExecutionServiceImpl implements HealthCheckExecutionService {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckExecutionServiceImpl.class);
    private static final int MAX_PAGE_SIZE = 100;

    private final ServiceRepository serviceRepository;
    private final HealthCheckRepository healthCheckRepository;
    private final HttpHealthProber httpHealthProber;

    public HealthCheckExecutionServiceImpl(
            ServiceRepository serviceRepository,
            HealthCheckRepository healthCheckRepository,
            HttpHealthProber httpHealthProber
    ) {
        this.serviceRepository = serviceRepository;
        this.healthCheckRepository = healthCheckRepository;
        this.httpHealthProber = httpHealthProber;
    }

    @Override
    @Transactional
    public HealthCheckResponse executeHealthCheck(UUID serviceId) {
        log.info("Executing health check for service ID '{}'", serviceId);

        MonitoredService service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Service", serviceId));

        if (!service.isEnabled()) {
            log.warn("Cannot execute health check: service '{}' (ID: '{}') is disabled", service.getName(), serviceId);
            throw new ServiceDisabledException(serviceId, service.getName());
        }

        ProbeResult probeResult = httpHealthProber.probe(service.getUrl(), service.getTimeoutMs());

        HealthCheck healthCheck = new HealthCheck(
                service,
                probeResult.responseTimeMs(),
                probeResult.httpStatusCode(),
                probeResult.result(),
                probeResult.errorMessage()
        );

        // Update service status based on check outcome
        service.recordHealthCheckOutcome(probeResult.result());

        HealthCheck savedCheck = healthCheckRepository.save(healthCheck);
        log.info("Health check completed for service '{}' with result: {}, latency: {}ms, new status: {}",
                service.getName(), probeResult.result(), probeResult.responseTimeMs(), service.getStatus());

        return HealthCheckResponse.from(savedCheck);
    }

    @Override
    public Page<HealthCheckResponse> getHistoricalChecks(UUID serviceId, Instant from, Instant to, Pageable pageable) {
        log.debug("Fetching historical health checks for service ID '{}', from: {}, to: {}", serviceId, from, to);

        if (!serviceRepository.existsById(serviceId)) {
            throw new ResourceNotFoundException("Service", serviceId);
        }

        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("'from' timestamp must be before or equal to 'to' timestamp");
        }

        int boundedPageSize = Math.min(pageable.getPageSize(), MAX_PAGE_SIZE);
        Pageable effectivePageable = PageRequest.of(pageable.getPageNumber(), boundedPageSize, pageable.getSort());

        Page<HealthCheck> checks;
        if (from != null && to != null) {
            checks = healthCheckRepository.findByServiceIdAndCheckedAtBetween(serviceId, from, to, effectivePageable);
        } else if (from != null) {
            checks = healthCheckRepository.findByServiceIdAndCheckedAtGreaterThanEqual(serviceId, from, effectivePageable);
        } else if (to != null) {
            checks = healthCheckRepository.findByServiceIdAndCheckedAtLessThanEqual(serviceId, to, effectivePageable);
        } else {
            checks = healthCheckRepository.findByServiceId(serviceId, effectivePageable);
        }

        return checks.map(HealthCheckResponse::from);
    }
}
