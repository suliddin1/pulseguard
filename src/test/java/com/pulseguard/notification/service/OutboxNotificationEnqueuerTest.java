package com.pulseguard.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pulseguard.incident.model.Incident;
import com.pulseguard.incident.model.IncidentSeverity;
import com.pulseguard.incident.model.IncidentType;
import com.pulseguard.notification.channel.NotificationChannel;
import com.pulseguard.notification.channel.NotificationChannelRegistry;
import com.pulseguard.notification.config.NotificationProperties;
import com.pulseguard.notification.metrics.NotificationMetrics;
import com.pulseguard.notification.model.NotificationChannelType;
import com.pulseguard.notification.model.NotificationEventType;
import com.pulseguard.notification.model.NotificationOutbox;
import com.pulseguard.notification.repository.NotificationOutboxRepository;
import com.pulseguard.service.model.MonitoredService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxNotificationEnqueuerTest {

    private NotificationOutboxRepository outboxRepository;
    private NotificationChannelRegistry channelRegistry;
    private NotificationProperties properties;
    private ObjectMapper objectMapper;
    private Clock fixedClock;
    private NotificationMetrics metrics;
    private OutboxNotificationEnqueuer enqueuer;

    private MonitoredService service;
    private Incident incident;

    @BeforeEach
    void setUp() {
        outboxRepository = mock(NotificationOutboxRepository.class);
        properties = new NotificationProperties();
        properties.getWebhook().setEnabled(true);
        properties.getOccurrence().setNotifyEvery(10);
        properties.getOccurrence().setMinInterval(Duration.ofMinutes(10));

        NotificationChannel webhookChannel = mock(NotificationChannel.class);
        when(webhookChannel.type()).thenReturn(NotificationChannelType.WEBHOOK);
        when(webhookChannel.isEnabled()).thenReturn(true);

        NotificationChannel slackChannel = mock(NotificationChannel.class);
        when(slackChannel.type()).thenReturn(NotificationChannelType.SLACK);
        when(slackChannel.isEnabled()).thenReturn(false);

        channelRegistry = new NotificationChannelRegistry(List.of(webhookChannel, slackChannel));

        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        fixedClock = Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneId.of("UTC"));
        metrics = new NotificationMetrics(new SimpleMeterRegistry());

        enqueuer = new OutboxNotificationEnqueuer(
                outboxRepository,
                channelRegistry,
                properties,
                objectMapper,
                fixedClock,
                metrics
        );

        service = new MonitoredService("Payments API", "desc", "https://payments.example.com", 30, 3000);
        incident = new Incident(service, IncidentType.SERVICE_UNAVAILABLE, IncidentSeverity.CRITICAL, "Payments down", "503 Service Unavailable", Instant.now());
    }

    @Test
    @DisplayName("incidentOpened saves outbox row with deterministic eventId for enabled channels")
    void incidentOpenedSavesOutboxRow() {
        ArgumentCaptor<NotificationOutbox> captor = ArgumentCaptor.forClass(NotificationOutbox.class);

        enqueuer.incidentOpened(incident);

        verify(outboxRepository).save(captor.capture());
        NotificationOutbox saved = captor.getValue();

        assertThat(saved.getChannel()).isEqualTo(NotificationChannelType.WEBHOOK);
        assertThat(saved.getEventType()).isEqualTo(NotificationEventType.INCIDENT_OPENED);
        assertThat(saved.getIncidentId()).isEqualTo(incident.getId());
        assertThat(saved.getServiceId()).isEqualTo(service.getId());
        assertThat(saved.getPayload()).contains("Payments down");
        assertThat(saved.getAttemptCount()).isZero();
    }

    @Test
    @DisplayName("incidentOccurrence only enqueues if count is a multiple of notifyEvery and interval has passed")
    void incidentOccurrenceRespectsPolicy() {
        // Occurrence count 5, notifyEvery is 10 -> should NOT enqueue
        for (int i = 1; i < 5; i++) {
            incident.recordOccurrence(Instant.now(), "Still failing");
        }
        assertThat(incident.getOccurrenceCount()).isEqualTo(5);
        enqueuer.incidentOccurrence(incident);
        verify(outboxRepository, never()).save(any());

        // Advance occurrence count to 10
        for (int i = 5; i < 10; i++) {
            incident.recordOccurrence(Instant.now(), "Still failing");
        }
        assertThat(incident.getOccurrenceCount()).isEqualTo(10);

        // When outboxRepository reports no recent occurrence within 10 min
        when(outboxRepository.existsByIncidentIdAndEventTypeAndCreatedAtAfter(eq(incident.getId()), eq(NotificationEventType.INCIDENT_OCCURRENCE), any()))
                .thenReturn(false);

        enqueuer.incidentOccurrence(incident);
        verify(outboxRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("incidentOccurrence suppresses notification if recent occurrence exists within minInterval")
    void incidentOccurrenceSuppressesWhenWithinMinInterval() {
        for (int i = 1; i < 10; i++) {
            incident.recordOccurrence(Instant.now(), "Failing");
        }
        assertThat(incident.getOccurrenceCount()).isEqualTo(10);

        // Simulate that an occurrence notification was already recorded 2 minutes ago
        when(outboxRepository.existsByIncidentIdAndEventTypeAndCreatedAtAfter(eq(incident.getId()), eq(NotificationEventType.INCIDENT_OCCURRENCE), any()))
                .thenReturn(true);

        enqueuer.incidentOccurrence(incident);
        verify(outboxRepository, never()).save(any());
    }

    @Test
    @DisplayName("incidentResolved saves resolved event row")
    void incidentResolvedSavesRow() {
        incident.resolve(Instant.now(), "Fixed");
        enqueuer.incidentResolved(incident);

        ArgumentCaptor<NotificationOutbox> captor = ArgumentCaptor.forClass(NotificationOutbox.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(NotificationEventType.INCIDENT_RESOLVED);
    }
}
