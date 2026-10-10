package com.pulseguard.notification.service;

import com.pulseguard.notification.dto.NotificationDetailResponse;
import com.pulseguard.notification.dto.NotificationResponse;
import com.pulseguard.notification.model.NotificationChannelType;
import com.pulseguard.notification.model.OutboxStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface NotificationManagementService {

    Page<NotificationResponse> getNotifications(
            OutboxStatus status,
            NotificationChannelType channel,
            UUID incidentId,
            Pageable pageable
    );

    NotificationDetailResponse getNotificationById(UUID id);
}
