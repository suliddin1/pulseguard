package com.pulseguard.notification.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseguard.incident.model.Incident;
import com.pulseguard.incident.model.IncidentSeverity;
import com.pulseguard.incident.model.IncidentType;
import com.pulseguard.notification.config.NotificationProperties;
import com.pulseguard.notification.http.OutboundHttpClient;
import com.pulseguard.notification.model.NotificationEventType;
import com.pulseguard.notification.payload.NotificationPayload;
import com.pulseguard.notification.security.DestinationNotAllowedException;
import com.pulseguard.notification.security.Redactor;
import com.pulseguard.service.model.MonitoredService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlackNotificationChannelTest {

    private OutboundHttpClient mockHttpClient;
    private NotificationProperties properties;
    private ObjectMapper objectMapper;
    private SlackNotificationChannel channel;

    @BeforeEach
    void setUp() {
        mockHttpClient = mock(OutboundHttpClient.class);
        properties = new NotificationProperties();
        properties.getSlack().setEnabled(true);
        properties.getSlack().setWebhookUrl("https://hooks.slack.com/services/T000/B000/XXXX");

        objectMapper = new ObjectMapper();
        Redactor redactor = new Redactor(List.of(properties.getSlack().getWebhookUrl()));

        channel = new SlackNotificationChannel(properties, mockHttpClient, redactor, objectMapper);
    }

    @Test
    @DisplayName("requireSlackHost rejects non-Slack hosts when allowUnsafe is false")
    void requireSlackHostRejectsForeignHosts() throws Exception {
        URI foreign = URI.create("https://attacker.example.com/services/T00/B00/X");
        assertThatThrownBy(() -> SlackNotificationChannel.requireSlackHost(foreign, false))
                .isInstanceOf(DestinationNotAllowedException.class)
                .hasMessageContaining("hooks.slack.com");

        // Allowed when allowUnsafe is true
        SlackNotificationChannel.requireSlackHost(foreign, true);

        // Allowed for genuine Slack webhook domain
        SlackNotificationChannel.requireSlackHost(URI.create("https://hooks.slack.com/services/T00/B00/X"), false);
        SlackNotificationChannel.requireSlackHost(URI.create("https://hooks.slack-gov.com/services/T00/B00/X"), false);
    }

    @Test
    @DisplayName("deliver escapes mrkdwn special characters and renders payload")
    @SuppressWarnings("unchecked")
    void deliverEscapesMrkdwnAndPosts() throws Exception {
        when(mockHttpClient.post(any(), any(), any())).thenReturn(200);

        MonitoredService service = new MonitoredService("Catalog <Alpha & Beta>", "desc", "https://cat.com", 30, 3000);
        Incident incident = new Incident(service, IncidentType.SERVICE_UNAVAILABLE, IncidentSeverity.CRITICAL, "Failure > 50% & high latency", "Details: <foo>&<bar>", Instant.now());
        NotificationPayload payload = NotificationPayload.of(UUID.randomUUID(), NotificationEventType.INCIDENT_OPENED, Instant.now(), incident);

        DeliveryResult result = channel.deliver(new NotificationMessage(UUID.randomUUID(), 1, payload));

        assertThat(result.kind()).isEqualTo(DeliveryResult.Kind.SUCCESS);

        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(mockHttpClient).post(any(), any(), bodyCaptor.capture());

        JsonNode json = objectMapper.readTree(bodyCaptor.getValue());
        String text = json.get("text").asText();

        // Must escape &, <, >
        assertThat(text).contains("Catalog &lt;Alpha &amp; Beta&gt;");
        assertThat(text).contains("Failure &gt; 50% &amp; high latency");
        assertThat(text).contains("&lt;foo&gt;&amp;&lt;bar&gt;");
        assertThat(text).contains(":rotating_light:");
        assertThat(text).contains("Incident opened");
    }

    @Test
    @DisplayName("renderText maps event types to appropriate Slack icons and titles")
    void renderTextMapsEventTypes() {
        MonitoredService service = new MonitoredService("Auth", "desc", "https://a.com", 30, 3000);
        Incident incident = new Incident(service, IncidentType.SERVICE_UNAVAILABLE, IncidentSeverity.CRITICAL, "Down", "Err", Instant.now());

        NotificationPayload opened = NotificationPayload.of(UUID.randomUUID(), NotificationEventType.INCIDENT_OPENED, Instant.now(), incident);
        assertThat(SlackNotificationChannel.renderText(opened)).contains(":rotating_light:").contains("Incident opened");

        NotificationPayload occ = NotificationPayload.of(UUID.randomUUID(), NotificationEventType.INCIDENT_OCCURRENCE, Instant.now(), incident);
        assertThat(SlackNotificationChannel.renderText(occ)).contains(":warning:").contains("Incident ongoing");

        incident.resolve(Instant.now(), "Fixed");
        NotificationPayload resolved = NotificationPayload.of(UUID.randomUUID(), NotificationEventType.INCIDENT_RESOLVED, Instant.now(), incident);
        assertThat(SlackNotificationChannel.renderText(resolved)).contains(":white_check_mark:").contains("Incident resolved");
    }
}
