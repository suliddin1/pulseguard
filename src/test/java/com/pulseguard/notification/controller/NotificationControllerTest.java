package com.pulseguard.notification.controller;

import com.pulseguard.common.exception.GlobalExceptionHandler;
import com.pulseguard.common.exception.ResourceNotFoundException;
import com.pulseguard.notification.dto.NotificationAttemptResponse;
import com.pulseguard.notification.dto.NotificationDetailResponse;
import com.pulseguard.notification.dto.NotificationResponse;
import com.pulseguard.notification.model.AttemptOutcome;
import com.pulseguard.notification.model.NotificationChannelType;
import com.pulseguard.notification.model.NotificationEventType;
import com.pulseguard.notification.model.OutboxStatus;
import com.pulseguard.notification.service.NotificationManagementService;
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

@WebMvcTest(controllers = NotificationController.class)
@Import(GlobalExceptionHandler.class)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NotificationManagementService managementService;

    @Test
    @DisplayName("GET /api/v1/notifications: Returns 200 OK with paginated list of notifications")
    void getNotifications_Returns200() throws Exception {
        UUID id = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        Instant now = Instant.now();

        NotificationResponse response = new NotificationResponse(
                id,
                UUID.randomUUID(),
                NotificationChannelType.WEBHOOK,
                NotificationEventType.INCIDENT_OPENED,
                incidentId,
                serviceId,
                OutboxStatus.DELIVERED,
                1,
                now,
                null,
                200,
                now,
                now,
                now
        );

        when(managementService.getNotifications(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/notifications")
                        .param("status", "DELIVERED")
                        .param("channel", "WEBHOOK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id", is(id.toString())))
                .andExpect(jsonPath("$.content[0].channel", is("WEBHOOK")))
                .andExpect(jsonPath("$.content[0].status", is("DELIVERED")))
                .andExpect(jsonPath("$.content[0].lastHttpStatus", is(200)))
                .andExpect(jsonPath("$.content[0].payload").doesNotExist()) // payload never leaked
                .andExpect(jsonPath("$.content[0].leaseToken").doesNotExist()); // lease token never leaked
    }

    @Test
    @DisplayName("GET /api/v1/notifications/{id}: Returns 200 OK with detail and attempt history")
    void getNotificationById_Returns200() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();

        NotificationAttemptResponse attempt = new NotificationAttemptResponse(
                UUID.randomUUID(),
                1,
                now,
                150,
                AttemptOutcome.SUCCESS,
                200,
                null
        );

        NotificationDetailResponse detail = new NotificationDetailResponse(
                id,
                UUID.randomUUID(),
                NotificationChannelType.WEBHOOK,
                NotificationEventType.INCIDENT_OPENED,
                UUID.randomUUID(),
                UUID.randomUUID(),
                OutboxStatus.DELIVERED,
                1,
                now,
                null,
                200,
                now,
                now,
                now,
                List.of(attempt)
        );

        when(managementService.getNotificationById(id)).thenReturn(detail);

        mockMvc.perform(get("/api/v1/notifications/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(id.toString())))
                .andExpect(jsonPath("$.status", is("DELIVERED")))
                .andExpect(jsonPath("$.attempts", hasSize(1)))
                .andExpect(jsonPath("$.attempts[0].attemptNumber", is(1)))
                .andExpect(jsonPath("$.attempts[0].outcome", is("SUCCESS")))
                .andExpect(jsonPath("$.attempts[0].httpStatus", is(200)))
                .andExpect(jsonPath("$.payload").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/notifications/{id}: Returns 404 when notification does not exist")
    void getNotificationById_NotFound_Returns404() throws Exception {
        UUID nonExistent = UUID.randomUUID();
        when(managementService.getNotificationById(nonExistent))
                .thenThrow(new ResourceNotFoundException("Notification", nonExistent));

        mockMvc.perform(get("/api/v1/notifications/{id}", nonExistent))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.message", is("Notification not found with identifier: " + nonExistent)));
    }
}
