package com.pulseguard.notification.dto;

import com.pulseguard.notification.model.NotificationChannelType;
import com.pulseguard.notification.model.NotificationEventType;
import com.pulseguard.notification.model.NotificationOutbox;
import com.pulseguard.notification.model.OutboxStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Summary DTO for an outbox record. Deliberately omits the payload and lease token
 * to protect against credential or secret leakage.
 */
public record NotificationResponse(
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
        Instant deliveredAt
) {
    public static NotificationResponse from(NotificationOutbox entity) {
        return new NotificationResponse(
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
                entity.getDeliveredAt()
        );
    }
}
