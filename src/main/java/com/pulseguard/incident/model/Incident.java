package com.pulseguard.incident.model;

import com.pulseguard.service.model.MonitoredService;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "incidents")
@EntityListeners(AuditingEntityListener.class)
public class Incident {

    private static final int MAX_SUMMARY_LENGTH = 255;
    private static final int MAX_DETAILS_LENGTH = 2000;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private MonitoredService service;

    @Enumerated(EnumType.STRING)
    @Column(name = "incident_type", nullable = false, length = 50)
    private IncidentType incidentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 30)
    private IncidentSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private IncidentStatus status;

    @Column(name = "summary", nullable = false, length = MAX_SUMMARY_LENGTH)
    private String summary;

    @Column(name = "details", length = MAX_DETAILS_LENGTH)
    private String details;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "last_occurrence_at", nullable = false)
    private Instant lastOccurrenceAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "occurrence_count", nullable = false)
    private long occurrenceCount;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Incident() {
        // Required by JPA
    }

    public Incident(
            MonitoredService service,
            IncidentType incidentType,
            IncidentSeverity severity,
            String summary,
            String details,
            Instant startedAt
    ) {
        this.id = UUID.randomUUID();
        this.service = Objects.requireNonNull(service, "Service must not be null");
        this.incidentType = Objects.requireNonNull(incidentType, "IncidentType must not be null");
        this.severity = Objects.requireNonNull(severity, "IncidentSeverity must not be null");
        this.status = IncidentStatus.OPEN;
        this.summary = truncate(Objects.requireNonNull(summary, "Summary must not be null").trim(), MAX_SUMMARY_LENGTH);
        this.details = truncate(details, MAX_DETAILS_LENGTH);
        this.startedAt = startedAt != null ? startedAt : Instant.now();
        this.lastOccurrenceAt = this.startedAt;
        this.resolvedAt = null;
        this.occurrenceCount = 1;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void recordOccurrence(Instant occurrenceTime, String newDetails) {
        this.occurrenceCount++;
        this.lastOccurrenceAt = occurrenceTime != null ? occurrenceTime : Instant.now();
        if (newDetails != null) {
            this.details = truncate(newDetails, MAX_DETAILS_LENGTH);
        }
        this.updatedAt = Instant.now();
    }

    public void resolve(Instant resolutionTime, String resolutionDetails) {
        this.status = IncidentStatus.RESOLVED;
        this.resolvedAt = resolutionTime != null ? resolutionTime : Instant.now();
        if (resolutionDetails != null) {
            this.details = truncate(resolutionDetails, MAX_DETAILS_LENGTH);
        }
        this.updatedAt = Instant.now();
    }

    public boolean isOpen() {
        return this.status == IncidentStatus.OPEN;
    }

    public boolean isResolved() {
        return this.status == IncidentStatus.RESOLVED;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength - 3) + "...";
    }

    public UUID getId() {
        return id;
    }

    public MonitoredService getService() {
        return service;
    }

    public IncidentType getIncidentType() {
        return incidentType;
    }

    public IncidentSeverity getSeverity() {
        return severity;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public String getSummary() {
        return summary;
    }

    public String getDetails() {
        return details;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getLastOccurrenceAt() {
        return lastOccurrenceAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public long getOccurrenceCount() {
        return occurrenceCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Incident incident = (Incident) o;
        return Objects.equals(id, incident.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Incident{" +
                "id=" + id +
                ", serviceId=" + (service != null ? service.getId() : null) +
                ", incidentType=" + incidentType +
                ", severity=" + severity +
                ", status=" + status +
                ", summary='" + summary + '\'' +
                ", startedAt=" + startedAt +
                ", occurrenceCount=" + occurrenceCount +
                '}';
    }
}
