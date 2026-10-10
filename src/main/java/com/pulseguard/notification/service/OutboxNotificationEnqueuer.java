package com.pulseguard.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseguard.incident.model.Incident;
import com.pulseguard.notification.channel.NotificationChannelRegistry;
import com.pulseguard.notification.config.NotificationProperties;
import com.pulseguard.notification.metrics.NotificationMetrics;
import com.pulseguard.notification.model.NotificationChannelType;
import com.pulseguard.notification.model.NotificationEventType;
import com.pulseguard.notification.model.NotificationOutbox;
import com.pulseguard.notification.payload.NotificationPayload;
import com.pulseguard.notification.repository.NotificationOutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Writes outbox rows (one per enabled channel). {@link Propagation#MANDATORY} makes it impossible to call
 * this outside the incident transaction, which is the whole point of the outbox pattern.
 *
 * <p>Event ids are deterministic ({@code incident id + event kind [+ occurrence count]}), so replaying the
 * same logical event can never create a second row: {@code UNIQUE(event_id, channel)} backs this up.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class OutboxNotificationEnqueuer implements NotificationEnqueuer {

    /** Serialized payload must fit the VARCHAR(4000) column; leave headroom. */
    private static final int MAX_PAYLOAD_CHARS = 3800;
    private static final int[] DETAIL_LIMITS = {1000, 300, 0};

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationChannelRegistry channelRegistry;
    private final NotificationProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final NotificationMetrics metrics;

    public OutboxNotificationEnqueuer(
            NotificationOutboxRepository outboxRepository,
            NotificationChannelRegistry channelRegistry,
            NotificationProperties properties,
            ObjectMapper objectMapper,
            Clock clock,
            NotificationMetrics metrics
    ) {
        this.outboxRepository = outboxRepository;
        this.channelRegistry = channelRegistry;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Override
    public void incidentOpened(Incident incident) {
        enqueue(incident, NotificationEventType.INCIDENT_OPENED, "incident:" + incident.getId() + ":OPENED");
    }

    @Override
    public void incidentOccurrence(Incident incident) {
        NotificationProperties.Occurrence policy = properties.getOccurrence();
        long count = incident.getOccurrenceCount();
        if (policy.getNotifyEvery() <= 0 || count % policy.getNotifyEvery() != 0) {
            return;
        }
        Instant now = clock.instant();
        if (outboxRepository.existsByIncidentIdAndEventTypeAndCreatedAtAfter(
                incident.getId(), NotificationEventType.INCIDENT_OCCURRENCE, now.minus(policy.getMinInterval()))) {
            return;
        }
        enqueue(incident, NotificationEventType.INCIDENT_OCCURRENCE,
                "incident:" + incident.getId() + ":OCCURRENCE:" + count);
    }

    @Override
    public void incidentResolved(Incident incident) {
        enqueue(incident, NotificationEventType.INCIDENT_RESOLVED, "incident:" + incident.getId() + ":RESOLVED");
    }

    private void enqueue(Incident incident, NotificationEventType eventType, String eventKey) {
        List<NotificationChannelType> channels = channelRegistry.enabledTypes();
        if (channels.isEmpty()) {
            return;
        }
        try {
            UUID eventId = UUID.nameUUIDFromBytes(eventKey.getBytes(StandardCharsets.UTF_8));
            Instant now = clock.instant();
            String payload = serialize(eventId, eventType, now, incident);
            for (NotificationChannelType channel : channels) {
                if (outboxRepository.existsByEventIdAndChannel(eventId, channel)) {
                    continue;
                }
                outboxRepository.save(new NotificationOutbox(
                        eventId, channel, eventType, incident.getId(), incident.getService().getId(), payload, now));
                metrics.queued(channel, eventType);
            }
        } catch (RuntimeException | JsonProcessingException ex) {
            metrics.enqueueFailure();
            throw new NotificationEnqueueException(
                    "Failed to write notification outbox event " + eventType + " for incident " + incident.getId(), ex);
        }
    }

    /** Serializes the snapshot, shortening free-text details if needed so it always fits the column. */
    private String serialize(UUID eventId, NotificationEventType eventType, Instant now, Incident incident)
            throws JsonProcessingException {
        String details = incident.getDetails();
        String json = objectMapper.writeValueAsString(NotificationPayload.of(eventId, eventType, now, incident, details));
        for (int limit : DETAIL_LIMITS) {
            if (json.length() <= MAX_PAYLOAD_CHARS) {
                return json;
            }
            String shortened = details == null || limit == 0 ? null
                    : details.substring(0, Math.min(details.length(), limit));
            json = objectMapper.writeValueAsString(NotificationPayload.of(eventId, eventType, now, incident, shortened));
        }
        if (json.length() > MAX_PAYLOAD_CHARS) {
            throw new JsonProcessingException("notification payload exceeds the maximum size") {
            };
        }
        return json;
    }
}
