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

/** Append-only record of one delivery attempt. */
@Entity
@Table(name = "notification_delivery_attempts")
public class NotificationAttempt implements Persistable<UUID> {

    private static final int MAX_ERROR_LENGTH = 1000;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "outbox_id", nullable = false, updatable = false)
    private UUID outboxId;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Column(name = "attempted_at", nullable = false, updatable = false)
    private Instant attemptedAt;

    @Column(name = "duration_ms", nullable = false, updatable = false)
    private long durationMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, updatable = false, length = 30)
    private AttemptOutcome outcome;

    @Column(name = "http_status", updatable = false)
    private Integer httpStatus;

    @Column(name = "error_message", updatable = false, length = MAX_ERROR_LENGTH)
    private String errorMessage;

    @Transient
    private boolean newEntity = true;

    protected NotificationAttempt() {
        // Required by JPA
    }

    public NotificationAttempt(
            UUID outboxId,
            int attemptNumber,
            Instant attemptedAt,
            long durationMs,
            AttemptOutcome outcome,
            Integer httpStatus,
            String errorMessage
    ) {
        this.id = UUID.randomUUID();
        this.outboxId = Objects.requireNonNull(outboxId, "outboxId");
        this.attemptNumber = attemptNumber;
        this.attemptedAt = Objects.requireNonNull(attemptedAt, "attemptedAt");
        this.durationMs = Math.max(0, durationMs);
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.httpStatus = httpStatus;
        this.errorMessage = truncate(errorMessage);
    }

    public static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH - 3) + "...";
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

    public UUID getOutboxId() {
        return outboxId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public AttemptOutcome getOutcome() {
        return outcome;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
