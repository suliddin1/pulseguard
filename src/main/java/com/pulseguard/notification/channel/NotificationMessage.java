package com.pulseguard.notification.channel;

import com.pulseguard.notification.payload.NotificationPayload;

import java.util.UUID;

/** What a channel is asked to deliver. {@code deliveryId} is the outbox row id and is stable across retries. */
public record NotificationMessage(UUID deliveryId, int attempt, NotificationPayload payload) {
}
