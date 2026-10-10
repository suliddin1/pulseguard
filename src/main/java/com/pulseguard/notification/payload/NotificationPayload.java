package com.pulseguard.notification.payload;

import com.pulseguard.incident.model.Incident;
import com.pulseguard.incident.model.IncidentSeverity;
import com.pulseguard.incident.model.IncidentStatus;
import com.pulseguard.incident.model.IncidentType;
import com.pulseguard.notification.model.NotificationEventType;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable snapshot of an incident lifecycle event, serialized into the outbox row at the moment the
 * incident changes. Delivering from a snapshot (rather than re-reading the incident) means every retry sends
 * the state as of the event, and the row stays meaningful even if the incident is later deleted.
 */
public record NotificationPayload(
        int schemaVersion,
        UUID eventId,
        NotificationEventType eventType,
        Instant occurredAt,
        ServiceInfo service,
        IncidentInfo incident
) {

    public static final int SCHEMA_VERSION = 1;

    public record ServiceInfo(UUID id, String name) {
    }

    public record IncidentInfo(
            UUID id,
            IncidentType type,
            IncidentSeverity severity,
            IncidentStatus status,
            String summary,
            String details,
            Instant startedAt,
            Instant lastOccurrenceAt,
            Instant resolvedAt,
            long occurrenceCount
    ) {
    }

    public static NotificationPayload of(UUID eventId, NotificationEventType eventType, Instant occurredAt, Incident incident) {
        return of(eventId, eventType, occurredAt, incident, incident.getDetails());
    }

    /** Variant allowing the (potentially long) details text to be replaced by a shortened one. */
    public static NotificationPayload of(
            UUID eventId,
            NotificationEventType eventType,
            Instant occurredAt,
            Incident incident,
            String details
    ) {
        return new NotificationPayload(
                SCHEMA_VERSION,
                eventId,
                eventType,
                occurredAt,
                new ServiceInfo(incident.getService().getId(), incident.getService().getName()),
                new IncidentInfo(
                        incident.getId(),
                        incident.getIncidentType(),
                        incident.getSeverity(),
                        incident.getStatus(),
                        incident.getSummary(),
                        details,
                        incident.getStartedAt(),
                        incident.getLastOccurrenceAt(),
                        incident.getResolvedAt(),
                        incident.getOccurrenceCount()
                )
        );
    }
}
