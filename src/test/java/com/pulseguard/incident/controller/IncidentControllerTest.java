package com.pulseguard.incident.controller;

import com.pulseguard.common.exception.GlobalExceptionHandler;
import com.pulseguard.common.exception.ResourceNotFoundException;
import com.pulseguard.incident.dto.IncidentResponse;
import com.pulseguard.incident.model.IncidentSeverity;
import com.pulseguard.incident.model.IncidentStatus;
import com.pulseguard.incident.model.IncidentType;
import com.pulseguard.incident.service.IncidentManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = IncidentController.class)
@Import(GlobalExceptionHandler.class)
class IncidentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IncidentManagementService incidentManagementService;

    @Test
    @DisplayName("GET /api/v1/services/{serviceId}/incidents: Returns 200 OK with paginated incidents")
    void getIncidentsForService_Success_Returns200() throws Exception {
        UUID serviceId = UUID.randomUUID();
        IncidentResponse response = createSampleResponse(serviceId);

        when(incidentManagementService.getIncidentsForService(eq(serviceId), any()))
                .thenReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/services/{serviceId}/incidents", serviceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].serviceId", is(serviceId.toString())))
                .andExpect(jsonPath("$.content[0].status", is("OPEN")))
                .andExpect(jsonPath("$.content[0].severity", is("CRITICAL")))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    @DisplayName("GET /api/v1/services/{serviceId}/incidents: Returns 404 when service does not exist")
    void getIncidentsForService_NotFound_Returns404() throws Exception {
        UUID serviceId = UUID.randomUUID();

        when(incidentManagementService.getIncidentsForService(eq(serviceId), any()))
                .thenThrow(new ResourceNotFoundException("Service", serviceId));

        mockMvc.perform(get("/api/v1/services/{serviceId}/incidents", serviceId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.error", is("Not Found")));
    }

    @Test
    @DisplayName("GET /api/v1/incidents: Returns 200 OK with global paginated incidents")
    void getIncidents_Global_Returns200() throws Exception {
        UUID serviceId = UUID.randomUUID();
        IncidentResponse response = createSampleResponse(serviceId);

        when(incidentManagementService.getIncidents(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/incidents")
                        .param("status", "OPEN")
                        .param("severity", "CRITICAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].summary", is("Service unavailable")))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    @DisplayName("GET /api/v1/incidents/open: Returns 200 OK with open incidents")
    void getOpenIncidents_Returns200() throws Exception {
        UUID serviceId = UUID.randomUUID();
        IncidentResponse response = createSampleResponse(serviceId);

        when(incidentManagementService.getOpenIncidents(any()))
                .thenReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/incidents/open"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].status", is("OPEN")));
    }

    @Test
    @DisplayName("GET /api/v1/incidents/{id}: Returns 200 OK for existing incident")
    void getIncidentById_Success_Returns200() throws Exception {
        UUID incidentId = UUID.randomUUID();
        IncidentResponse response = createSampleResponse(UUID.randomUUID(), incidentId);

        when(incidentManagementService.getIncidentById(incidentId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/incidents/{id}", incidentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(incidentId.toString())))
                .andExpect(jsonPath("$.summary", is("Service unavailable")));
    }

    @Test
    @DisplayName("GET /api/v1/incidents/{id}: Returns 404 for missing incident")
    void getIncidentById_NotFound_Returns404() throws Exception {
        UUID incidentId = UUID.randomUUID();

        when(incidentManagementService.getIncidentById(incidentId))
                .thenThrow(new ResourceNotFoundException("Incident", incidentId));

        mockMvc.perform(get("/api/v1/incidents/{id}", incidentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.error", is("Not Found")));
    }

    @Test
    @DisplayName("GET /api/v1/incidents: Returns 400 Bad Request when 'from' is after 'to'")
    void getIncidents_InvalidDateRange_Returns400() throws Exception {
        when(incidentManagementService.getIncidents(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("'from' timestamp must be before or equal to 'to' timestamp"));

        mockMvc.perform(get("/api/v1/incidents")
                        .param("from", "2026-10-10T12:00:00Z")
                        .param("to", "2026-10-10T10:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.error", is("Bad Request")));
    }

    private IncidentResponse createSampleResponse(UUID serviceId) {
        return createSampleResponse(serviceId, UUID.randomUUID());
    }

    private IncidentResponse createSampleResponse(UUID serviceId, UUID incidentId) {
        Instant now = Instant.now();
        return new IncidentResponse(
                incidentId,
                serviceId,
                "Checkout Service",
                IncidentType.SERVICE_UNAVAILABLE,
                IncidentSeverity.CRITICAL,
                IncidentStatus.OPEN,
                "Service unavailable",
                "HTTP 500 Server Error",
                now.minusSeconds(120),
                now,
                null,
                3L,
                now.minusSeconds(120),
                now
        );
    }
}
