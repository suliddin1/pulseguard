package com.pulseguard.notification.dto;

import com.pulseguard.notification.model.AttemptOutcome;
import com.pulseguard.notification.model.NotificationAttempt;

import java.time.Instant;
import java.util.UUID;

public record NotificationAttemptResponse(
        UUID id,
        int attemptNumber,
        Instant attemptedAt,
        long durationMs,
        AttemptOutcome outcome,
        Integer httpStatus,
        String errorMessage
) {
    public static NotificationAttemptResponse from(NotificationAttempt entity) {
        return new NotificationAttemptResponse(
                entity.getId(),
                entity.getAttemptNumber(),
                entity.getAttemptedAt(),
                entity.getDurationMs(),
                entity.getOutcome(),
                entity.getHttpStatus(),
                entity.getErrorMessage()
        );
    }
}
