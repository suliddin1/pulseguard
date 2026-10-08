package com.pulseguard.service.service;

import com.pulseguard.common.exception.DuplicateResourceException;
import com.pulseguard.common.exception.ResourceNotFoundException;
import com.pulseguard.service.dto.CreateServiceRequest;
import com.pulseguard.service.dto.PatchServiceStatusRequest;
import com.pulseguard.service.dto.ServiceResponse;
import com.pulseguard.service.dto.UpdateServiceRequest;
import com.pulseguard.service.model.MonitoredService;
import com.pulseguard.service.model.ServiceStatus;
import com.pulseguard.service.repository.ServiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ServiceManagementServiceImpl implements ServiceManagementService {

    private static final Logger log = LoggerFactory.getLogger(ServiceManagementServiceImpl.class);

    private final ServiceRepository serviceRepository;

    public ServiceManagementServiceImpl(ServiceRepository serviceRepository) {
        this.serviceRepository = serviceRepository;
    }

    @Override
    @Transactional
    public ServiceResponse createService(CreateServiceRequest request) {
        log.info("Registering new monitored service with name: '{}', URL: '{}'", request.name(), request.url());

        String trimmedName = request.name().trim();
        if (serviceRepository.existsByNameIgnoreCase(trimmedName)) {
            throw new DuplicateResourceException("Service", "name", trimmedName);
        }

        MonitoredService service = new MonitoredService(
                trimmedName,
                request.description(),
                request.url(),
                request.getResolvedCheckIntervalSeconds(),
                request.getResolvedTimeoutMs()
        );

        MonitoredService savedService = serviceRepository.save(service);
        log.info("Successfully registered service with ID: '{}'", savedService.getId());
        return ServiceResponse.from(savedService);
    }

    @Override
    public ServiceResponse getServiceById(UUID id) {
        log.debug("Fetching monitored service by ID: '{}'", id);
        return serviceRepository.findById(id)
                .map(ServiceResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Service", id));
    }

    @Override
    public Page<ServiceResponse> listServices(Boolean enabled, ServiceStatus status, Pageable pageable) {
        log.debug("Listing monitored services with filters: enabled={}, status={}", enabled, status);
        Page<MonitoredService> services;

        if (enabled != null && status != null) {
            services = serviceRepository.findByEnabledAndStatus(enabled, status, pageable);
        } else if (enabled != null) {
            services = serviceRepository.findByEnabled(enabled, pageable);
        } else if (status != null) {
            services = serviceRepository.findByStatus(status, pageable);
        } else {
            services = serviceRepository.findAll(pageable);
        }

        return services.map(ServiceResponse::from);
    }

    @Override
    @Transactional
    public ServiceResponse updateService(UUID id, UpdateServiceRequest request) {
        log.info("Updating configuration for service ID: '{}'", id);
        MonitoredService service = serviceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Service", id));

        String trimmedName = request.name().trim();
        if (serviceRepository.existsByNameIgnoreCaseAndIdNot(trimmedName, id)) {
            throw new DuplicateResourceException("Service", "name", trimmedName);
        }

        service.updateConfiguration(
                trimmedName,
                request.description(),
                request.url(),
                request.checkIntervalSeconds(),
                request.timeoutMs()
        );

        log.info("Successfully updated service configuration for ID: '{}'", id);
        return ServiceResponse.from(service);
    }

    @Override
    @Transactional
    public ServiceResponse updateServiceStatus(UUID id, PatchServiceStatusRequest request) {
        log.info("Updating enabled status for service ID: '{}' to: {}", id, request.enabled());
        MonitoredService service = serviceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Service", id));

        if (Boolean.TRUE.equals(request.enabled())) {
            service.enable();
        } else {
            service.disable();
        }

        log.info("Successfully toggled enabled status for service ID: '{}'", id);
        return ServiceResponse.from(service);
    }

    @Override
    @Transactional
    public void deleteService(UUID id) {
        log.info("Deleting monitored service ID: '{}'", id);
        if (!serviceRepository.existsById(id)) {
            throw new ResourceNotFoundException("Service", id);
        }
        serviceRepository.deleteById(id);
        log.info("Successfully deleted service ID: '{}'", id);
    }
}
