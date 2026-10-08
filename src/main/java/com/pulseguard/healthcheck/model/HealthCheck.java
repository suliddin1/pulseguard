package com.pulseguard.healthcheck.model;

import com.pulseguard.service.model.MonitoredService;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "health_checks")
public class HealthCheck {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private MonitoredService service;

    @Column(name = "checked_at", nullable = false, updatable = false)
    private Instant checkedAt;

    @Column(name = "response_time_ms", nullable = false)
    private long responseTimeMs;

    @Column(name = "http_status_code")
    private Integer httpStatusCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 30)
    private HealthCheckResult result;

    @Column(name = "error_message", length = MAX_ERROR_MESSAGE_LENGTH)
    private String errorMessage;

    protected HealthCheck() {
        // Required by JPA
    }

    public HealthCheck(
            MonitoredService service,
            long responseTimeMs,
            Integer httpStatusCode,
            HealthCheckResult result,
            String errorMessage
    ) {
        this.id = UUID.randomUUID();
        this.service = Objects.requireNonNull(service, "Service must not be null");
        this.checkedAt = Instant.now();
        this.responseTimeMs = Math.max(0, responseTimeMs);
        this.httpStatusCode = httpStatusCode;
        this.result = Objects.requireNonNull(result, "Result must not be null");
        this.errorMessage = truncateErrorMessage(errorMessage);
    }

    private String truncateErrorMessage(String message) {
        if (message == null) {
            return null;
        }
        String trimmed = message.trim();
        if (trimmed.length() <= MAX_ERROR_MESSAGE_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_ERROR_MESSAGE_LENGTH - 3) + "...";
    }

    public UUID getId() {
        return id;
    }

    public MonitoredService getService() {
        return service;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }

    public long getResponseTimeMs() {
        return responseTimeMs;
    }

    public Integer getHttpStatusCode() {
        return httpStatusCode;
    }

    public HealthCheckResult getResult() {
        return result;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HealthCheck that = (HealthCheck) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "HealthCheck{" +
                "id=" + id +
                ", serviceId=" + (service != null ? service.getId() : null) +
                ", checkedAt=" + checkedAt +
                ", responseTimeMs=" + responseTimeMs +
                ", httpStatusCode=" + httpStatusCode +
                ", result=" + result +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }
}
