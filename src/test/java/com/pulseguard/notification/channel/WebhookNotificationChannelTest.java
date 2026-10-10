package com.pulseguard.notification.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pulseguard.incident.model.Incident;
import com.pulseguard.incident.model.IncidentSeverity;
import com.pulseguard.incident.model.IncidentType;
import com.pulseguard.notification.config.NotificationProperties;
import com.pulseguard.notification.http.OutboundHttpClient;
import com.pulseguard.notification.model.NotificationEventType;
import com.pulseguard.notification.payload.NotificationPayload;
import com.pulseguard.notification.security.Redactor;
import com.pulseguard.service.model.MonitoredService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookNotificationChannelTest {

    private OutboundHttpClient mockHttpClient;
    private NotificationProperties properties;
    private ObjectMapper objectMapper;
    private Clock fixedClock;
    private WebhookNotificationChannel channel;

    @BeforeEach
    void setUp() {
        mockHttpClient = mock(OutboundHttpClient.class);
        properties = new NotificationProperties();
        properties.getWebhook().setEnabled(true);
        properties.getWebhook().setUrl("https://api.example.com/pulseguard-events");
        properties.getWebhook().setSecret("secret-hmac-key");

        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        fixedClock = Clock.fixed(Instant.parse("2026-10-10T15:00:00Z"), ZoneId.of("UTC"));
        Redactor redactor = new Redactor(List.of(properties.getWebhook().getUrl(), properties.getWebhook().getSecret()));

        channel = new WebhookNotificationChannel(properties, mockHttpClient, redactor, objectMapper, fixedClock);
    }

    @Test
    @DisplayName("deliver builds signed envelope with idempotency headers and sends HTTP POST")
    @SuppressWarnings("unchecked")
    void deliverBuildsSignedEnvelopeAndHeaders() throws Exception {
        when(mockHttpClient.post(any(), any(), any())).thenReturn(200);

        MonitoredService service = new MonitoredService("Billing API", "desc", "https://bill.com", 30, 3000);
        Incident incident = new Incident(service, IncidentType.SERVICE_UNAVAILABLE, IncidentSeverity.CRITICAL, "Service is down", "Connection timeout", Instant.now());
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();

        NotificationPayload payload = NotificationPayload.of(eventId, NotificationEventType.INCIDENT_OPENED, Instant.now(), incident);
        NotificationMessage message = new NotificationMessage(deliveryId, 1, payload);

        DeliveryResult result = channel.deliver(message);

        assertThat(result.kind()).isEqualTo(DeliveryResult.Kind.SUCCESS);
        assertThat(result.httpStatus()).isEqualTo(200);

        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);

        verify(mockHttpClient).post(uriCaptor.capture(), headersCaptor.capture(), bodyCaptor.capture());

        assertThat(uriCaptor.getValue()).isEqualTo(URI.create("https://api.example.com/pulseguard-events"));
        Map<String, String> headers = headersCaptor.getValue();
        assertThat(headers.get("Content-Type")).contains("application/json");
        assertThat(headers.get("X-PulseGuard-Event")).isEqualTo("INCIDENT_OPENED");
        assertThat(headers.get("X-PulseGuard-Delivery")).isEqualTo(deliveryId.toString());
        assertThat(headers.get("Idempotency-Key")).isEqualTo(eventId.toString());
        assertThat(headers.get("X-PulseGuard-Timestamp")).isEqualTo("1791644400");
        assertThat(headers.get("X-PulseGuard-Signature")).startsWith("sha256=");

        // Verify HMAC signature matches
        byte[] body = bodyCaptor.getValue();
        String expectedSig = "sha256=" + WebhookNotificationChannel.sign("secret-hmac-key", "1791644400", body);
        assertThat(headers.get("X-PulseGuard-Signature")).isEqualTo(expectedSig);

        // Verify JSON body
        JsonNode json = objectMapper.readTree(body);
        assertThat(json.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(json.get("deliveryId").asText()).isEqualTo(deliveryId.toString());
        assertThat(json.get("eventType").asText()).isEqualTo("INCIDENT_OPENED");
        assertThat(json.get("service").get("name").asText()).isEqualTo("Billing API");
        assertThat(json.get("incident").get("summary").asText()).isEqualTo("Service is down");
    }

    @Test
    @DisplayName("deliver classifies 5xx and 429 as RETRYABLE")
    void deliverClassifiesRetryableErrors() throws Exception {
        when(mockHttpClient.post(any(), any(), any())).thenReturn(503);

        MonitoredService service = new MonitoredService("Test", "desc", "https://t.com", 30, 3000);
        Incident incident = new Incident(service, IncidentType.SERVICE_UNAVAILABLE, IncidentSeverity.CRITICAL, "Down", "err", Instant.now());
        NotificationPayload payload = NotificationPayload.of(UUID.randomUUID(), NotificationEventType.INCIDENT_OPENED, Instant.now(), incident);

        DeliveryResult result = channel.deliver(new NotificationMessage(UUID.randomUUID(), 1, payload));
        assertThat(result.kind()).isEqualTo(DeliveryResult.Kind.RETRYABLE);
        assertThat(result.httpStatus()).isEqualTo(503);
    }

    @Test
    @DisplayName("deliver classifies 4xx as PERMANENT")
    void deliverClassifiesPermanentErrors() throws Exception {
        when(mockHttpClient.post(any(), any(), any())).thenReturn(404);

        MonitoredService service = new MonitoredService("Test", "desc", "https://t.com", 30, 3000);
        Incident incident = new Incident(service, IncidentType.SERVICE_UNAVAILABLE, IncidentSeverity.CRITICAL, "Down", "err", Instant.now());
        NotificationPayload payload = NotificationPayload.of(UUID.randomUUID(), NotificationEventType.INCIDENT_OPENED, Instant.now(), incident);

        DeliveryResult result = channel.deliver(new NotificationMessage(UUID.randomUUID(), 1, payload));
        assertThat(result.kind()).isEqualTo(DeliveryResult.Kind.PERMANENT);
        assertThat(result.httpStatus()).isEqualTo(404);
    }
}
