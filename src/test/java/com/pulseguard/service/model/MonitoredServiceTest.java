package com.pulseguard.service.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonitoredServiceTest {

    @Test
    @DisplayName("Should initialize service with valid defaults and unique ID")
    void shouldInitializeWithDefaults() {
        MonitoredService service = new MonitoredService(
                "Auth Service",
                "Authentication and authorization microservice",
                "https://auth.internal.example.com/health",
                30,
                3000
        );

        assertThat(service.getId()).isNotNull();
        assertThat(service.getName()).isEqualTo("Auth Service");
        assertThat(service.getDescription()).isEqualTo("Authentication and authorization microservice");
        assertThat(service.getUrl()).isEqualTo("https://auth.internal.example.com/health");
        assertThat(service.getCheckIntervalSeconds()).isEqualTo(30);
        assertThat(service.getTimeoutMs()).isEqualTo(3000);
        assertThat(service.isEnabled()).isTrue();
        assertThat(service.getStatus()).isEqualTo(ServiceStatus.UNKNOWN);
        assertThat(service.getCreatedAt()).isNotNull();
        assertThat(service.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should fall back to default interval and timeout when values are zero or negative")
    void shouldFallBackToDefaultsWhenNonPositive() {
        MonitoredService service = new MonitoredService(
                "Payment Service",
                null,
                "https://payment.internal.example.com/health",
                0,
                -10
        );

        assertThat(service.getCheckIntervalSeconds()).isEqualTo(60);
        assertThat(service.getTimeoutMs()).isEqualTo(5000);
    }

    @Test
    @DisplayName("Should update configuration and refresh updated timestamp")
    void shouldUpdateConfiguration() {
        MonitoredService service = new MonitoredService(
                "Old Name",
                "Old Desc",
                "https://old.example.com/health",
                60,
                5000
        );

        service.updateConfiguration(
                "New Name",
                "New Desc",
                "https://new.example.com/health",
                15,
                2500
        );

        assertThat(service.getName()).isEqualTo("New Name");
        assertThat(service.getDescription()).isEqualTo("New Desc");
        assertThat(service.getUrl()).isEqualTo("https://new.example.com/health");
        assertThat(service.getCheckIntervalSeconds()).isEqualTo(15);
        assertThat(service.getTimeoutMs()).isEqualTo(2500);
    }

    @Test
    @DisplayName("Should enable and disable service correctly")
    void shouldToggleEnabledState() {
        MonitoredService service = new MonitoredService(
                "Search API",
                null,
                "https://search.example.com/health",
                60,
                5000
        );

        assertThat(service.isEnabled()).isTrue();

        service.disable();
        assertThat(service.isEnabled()).isFalse();

        service.enable();
        assertThat(service.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("Should update service health status")
    void shouldUpdateHealthStatus() {
        MonitoredService service = new MonitoredService(
                "Billing Service",
                null,
                "https://billing.example.com/health",
                60,
                5000
        );

        service.updateStatus(ServiceStatus.HEALTHY);
        assertThat(service.getStatus()).isEqualTo(ServiceStatus.HEALTHY);

        service.updateStatus(ServiceStatus.UNHEALTHY);
        assertThat(service.getStatus()).isEqualTo(ServiceStatus.UNHEALTHY);
    }

    @Test
    @DisplayName("Should reject null name or null URL")
    void shouldRejectNullMandatoryFields() {
        assertThatThrownBy(() -> new MonitoredService(null, null, "https://api.example.com", 60, 5000))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Name must not be null");

        assertThatThrownBy(() -> new MonitoredService("Valid Name", null, null, 60, 5000))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("URL must not be null");
    }

    @Test
    @DisplayName("Should transition status to HEALTHY when check succeeds, and UNHEALTHY when it fails or times out")
    void shouldTransitionStatusOnHealthCheckOutcome() {
        MonitoredService service = new MonitoredService(
                "API Gateway",
                null,
                "https://gw.example.com/health",
                60,
                5000
        );

        assertThat(service.getStatus()).isEqualTo(ServiceStatus.UNKNOWN);

        service.recordHealthCheckOutcome(com.pulseguard.healthcheck.model.HealthCheckResult.SUCCESS);
        assertThat(service.getStatus()).isEqualTo(ServiceStatus.HEALTHY);

        service.recordHealthCheckOutcome(com.pulseguard.healthcheck.model.HealthCheckResult.FAILURE);
        assertThat(service.getStatus()).isEqualTo(ServiceStatus.UNHEALTHY);

        service.recordHealthCheckOutcome(com.pulseguard.healthcheck.model.HealthCheckResult.TIMEOUT);
        assertThat(service.getStatus()).isEqualTo(ServiceStatus.UNHEALTHY);
    }
}
