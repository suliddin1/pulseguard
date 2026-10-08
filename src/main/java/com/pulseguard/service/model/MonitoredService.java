package com.pulseguard.service.model;

import com.pulseguard.healthcheck.model.HealthCheckResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "services")
@EntityListeners(AuditingEntityListener.class)
public class MonitoredService {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 100, unique = true)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "url", nullable = false, length = 2048)
    private String url;

    @Column(name = "check_interval_seconds", nullable = false)
    private int checkIntervalSeconds;

    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ServiceStatus status;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MonitoredService() {
        // Required by JPA
    }

    public MonitoredService(
            String name,
            String description,
            String url,
            int checkIntervalSeconds,
            int timeoutMs
    ) {
        this.id = UUID.randomUUID();
        this.name = Objects.requireNonNull(name, "Name must not be null").trim();
        this.description = description != null ? description.trim() : null;
        this.url = Objects.requireNonNull(url, "URL must not be null").trim();
        this.checkIntervalSeconds = checkIntervalSeconds > 0 ? checkIntervalSeconds : 60;
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 5000;
        this.enabled = true;
        this.status = ServiceStatus.UNKNOWN;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void updateConfiguration(
            String name,
            String description,
            String url,
            int checkIntervalSeconds,
            int timeoutMs
    ) {
        this.name = Objects.requireNonNull(name, "Name must not be null").trim();
        this.description = description != null ? description.trim() : null;
        this.url = Objects.requireNonNull(url, "URL must not be null").trim();
        this.checkIntervalSeconds = checkIntervalSeconds;
        this.timeoutMs = timeoutMs;
        this.updatedAt = Instant.now();
    }

    public void enable() {
        this.enabled = true;
        this.updatedAt = Instant.now();
    }

    public void disable() {
        this.enabled = false;
        this.updatedAt = Instant.now();
    }

    public void updateStatus(ServiceStatus newStatus) {
        this.status = Objects.requireNonNull(newStatus, "Status must not be null");
        this.updatedAt = Instant.now();
    }

    /**
     * Updates service status based on the outcome of a health check execution.
     * Business transition rule:
     * - SUCCESS -> HEALTHY
     * - FAILURE or TIMEOUT -> UNHEALTHY
     *
     * @param result the outcome of the health check probe
     */
    public void recordHealthCheckOutcome(HealthCheckResult result) {
        Objects.requireNonNull(result, "HealthCheckResult must not be null");
        if (result == HealthCheckResult.SUCCESS) {
            this.status = ServiceStatus.HEALTHY;
        } else {
            this.status = ServiceStatus.UNHEALTHY;
        }
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getUrl() {
        return url;
    }

    public int getCheckIntervalSeconds() {
        return checkIntervalSeconds;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public ServiceStatus getStatus() {
        return status;
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
        MonitoredService that = (MonitoredService) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "MonitoredService{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", url='" + url + '\'' +
                ", checkIntervalSeconds=" + checkIntervalSeconds +
                ", timeoutMs=" + timeoutMs +
                ", enabled=" + enabled +
                ", status=" + status +
                '}';
    }
}
