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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceManagementServiceTest {

    @Mock
    private ServiceRepository serviceRepository;

    private ServiceManagementService serviceManagementService;

    @BeforeEach
    void setUp() {
        serviceManagementService = new ServiceManagementServiceImpl(serviceRepository);
    }

    @Test
    @DisplayName("createService: Successfully saves and maps service")
    void createService_Success() {
        CreateServiceRequest request = new CreateServiceRequest(
                "Notifications Service",
                "Email and SMS dispatcher",
                "https://notify.example.com/health",
                30,
                4000
        );

        when(serviceRepository.existsByNameIgnoreCase("Notifications Service")).thenReturn(false);
        when(serviceRepository.save(any(MonitoredService.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ServiceResponse response = serviceManagementService.createService(request);

        assertThat(response).isNotNull();
        assertThat(response.id()).isNotNull();
        assertThat(response.name()).isEqualTo("Notifications Service");
        assertThat(response.description()).isEqualTo("Email and SMS dispatcher");
        assertThat(response.url()).isEqualTo("https://notify.example.com/health");
        assertThat(response.checkIntervalSeconds()).isEqualTo(30);
        assertThat(response.timeoutMs()).isEqualTo(4000);
        assertThat(response.enabled()).isTrue();
        assertThat(response.status()).isEqualTo(ServiceStatus.UNKNOWN);

        ArgumentCaptor<MonitoredService> captor = ArgumentCaptor.forClass(MonitoredService.class);
        verify(serviceRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Notifications Service");
    }

    @Test
    @DisplayName("createService: Throws DuplicateResourceException when name already exists")
    void createService_DuplicateName_ThrowsException() {
        CreateServiceRequest request = new CreateServiceRequest(
                "Existing Service",
                null,
                "https://existing.example.com/health",
                60,
                5000
        );

        when(serviceRepository.existsByNameIgnoreCase("Existing Service")).thenReturn(true);

        assertThatThrownBy(() -> serviceManagementService.createService(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Existing Service");

        verify(serviceRepository, never()).save(any());
    }

    @Test
    @DisplayName("getServiceById: Returns mapped response when service exists")
    void getServiceById_Found() {
        MonitoredService service = new MonitoredService(
                "Inventory Service",
                null,
                "https://inventory.example.com/health",
                60,
                5000
        );
        UUID id = service.getId();

        when(serviceRepository.findById(id)).thenReturn(Optional.of(service));

        ServiceResponse response = serviceManagementService.getServiceById(id);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(id);
        assertThat(response.name()).isEqualTo("Inventory Service");
    }

    @Test
    @DisplayName("getServiceById: Throws ResourceNotFoundException when service does not exist")
    void getServiceById_NotFound_ThrowsException() {
        UUID randomId = UUID.randomUUID();
        when(serviceRepository.findById(randomId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceManagementService.getServiceById(randomId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(randomId.toString());
    }

    @Test
    @DisplayName("listServices: Applies filters and pageable accurately")
    void listServices_WithFilters() {
        MonitoredService service = new MonitoredService(
                "Order Service",
                null,
                "https://order.example.com/health",
                60,
                5000
        );
        Pageable pageable = PageRequest.of(0, 10);
        Page<MonitoredService> page = new PageImpl<>(List.of(service), pageable, 1);

        when(serviceRepository.findByEnabledAndStatus(true, ServiceStatus.UNKNOWN, pageable))
                .thenReturn(page);

        Page<ServiceResponse> result = serviceManagementService.listServices(true, ServiceStatus.UNKNOWN, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).name()).isEqualTo("Order Service");
        verify(serviceRepository).findByEnabledAndStatus(true, ServiceStatus.UNKNOWN, pageable);
    }

    @Test
    @DisplayName("updateService: Successfully updates existing service configuration")
    void updateService_Success() {
        MonitoredService service = new MonitoredService(
                "Old Name",
                "Old Desc",
                "https://old.example.com/health",
                60,
                5000
        );
        UUID id = service.getId();

        UpdateServiceRequest request = new UpdateServiceRequest(
                "New Name",
                "New Desc",
                "https://new.example.com/health",
                20,
                3000
        );

        when(serviceRepository.findById(id)).thenReturn(Optional.of(service));
        when(serviceRepository.existsByNameIgnoreCaseAndIdNot("New Name", id)).thenReturn(false);

        ServiceResponse response = serviceManagementService.updateService(id, request);

        assertThat(response.name()).isEqualTo("New Name");
        assertThat(response.description()).isEqualTo("New Desc");
        assertThat(response.url()).isEqualTo("https://new.example.com/health");
        assertThat(response.checkIntervalSeconds()).isEqualTo(20);
        assertThat(response.timeoutMs()).isEqualTo(3000);
    }

    @Test
    @DisplayName("updateService: Throws DuplicateResourceException when another service has same name")
    void updateService_DuplicateNameOnDifferentService_ThrowsException() {
        MonitoredService service = new MonitoredService(
                "Current Name",
                null,
                "https://current.example.com/health",
                60,
                5000
        );
        UUID id = service.getId();

        UpdateServiceRequest request = new UpdateServiceRequest(
                "Conflicting Name",
                null,
                "https://current.example.com/health",
                60,
                5000
        );

        when(serviceRepository.findById(id)).thenReturn(Optional.of(service));
        when(serviceRepository.existsByNameIgnoreCaseAndIdNot("Conflicting Name", id)).thenReturn(true);

        assertThatThrownBy(() -> serviceManagementService.updateService(id, request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Conflicting Name");
    }

    @Test
    @DisplayName("updateServiceStatus: Successfully disables and enables service")
    void updateServiceStatus_Success() {
        MonitoredService service = new MonitoredService(
                "API Gateway",
                null,
                "https://gw.example.com/health",
                60,
                5000
        );
        UUID id = service.getId();

        when(serviceRepository.findById(id)).thenReturn(Optional.of(service));

        ServiceResponse disabled = serviceManagementService.updateServiceStatus(id, new PatchServiceStatusRequest(false));
        assertThat(disabled.enabled()).isFalse();

        ServiceResponse enabled = serviceManagementService.updateServiceStatus(id, new PatchServiceStatusRequest(true));
        assertThat(enabled.enabled()).isTrue();
    }

    @Test
    @DisplayName("deleteService: Deletes service when exists")
    void deleteService_Success() {
        UUID id = UUID.randomUUID();
        when(serviceRepository.existsById(id)).thenReturn(true);

        serviceManagementService.deleteService(id);

        verify(serviceRepository).deleteById(id);
    }

    @Test
    @DisplayName("deleteService: Throws ResourceNotFoundException when id does not exist")
    void deleteService_NotFound_ThrowsException() {
        UUID id = UUID.randomUUID();
        when(serviceRepository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> serviceManagementService.deleteService(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(id.toString());

        verify(serviceRepository, never()).deleteById(any());
    }
}
