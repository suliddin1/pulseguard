package com.pulseguard.service.service;

import com.pulseguard.service.dto.CreateServiceRequest;
import com.pulseguard.service.dto.PatchServiceStatusRequest;
import com.pulseguard.service.dto.ServiceResponse;
import com.pulseguard.service.dto.UpdateServiceRequest;
import com.pulseguard.service.model.ServiceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ServiceManagementService {

    ServiceResponse createService(CreateServiceRequest request);

    ServiceResponse getServiceById(UUID id);

    Page<ServiceResponse> listServices(Boolean enabled, ServiceStatus status, Pageable pageable);

    ServiceResponse updateService(UUID id, UpdateServiceRequest request);

    ServiceResponse updateServiceStatus(UUID id, PatchServiceStatusRequest request);

    void deleteService(UUID id);
}
