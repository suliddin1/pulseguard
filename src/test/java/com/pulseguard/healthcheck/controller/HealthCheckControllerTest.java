package com.pulseguard.healthcheck.controller;

import com.pulseguard.common.exception.GlobalExceptionHandler;
import com.pulseguard.common.exception.ResourceNotFoundException;
import com.pulseguard.common.exception.ServiceDisabledException;
import com.pulseguard.healthcheck.dto.HealthCheckResponse;
import com.pulseguard.healthcheck.model.HealthCheckResult;
import com.pulseguard.healthcheck.service.HealthCheckExecutionService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = HealthCheckController.class)
@Import(GlobalExceptionHandler.class)
class HealthCheckControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HealthCheckExecutionService healthCheckExecutionService;

    @Test
    @DisplayName("POST /api/v1/services/{id}/checks: Returns 200 OK with execution result")
    void triggerHealthCheck_Success_Returns200() throws Exception {
        UUID serviceId = UUID.randomUUID();
        UUID checkId = UUID.randomUUID();

        HealthCheckResponse response = new HealthCheckResponse(
                checkId,
                serviceId,
                "Billing Service",
                Instant.now(),
                95L,
                200,
                HealthCheckResult.SUCCESS,
                null
        );

        when(healthCheckExecutionService.executeHealthCheck(serviceId)).thenReturn(response);

        mockMvc.perform(post("/api/v1/services/{serviceId}/checks", serviceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(checkId.toString())))
                .andExpect(jsonPath("$.serviceId", is(serviceId.toString())))
                .andExpect(jsonPath("$.serviceName", is("Billing Service")))
                .andExpect(jsonPath("$.result", is("SUCCESS")))
                .andExpect(jsonPath("$.httpStatusCode", is(200)))
                .andExpect(jsonPath("$.responseTimeMs", is(95)));
    }

    @Test
    @DisplayName("POST /api/v1/services/{id}/checks: Returns 404 Not Found when service does not exist")
    void triggerHealthCheck_NotFound_Returns404() throws Exception {
        UUID nonExistentId = UUID.randomUUID();
        when(healthCheckExecutionService.executeHealthCheck(nonExistentId))
                .thenThrow(new ResourceNotFoundException("Service", nonExistentId));

        mockMvc.perform(post("/api/v1/services/{serviceId}/checks", nonExistentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("POST /api/v1/services/{id}/checks: Returns 400 Bad Request when service is disabled")
    void triggerHealthCheck_Disabled_Returns400() throws Exception {
        UUID serviceId = UUID.randomUUID();
        when(healthCheckExecutionService.executeHealthCheck(serviceId))
                .thenThrow(new ServiceDisabledException(serviceId, "Disabled API"));

        mockMvc.perform(post("/api/v1/services/{serviceId}/checks", serviceId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("disabled")));
    }

    @Test
    @DisplayName("GET /api/v1/services/{id}/checks: Returns 200 OK with paginated list")
    void getHistoricalChecks_Success_Returns200() throws Exception {
        UUID serviceId = UUID.randomUUID();
        UUID checkId = UUID.randomUUID();

        HealthCheckResponse check = new HealthCheckResponse(
                checkId,
                serviceId,
                "Auth API",
                Instant.now(),
                45L,
                200,
                HealthCheckResult.SUCCESS,
                null
        );

        when(healthCheckExecutionService.getHistoricalChecks(eq(serviceId), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(check), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/services/{serviceId}/checks", serviceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id", is(checkId.toString())))
                .andExpect(jsonPath("$.content[0].result", is("SUCCESS")));
    }

    @Test
    @DisplayName("GET /api/v1/services/{id}/checks: Returns 404 Not Found when service does not exist")
    void getHistoricalChecks_NotFound_Returns404() throws Exception {
        UUID nonExistentId = UUID.randomUUID();
        when(healthCheckExecutionService.getHistoricalChecks(eq(nonExistentId), any(), any(), any()))
                .thenThrow(new ResourceNotFoundException("Service", nonExistentId));

        mockMvc.perform(get("/api/v1/services/{serviceId}/checks", nonExistentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)));
    }
}
