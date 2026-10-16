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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
public class HealthCheckExecutionServiceImpl implements HealthCheckExecutionService {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckExecutionServiceImpl.class);
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "id", "checkedAt", "responseTimeMs", "httpStatusCode", "result", "createdAt"
    );

    private final ServiceRepository serviceRepository;
    private final HealthCheckRepository healthCheckRepository;
    private final HttpHealthProber httpHealthProber;
    private final com.pulseguard.incident.service.IncidentDetectionService incidentDetectionService;
    private final TransactionOperations transactionOperations;

    @Autowired
    public HealthCheckExecutionServiceImpl(
            ServiceRepository serviceRepository,
            HealthCheckRepository healthCheckRepository,
            HttpHealthProber httpHealthProber,
            @Autowired(required = false) com.pulseguard.incident.service.IncidentDetectionService incidentDetectionService,
            @Autowired(required = false) PlatformTransactionManager transactionManager
    ) {
        this.serviceRepository = serviceRepository;
        this.healthCheckRepository = healthCheckRepository;
        this.httpHealthProber = httpHealthProber;
        this.incidentDetectionService = incidentDetectionService;
        this.transactionOperations = transactionManager != null
                ? new TransactionTemplate(transactionManager)
                : TransactionOperations.withoutTransaction();
    }

    public HealthCheckExecutionServiceImpl(
            ServiceRepository serviceRepository,
            HealthCheckRepository healthCheckRepository,
            HttpHealthProber httpHealthProber,
            com.pulseguard.incident.service.IncidentDetectionService incidentDetectionService
    ) {
        this(serviceRepository, healthCheckRepository, httpHealthProber, incidentDetectionService, null);
    }

    public HealthCheckExecutionServiceImpl(
            ServiceRepository serviceRepository,
            HealthCheckRepository healthCheckRepository,
            HttpHealthProber httpHealthProber
    ) {
        this(serviceRepository, healthCheckRepository, httpHealthProber, null, null);
    }

    @Override
    public HealthCheckResponse executeHealthCheck(UUID serviceId) {
        log.info("Executing health check for service ID '{}'", serviceId);

        // Step 1: Read service metadata outside a write lock
        MonitoredService initialService = serviceRepository.findById(serviceId)
                .or(() -> serviceRepository.findWithLockById(serviceId))
                .orElseThrow(() -> new ResourceNotFoundException("Service", serviceId));

        if (!initialService.isEnabled()) {
            log.warn("Cannot execute health check: service '{}' (ID: '{}') is disabled", initialService.getName(), serviceId);
            throw new ServiceDisabledException(serviceId, initialService.getName());
        }

        String targetUrl = initialService.getUrl();
        int timeoutMs = initialService.getTimeoutMs();

        // Step 2: Execute external HTTP network probe completely outside any database transaction or row lock
        ProbeResult probeResult = httpHealthProber.probe(targetUrl, timeoutMs);

        // Step 3: Persist check result, update service status, and evaluate incidents in a short transaction
        return transactionOperations.execute(status -> {
            MonitoredService service = serviceRepository.findWithLockById(serviceId)
                    .or(() -> serviceRepository.findById(serviceId))
                    .orElseThrow(() -> new ResourceNotFoundException("Service", serviceId));

            if (!service.isEnabled()) {
                log.warn("Cannot record health check: service '{}' (ID: '{}') was disabled during probe", service.getName(), serviceId);
                throw new ServiceDisabledException(serviceId, service.getName());
            }

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

            // Automatically evaluate incident detection and recovery rules
            if (incidentDetectionService != null) {
                incidentDetectionService.evaluateCheck(service, savedCheck);
            }

            log.info("Health check completed for service '{}' with result: {}, latency: {}ms, new status: {}",
                    service.getName(), probeResult.result(), probeResult.responseTimeMs(), service.getStatus());

            return HealthCheckResponse.from(savedCheck);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Page<HealthCheckResponse> getHistoricalChecks(UUID serviceId, Instant from, Instant to, Pageable pageable) {
        log.debug("Fetching historical health checks for service ID '{}', from: {}, to: {}", serviceId, from, to);

        if (!serviceRepository.existsById(serviceId)) {
            throw new ResourceNotFoundException("Service", serviceId);
        }

        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("'from' timestamp must be before or equal to 'to' timestamp");
        }

        Pageable effectivePageable = sanitizePageable(pageable);

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

    private Pageable sanitizePageable(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "checkedAt"));
        }
        for (Sort.Order order : pageable.getSort()) {
            if (!ALLOWED_SORT_FIELDS.contains(order.getProperty())) {
                throw new IllegalArgumentException("Unsupported sort property: '" + order.getProperty() + "'");
            }
        }
        int boundedPageSize = Math.min(Math.max(1, pageable.getPageSize()), MAX_PAGE_SIZE);
        return PageRequest.of(Math.max(0, pageable.getPageNumber()), boundedPageSize, pageable.getSort());
    }
}
