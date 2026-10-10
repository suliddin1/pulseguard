package com.pulseguard.notification.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One (event, channel) delivery in the transactional outbox.
 *
 * <p>State transitions are performed by fenced bulk updates in {@code NotificationOutboxRepository}
 * (compare-and-set on status / lease token) rather than by mutating managed entities, so that concurrent
 * dispatcher workers and application instances cannot overwrite each other's progress.
 */
@Entity
@Table(name = "notification_outbox")
public class NotificationOutbox implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false, length = 30)
    private NotificationChannelType channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 40)
    private NotificationEventType eventType;

    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    @Column(name = "service_id", nullable = false, updatable = false)
    private UUID serviceId;

    @Column(name = "payload", nullable = false, updatable = false, length = 4000)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "lease_token", length = 64)
    private String leaseToken;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "last_http_status")
    private Integer lastHttpStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Transient
    private boolean newEntity = true;

    protected NotificationOutbox() {
        // Required by JPA
    }

    public NotificationOutbox(
            UUID eventId,
            NotificationChannelType channel,
            NotificationEventType eventType,
            UUID incidentId,
            UUID serviceId,
            String payload,
            Instant now
    ) {
        this.id = UUID.randomUUID();
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.incidentId = Objects.requireNonNull(incidentId, "incidentId");
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.status = OutboxStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = Objects.requireNonNull(now, "now");
        this.createdAt = now;
        this.updatedAt = now;
    }

    @Override
    public UUID getId() {
        return id;
    }

    /** Application-assigned id: lets Spring Data issue a plain INSERT instead of SELECT-then-merge. */
    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }

    public UUID getEventId() {
        return eventId;
    }

    public NotificationChannelType getChannel() {
        return channel;
    }

    public NotificationEventType getEventType() {
        return eventType;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public UUID getServiceId() {
        return serviceId;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLeaseToken() {
        return leaseToken;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Integer getLastHttpStatus() {
        return lastHttpStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return Objects.equals(id, ((NotificationOutbox) o).id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        // Deliberately omits payload and lease token.
        return "NotificationOutbox{id=" + id + ", channel=" + channel + ", eventType=" + eventType
                + ", status=" + status + ", attemptCount=" + attemptCount + '}';
    }
}
