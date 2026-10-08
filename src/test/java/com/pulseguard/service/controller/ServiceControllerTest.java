package com.pulseguard.service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseguard.common.exception.DuplicateResourceException;
import com.pulseguard.common.exception.GlobalExceptionHandler;
import com.pulseguard.common.exception.ResourceNotFoundException;
import com.pulseguard.service.dto.CreateServiceRequest;
import com.pulseguard.service.dto.PatchServiceStatusRequest;
import com.pulseguard.service.dto.ServiceResponse;
import com.pulseguard.service.dto.UpdateServiceRequest;
import com.pulseguard.service.model.ServiceStatus;
import com.pulseguard.service.service.ServiceManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ServiceController.class)
@Import(GlobalExceptionHandler.class)
class ServiceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ServiceManagementService serviceManagementService;

    @Test
    @DisplayName("POST /api/v1/services: Returns 201 Created and Location header on valid request")
    void createService_ValidPayload_Returns201() throws Exception {
        UUID id = UUID.randomUUID();
        CreateServiceRequest request = new CreateServiceRequest(
                "Billing Service",
                "Handles payment processing",
                "https://billing.example.com/health",
                30,
                3000
        );

        ServiceResponse response = new ServiceResponse(
                id,
                "Billing Service",
                "Handles payment processing",
                "https://billing.example.com/health",
                30,
                3000,
                true,
                ServiceStatus.UNKNOWN,
                Instant.now(),
                Instant.now()
        );

        when(serviceManagementService.createService(any(CreateServiceRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/services/" + id)))
                .andExpect(jsonPath("$.id", is(id.toString())))
                .andExpect(jsonPath("$.name", is("Billing Service")))
                .andExpect(jsonPath("$.url", is("https://billing.example.com/health")))
                .andExpect(jsonPath("$.enabled", is(true)))
                .andExpect(jsonPath("$.status", is("UNKNOWN")));
    }

    @Test
    @DisplayName("POST /api/v1/services: Returns 400 Bad Request when name is blank")
    void createService_BlankName_Returns400() throws Exception {
        CreateServiceRequest request = new CreateServiceRequest(
                "",
                "Handles payment processing",
                "https://billing.example.com/health",
                30,
                3000
        );

        mockMvc.perform(post("/api/v1/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.validationErrors").isArray())
                .andExpect(jsonPath("$.validationErrors[0].field", is("name")));
    }

    @Test
    @DisplayName("POST /api/v1/services: Returns 400 Bad Request when URL format is invalid")
    void createService_InvalidUrl_Returns400() throws Exception {
        CreateServiceRequest request = new CreateServiceRequest(
                "Test Service",
                null,
                "ftp://invalid-url.com",
                30,
                3000
        );

        mockMvc.perform(post("/api/v1/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.validationErrors[0].field", is("url")));
    }

    @Test
    @DisplayName("POST /api/v1/services: Returns 400 Bad Request when interval is below minimum")
    void createService_IntervalBelowMinimum_Returns400() throws Exception {
        CreateServiceRequest request = new CreateServiceRequest(
                "Test Service",
                null,
                "https://service.example.com/health",
                2,
                3000
        );

        mockMvc.perform(post("/api/v1/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.validationErrors[0].field", is("checkIntervalSeconds")));
    }

    @Test
    @DisplayName("POST /api/v1/services: Returns 409 Conflict when service name already exists")
    void createService_DuplicateName_Returns409() throws Exception {
        CreateServiceRequest request = new CreateServiceRequest(
                "Existing Service",
                null,
                "https://existing.example.com/health",
                60,
                5000
        );

        when(serviceManagementService.createService(any(CreateServiceRequest.class)))
                .thenThrow(new DuplicateResourceException("Service", "name", "Existing Service"));

        mockMvc.perform(post("/api/v1/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.message", containsString("already exists")));
    }

    @Test
    @DisplayName("GET /api/v1/services/{id}: Returns 200 OK when found")
    void getServiceById_Found_Returns200() throws Exception {
        UUID id = UUID.randomUUID();
        ServiceResponse response = new ServiceResponse(
                id,
                "Auth Service",
                null,
                "https://auth.example.com/health",
                60,
                5000,
                true,
                ServiceStatus.HEALTHY,
                Instant.now(),
                Instant.now()
        );

        when(serviceManagementService.getServiceById(id)).thenReturn(response);

        mockMvc.perform(get("/api/v1/services/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(id.toString())))
                .andExpect(jsonPath("$.name", is("Auth Service")))
                .andExpect(jsonPath("$.status", is("HEALTHY")));
    }

    @Test
    @DisplayName("GET /api/v1/services/{id}: Returns 404 Not Found when service does not exist")
    void getServiceById_NotFound_Returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(serviceManagementService.getServiceById(id))
                .thenThrow(new ResourceNotFoundException("Service", id));

        mockMvc.perform(get("/api/v1/services/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.message", containsString("not found")));
    }

    @Test
    @DisplayName("GET /api/v1/services: Returns 200 OK and paginated response")
    void listServices_Returns200() throws Exception {
        UUID id = UUID.randomUUID();
        ServiceResponse item = new ServiceResponse(
                id,
                "Test Service",
                null,
                "https://test.example.com",
                60,
                5000,
                true,
                ServiceStatus.UNKNOWN,
                Instant.now(),
                Instant.now()
        );

        when(serviceManagementService.listServices(eq(null), eq(null), any()))
                .thenReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].name", is("Test Service")));
    }

    @Test
    @DisplayName("PUT /api/v1/services/{id}: Returns 200 OK on valid update")
    void updateService_Valid_Returns200() throws Exception {
        UUID id = UUID.randomUUID();
        UpdateServiceRequest request = new UpdateServiceRequest(
                "Updated Name",
                "New description",
                "https://updated.example.com/health",
                30,
                3000
        );

        ServiceResponse response = new ServiceResponse(
                id,
                "Updated Name",
                "New description",
                "https://updated.example.com/health",
                30,
                3000,
                true,
                ServiceStatus.UNKNOWN,
                Instant.now(),
                Instant.now()
        );

        when(serviceManagementService.updateService(eq(id), any(UpdateServiceRequest.class)))
                .thenReturn(response);

        mockMvc.perform(put("/api/v1/services/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Updated Name")))
                .andExpect(jsonPath("$.checkIntervalSeconds", is(30)));
    }

    @Test
    @DisplayName("PATCH /api/v1/services/{id}/status: Returns 200 OK on valid toggle")
    void patchServiceStatus_Valid_Returns200() throws Exception {
        UUID id = UUID.randomUUID();
        PatchServiceStatusRequest request = new PatchServiceStatusRequest(false);

        ServiceResponse response = new ServiceResponse(
                id,
                "Test Service",
                null,
                "https://test.example.com",
                60,
                5000,
                false,
                ServiceStatus.UNKNOWN,
                Instant.now(),
                Instant.now()
        );

        when(serviceManagementService.updateServiceStatus(eq(id), any(PatchServiceStatusRequest.class)))
                .thenReturn(response);

        mockMvc.perform(patch("/api/v1/services/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(false)));
    }

    @Test
    @DisplayName("DELETE /api/v1/services/{id}: Returns 204 No Content when deleted")
    void deleteService_Found_Returns204() throws Exception {
        UUID id = UUID.randomUUID();
        doNothing().when(serviceManagementService).deleteService(id);

        mockMvc.perform(delete("/api/v1/services/{id}", id))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /api/v1/services/{id}: Returns 404 Not Found when service does not exist")
    void deleteService_NotFound_Returns404() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new ResourceNotFoundException("Service", id))
                .when(serviceManagementService).deleteService(id);

        mockMvc.perform(delete("/api/v1/services/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)));
    }
}
