package com.pulseguard.healthcheck.model;

import com.pulseguard.service.model.MonitoredService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HealthCheckTest {

    @Test
    @DisplayName("Should create HealthCheck with valid attributes")
    void shouldCreateHealthCheck() {
        MonitoredService service = new MonitoredService(
                "Billing Service",
                null,
                "https://billing.example.com",
                60,
                5000
        );

        HealthCheck check = new HealthCheck(
                service,
                142L,
                200,
                HealthCheckResult.SUCCESS,
                null
        );

        assertThat(check.getId()).isNotNull();
        assertThat(check.getService()).isEqualTo(service);
        assertThat(check.getResponseTimeMs()).isEqualTo(142L);
        assertThat(check.getHttpStatusCode()).isEqualTo(200);
        assertThat(check.getResult()).isEqualTo(HealthCheckResult.SUCCESS);
        assertThat(check.getErrorMessage()).isNull();
        assertThat(check.getCheckedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should clamp negative response time to zero")
    void shouldClampNegativeResponseTime() {
        MonitoredService service = new MonitoredService(
                "Order Service",
                null,
                "https://order.example.com",
                60,
                5000
        );

        HealthCheck check = new HealthCheck(
                service,
                -50L,
                500,
                HealthCheckResult.FAILURE,
                "Internal Server Error"
        );

        assertThat(check.getResponseTimeMs()).isEqualTo(0L);
        assertThat(check.getResult()).isEqualTo(HealthCheckResult.FAILURE);
        assertThat(check.getHttpStatusCode()).isEqualTo(500);
    }

    @Test
    @DisplayName("Should truncate overly long error messages to 1000 characters")
    void shouldTruncateLongErrorMessage() {
        MonitoredService service = new MonitoredService(
                "Auth Service",
                null,
                "https://auth.example.com",
                60,
                5000
        );

        String longError = "E".repeat(1500);
        HealthCheck check = new HealthCheck(
                service,
                3000L,
                null,
                HealthCheckResult.TIMEOUT,
                longError
        );

        assertThat(check.getErrorMessage()).hasSize(1000);
        assertThat(check.getErrorMessage()).endsWith("...");
    }

    @Test
    @DisplayName("Should reject null service or result")
    void shouldRejectNullMandatoryFields() {
        MonitoredService service = new MonitoredService(
                "User Service",
                null,
                "https://user.example.com",
                60,
                5000
        );

        assertThatThrownBy(() -> new HealthCheck(null, 100L, 200, HealthCheckResult.SUCCESS, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Service must not be null");

        assertThatThrownBy(() -> new HealthCheck(service, 100L, 200, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Result must not be null");
    }
}
