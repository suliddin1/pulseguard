package com.pulseguard.notification.controller;

import com.pulseguard.notification.dto.NotificationDetailResponse;
import com.pulseguard.notification.dto.NotificationResponse;
import com.pulseguard.notification.model.NotificationChannelType;
import com.pulseguard.notification.model.OutboxStatus;
import com.pulseguard.notification.service.NotificationManagementService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationManagementService managementService;

    public NotificationController(NotificationManagementService managementService) {
        this.managementService = managementService;
    }

    @GetMapping
    public ResponseEntity<Page<NotificationResponse>> getNotifications(
            @RequestParam(required = false) OutboxStatus status,
            @RequestParam(required = false) NotificationChannelType channel,
            @RequestParam(required = false) UUID incidentId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<NotificationResponse> result = managementService.getNotifications(status, channel, incidentId, pageable);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<NotificationDetailResponse> getNotificationById(@PathVariable UUID id) {
        NotificationDetailResponse result = managementService.getNotificationById(id);
        return ResponseEntity.ok(result);
    }
}
