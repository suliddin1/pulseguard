package com.pulseguard.notification.dispatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseguard.notification.channel.DeliveryResult;
import com.pulseguard.notification.channel.NotificationChannel;
import com.pulseguard.notification.channel.NotificationChannelRegistry;
import com.pulseguard.notification.config.NotificationProperties;
import com.pulseguard.notification.metrics.NotificationMetrics;
import com.pulseguard.notification.model.AttemptOutcome;
import com.pulseguard.notification.model.NotificationAttempt;
import com.pulseguard.notification.model.NotificationChannelType;
import com.pulseguard.notification.model.NotificationEventType;
import com.pulseguard.notification.model.NotificationOutbox;
import com.pulseguard.notification.model.OutboxStatus;
import com.pulseguard.notification.repository.NotificationAttemptRepository;
import com.pulseguard.notification.repository.NotificationOutboxRepository;
import com.pulseguard.notification.service.NotificationOutboxService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationDispatcherTest {

    private NotificationOutboxService outboxService;
    private NotificationOutboxRepository outboxRepository;
    private NotificationAttemptRepository attemptRepository;
    private NotificationChannel mockChannel;
    private NotificationChannelRegistry registry;
    private NotificationProperties properties;
    private MeterRegistry meterRegistry;
    private NotificationMetrics metrics;
    private ObjectMapper objectMapper;
    private Clock fixedClock;
    private NotificationDispatcher dispatcher;

    private UUID incidentId;
    private UUID serviceId;

    @BeforeEach
    void setUp() {
        outboxRepository = mock(NotificationOutboxRepository.class);
        attemptRepository = mock(NotificationAttemptRepository.class);
        properties = new NotificationProperties();
        properties.getRetry().setMaxAttempts(3);
        properties.getRetry().setInitialBackoff(Duration.ofSeconds(2));
        properties.getRetry().setMaxBackoff(Duration.ofSeconds(10));
        properties.getRetry().setJitter(0.0); // deterministic
        properties.getRateLimit().setPerMinute(0); // disabled for test

        meterRegistry = new SimpleMeterRegistry();
        metrics = new NotificationMetrics(meterRegistry);

        outboxService = new NotificationOutboxService(outboxRepository, attemptRepository, properties, metrics);

        mockChannel = mock(NotificationChannel.class);
        when(mockChannel.type()).thenReturn(NotificationChannelType.WEBHOOK);
        when(mockChannel.isEnabled()).thenReturn(true);
        registry = new NotificationChannelRegistry(List.of(mockChannel));

        objectMapper = new ObjectMapper();
        fixedClock = Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneId.of("UTC"));

        BackoffPolicy backoffPolicy = new BackoffPolicy(
                properties.getRetry().getInitialBackoff(),
                properties.getRetry().getMaxBackoff(),
                properties.getRetry().getJitter(),
                () -> 0.5
        );
        ChannelRateLimiter rateLimiter = new ChannelRateLimiter(fixedClock, 0);

        dispatcher = new NotificationDispatcher(
                outboxService,
                registry,
                rateLimiter,
                backoffPolicy,
                properties,
                metrics,
                objectMapper,
                new SyncTaskExecutor(),
                fixedClock
        );

        incidentId = UUID.randomUUID();
        serviceId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Successful delivery marks outbox record DELIVERED and records attempt")
    void successfulDeliveryMarksDelivered() {
        String payload = "{\"schemaVersion\":1}";
        NotificationOutbox row = new NotificationOutbox(UUID.randomUUID(), NotificationChannelType.WEBHOOK, NotificationEventType.INCIDENT_OPENED, incidentId, serviceId, payload, fixedClock.instant());
        UUID id = row.getId();

        when(outboxRepository.findClaimableIds(any(), any(), any(), any())).thenReturn(List.of(id));
        when(outboxRepository.claim(eq(id), any(), any(), any(), any(), any())).thenReturn(1);
        when(outboxRepository.findById(id)).thenReturn(java.util.Optional.of(row));
        when(outboxRepository.completeDelivered(eq(id), any(), any(), eq(200), eq(OutboxStatus.DELIVERED), any())).thenReturn(1);
        when(mockChannel.deliver(any())).thenReturn(DeliveryResult.success(200));

        int submitted = dispatcher.dispatchBatch();

        assertThat(submitted).isEqualTo(1);
        org.mockito.Mockito.verify(attemptRepository).save(any(NotificationAttempt.class));
        org.mockito.Mockito.verify(outboxRepository).completeDelivered(eq(id), any(), any(), eq(200), eq(OutboxStatus.DELIVERED), any());
        assertThat(meterRegistry.counter("pulseguard.notifications.delivered", "channel", "WEBHOOK").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Retryable failure schedules retry with backoff")
    void retryableFailureSchedulesRetry() {
        NotificationOutbox row = new NotificationOutbox(UUID.randomUUID(), NotificationChannelType.WEBHOOK, NotificationEventType.INCIDENT_OPENED, incidentId, serviceId, "{}", fixedClock.instant());
        UUID id = row.getId();

        when(outboxRepository.findClaimableIds(any(), any(), any(), any())).thenReturn(List.of(id));
        when(outboxRepository.claim(eq(id), any(), any(), any(), any(), any())).thenReturn(1);
        when(outboxRepository.findById(id)).thenReturn(java.util.Optional.of(row));
        when(outboxRepository.completeRetry(eq(id), any(), any(), any(), any(), eq(500), eq(OutboxStatus.PENDING), any())).thenReturn(1);
        when(mockChannel.deliver(any())).thenReturn(DeliveryResult.retryable(500, "http_500"));

        dispatcher.dispatchBatch();

        org.mockito.Mockito.verify(outboxRepository).completeRetry(eq(id), any(), any(), any(), any(), eq(500), eq(OutboxStatus.PENDING), any());
        assertThat(meterRegistry.counter("pulseguard.notifications.retries", "channel", "WEBHOOK").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Permanent failure marks outbox record DEAD immediately")
    void permanentFailureMarksDead() {
        NotificationOutbox row = new NotificationOutbox(UUID.randomUUID(), NotificationChannelType.WEBHOOK, NotificationEventType.INCIDENT_OPENED, incidentId, serviceId, "{}", fixedClock.instant());
        UUID id = row.getId();

        when(outboxRepository.findClaimableIds(any(), any(), any(), any())).thenReturn(List.of(id));
        when(outboxRepository.claim(eq(id), any(), any(), any(), any(), any())).thenReturn(1);
        when(outboxRepository.findById(id)).thenReturn(java.util.Optional.of(row));
        when(outboxRepository.completeDead(eq(id), any(), any(), any(), eq(400), eq(OutboxStatus.DEAD), any())).thenReturn(1);
        when(mockChannel.deliver(any())).thenReturn(DeliveryResult.permanent(400, "http_400"));

        dispatcher.dispatchBatch();

        org.mockito.Mockito.verify(outboxRepository).completeDead(eq(id), any(), any(), any(), eq(400), eq(OutboxStatus.DEAD), any());
        assertThat(meterRegistry.counter("pulseguard.notifications.dead", "channel", "WEBHOOK", "reason", "permanent_failure").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Retries exhausted marks outbox record DEAD")
    void retriesExhaustedMarksDead() {
        NotificationOutbox row = new NotificationOutbox(UUID.randomUUID(), NotificationChannelType.WEBHOOK, NotificationEventType.INCIDENT_OPENED, incidentId, serviceId, "{}", fixedClock.instant());
        UUID id = row.getId();

        // Max attempts is 3. Simulate this claim being the 3rd attempt.
        when(outboxRepository.findClaimableIds(any(), any(), any(), any())).thenReturn(List.of(id));
        when(outboxRepository.claim(eq(id), any(), any(), any(), any(), any())).thenReturn(1);

        NotificationOutbox exhaustedRow = org.mockito.Mockito.spy(row);
        when(exhaustedRow.getAttemptCount()).thenReturn(3);

        when(outboxRepository.findById(id)).thenReturn(java.util.Optional.of(exhaustedRow));
        when(outboxRepository.completeDead(eq(id), any(), any(), any(), eq(504), eq(OutboxStatus.DEAD), any())).thenReturn(1);
        when(mockChannel.deliver(any())).thenReturn(DeliveryResult.retryable(504, "gateway_timeout"));

        dispatcher.dispatchBatch();

        org.mockito.Mockito.verify(outboxRepository).completeDead(eq(id), any(), any(), any(), eq(504), eq(OutboxStatus.DEAD), any());
        assertThat(meterRegistry.counter("pulseguard.notifications.dead", "channel", "WEBHOOK", "reason", "retries_exhausted").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("stop() halts acceptance of new dispatch work")
    void stopHaltsDispatch() {
        dispatcher.stop();
        assertThat(dispatcher.isAccepting()).isFalse();

        int submitted = dispatcher.dispatchBatch();
        assertThat(submitted).isZero();
        org.mockito.Mockito.verifyNoInteractions(outboxRepository);
    }

    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
