package com.pulseguard.notification.service;

import com.pulseguard.notification.model.NotificationChannelType;
import com.pulseguard.notification.model.NotificationEventType;

import java.util.UUID;

/**
 * A row this worker has exclusively claimed. {@code leaseToken} fences all subsequent state changes: if the
 * lease was lost (expired and re-claimed elsewhere), completion updates match zero rows and are ignored.
 */
public record ClaimedNotification(
        UUID id,
        UUID eventId,
        NotificationChannelType channel,
        NotificationEventType eventType,
        String payload,
        int attemptNumber,
        String leaseToken
) {

    @Override
    public String toString() {
        // No payload, no lease token.
        return "ClaimedNotification{id=" + id + ", channel=" + channel + ", eventType=" + eventType
                + ", attemptNumber=" + attemptNumber + '}';
    }
}
