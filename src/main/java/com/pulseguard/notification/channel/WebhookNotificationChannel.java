package com.pulseguard.notification.channel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseguard.notification.config.NotificationProperties;
import com.pulseguard.notification.http.OutboundHttpClient;
import com.pulseguard.notification.model.NotificationChannelType;
import com.pulseguard.notification.payload.NotificationPayload;
import com.pulseguard.notification.security.PermanentDeliveryException;
import com.pulseguard.notification.security.Redactor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Generic JSON webhook. See the README for the payload contract and signature scheme.
 *
 * <p>Headers: {@code X-PulseGuard-Event}, {@code X-PulseGuard-Delivery} (outbox row id, stable across
 * retries), {@code Idempotency-Key} (event id: receivers can de-duplicate at-least-once redeliveries) and,
 * when a secret is configured, {@code X-PulseGuard-Timestamp} and {@code X-PulseGuard-Signature}
 * ({@code sha256=hex(HMAC_SHA256(secret, timestamp + "." + body))}).
 */
@Component
public class WebhookNotificationChannel extends AbstractHttpNotificationChannel {

    /** The body actually POSTed: the stored snapshot plus the per-delivery id. */
    record Envelope(
            int schemaVersion,
            UUID eventId,
            UUID deliveryId,
            String eventType,
            java.time.Instant occurredAt,
            NotificationPayload.ServiceInfo service,
            NotificationPayload.IncidentInfo incident
    ) {
    }

    private final NotificationProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public WebhookNotificationChannel(
            NotificationProperties properties,
            OutboundHttpClient httpClient,
            Redactor redactor,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        super(httpClient, redactor);
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public NotificationChannelType type() {
        return NotificationChannelType.WEBHOOK;
    }

    @Override
    public boolean isEnabled() {
        return properties.getWebhook().isEnabled();
    }

    @Override
    public DeliveryResult deliver(NotificationMessage message) {
        NotificationProperties.Webhook config = properties.getWebhook();
        try {
            URI uri = parse(config.getUrl());
            NotificationPayload payload = message.payload();
            byte[] body = objectMapper.writeValueAsBytes(new Envelope(
                    payload.schemaVersion(),
                    payload.eventId(),
                    message.deliveryId(),
                    payload.eventType().name(),
                    payload.occurredAt(),
                    payload.service(),
                    payload.incident()
            ));

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json; charset=utf-8");
            headers.put("User-Agent", "PulseGuard-Webhook/1");
            headers.put("X-PulseGuard-Event", payload.eventType().name());
            headers.put("X-PulseGuard-Delivery", message.deliveryId().toString());
            headers.put("Idempotency-Key", payload.eventId().toString());
            if (!config.getSecret().isEmpty()) {
                String timestamp = Long.toString(clock.instant().getEpochSecond());
                headers.put("X-PulseGuard-Timestamp", timestamp);
                headers.put("X-PulseGuard-Signature", "sha256=" + sign(config.getSecret(), timestamp, body));
            }
            return post(uri, headers, body);
        } catch (PermanentDeliveryException ex) {
            return DeliveryResult.permanent(null, redactor().describe(ex));
        } catch (JsonProcessingException ex) {
            return DeliveryResult.permanent(null, "payload_serialization_failed");
        } catch (RuntimeException ex) {
            return DeliveryResult.permanent(null, redactor().describe(ex));
        }
    }

    /** {@code hex(HMAC_SHA256(secret, timestamp + "." + body))}. Public so receivers/tests can mirror it. */
    public static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is unavailable", ex);
        }
    }
}
