package com.pulseguard.notification.dto;

import com.pulseguard.notification.model.NotificationChannelType;
import com.pulseguard.notification.model.NotificationEventType;
import com.pulseguard.notification.model.NotificationOutbox;
import com.pulseguard.notification.model.OutboxStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Detailed view of an outbox record including attempt history.
 * Deliberately omits the payload and lease token.
 */
public record NotificationDetailResponse(
        UUID id,
        UUID eventId,
        NotificationChannelType channel,
        NotificationEventType eventType,
        UUID incidentId,
        UUID serviceId,
        OutboxStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        String lastError,
        Integer lastHttpStatus,
        Instant createdAt,
        Instant updatedAt,
        Instant deliveredAt,
        List<NotificationAttemptResponse> attempts
) {
    public static NotificationDetailResponse from(NotificationOutbox entity, List<NotificationAttemptResponse> attempts) {
        return new NotificationDetailResponse(
                entity.getId(),
                entity.getEventId(),
                entity.getChannel(),
                entity.getEventType(),
                entity.getIncidentId(),
                entity.getServiceId(),
                entity.getStatus(),
                entity.getAttemptCount(),
                entity.getNextAttemptAt(),
                entity.getLastError(),
                entity.getLastHttpStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeliveredAt(),
                attempts != null ? attempts : List.of()
        );
    }
}
