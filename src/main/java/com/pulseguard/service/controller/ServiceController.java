package com.pulseguard.service.controller;

import com.pulseguard.service.dto.CreateServiceRequest;
import com.pulseguard.service.dto.PatchServiceStatusRequest;
import com.pulseguard.service.dto.ServiceResponse;
import com.pulseguard.service.dto.UpdateServiceRequest;
import com.pulseguard.service.model.ServiceStatus;
import com.pulseguard.service.service.ServiceManagementService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/services")
public class ServiceController {

    private final ServiceManagementService serviceManagementService;

    public ServiceController(ServiceManagementService serviceManagementService) {
        this.serviceManagementService = serviceManagementService;
    }

    @PostMapping
    public ResponseEntity<ServiceResponse> createService(@Valid @RequestBody CreateServiceRequest request) {
        ServiceResponse created = serviceManagementService.createService(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping
    public ResponseEntity<Page<ServiceResponse>> listServices(
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) ServiceStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<ServiceResponse> services = serviceManagementService.listServices(enabled, status, pageable);
        return ResponseEntity.ok(services);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ServiceResponse> getServiceById(@PathVariable UUID id) {
        ServiceResponse service = serviceManagementService.getServiceById(id);
        return ResponseEntity.ok(service);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ServiceResponse> updateService(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateServiceRequest request
    ) {
        ServiceResponse updated = serviceManagementService.updateService(id, request);
        return ResponseEntity.ok(updated);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ServiceResponse> updateServiceStatus(
            @PathVariable UUID id,
            @Valid @RequestBody PatchServiceStatusRequest request
    ) {
        ServiceResponse updated = serviceManagementService.updateServiceStatus(id, request);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteService(@PathVariable UUID id) {
        serviceManagementService.deleteService(id);
        return ResponseEntity.noContent().build();
    }
}
