package com.pulseguard.healthcheck.prober;

import com.pulseguard.healthcheck.model.HealthCheckResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class JavaHttpHealthProberTest {

    private static HttpServer mockServer;
    private static int port;
    private final HttpHealthProber prober = new JavaHttpHealthProber();

    @BeforeAll
    static void startServer() throws IOException {
        mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = mockServer.getAddress().getPort();

        mockServer.createContext("/health-ok", exchange -> {
            byte[] response = "OK".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        mockServer.createContext("/health-503", exchange -> {
            byte[] response = "Service Unavailable".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        mockServer.createContext("/health-slow", exchange -> {
            try {
                Thread.sleep(1200);
            } catch (InterruptedException ignored) {
            }
            byte[] response = "Delayed".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        mockServer.start();
    }

    @AfterAll
    static void stopServer() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    @Test
    @DisplayName("Should return SUCCESS and record latency for HTTP 200 response")
    void shouldReturnSuccessFor200() {
        String url = "http://127.0.0.1:" + port + "/health-ok";

        ProbeResult result = prober.probe(url, 3000);

        assertThat(result.result()).isEqualTo(HealthCheckResult.SUCCESS);
        assertThat(result.httpStatusCode()).isEqualTo(200);
        assertThat(result.responseTimeMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    @DisplayName("Should return FAILURE and record status code for HTTP 503 response")
    void shouldReturnFailureFor503() {
        String url = "http://127.0.0.1:" + port + "/health-503";

        ProbeResult result = prober.probe(url, 3000);

        assertThat(result.result()).isEqualTo(HealthCheckResult.FAILURE);
        assertThat(result.httpStatusCode()).isEqualTo(503);
        assertThat(result.errorMessage()).contains("503");
    }

    @Test
    @DisplayName("Should return TIMEOUT when response exceeds configured timeout threshold")
    void shouldReturnTimeoutWhenExceeded() {
        String url = "http://127.0.0.1:" + port + "/health-slow";

        // Probe with 500ms timeout while server delays 1200ms
        ProbeResult result = prober.probe(url, 500);

        assertThat(result.result()).isEqualTo(HealthCheckResult.TIMEOUT);
        assertThat(result.httpStatusCode()).isNull();
        assertThat(result.errorMessage()).containsIgnoringCase("timed out");
    }

    @Test
    @DisplayName("Should return FAILURE when connection is refused")
    void shouldReturnFailureWhenConnectionRefused() {
        // Port 1 is reserved and typically nothing listens on 127.0.0.1:1
        String unreachableUrl = "http://127.0.0.1:1/health";

        ProbeResult result = prober.probe(unreachableUrl, 1000);

        assertThat(result.result()).isEqualTo(HealthCheckResult.FAILURE);
        assertThat(result.httpStatusCode()).isNull();
        assertThat(result.errorMessage()).isNotNull();
    }

    @Test
    @DisplayName("Should return FAILURE when URL has invalid scheme")
    void shouldReturnFailureForInvalidScheme() {
        String invalidSchemeUrl = "ftp://127.0.0.1/health";

        ProbeResult result = prober.probe(invalidSchemeUrl, 1000);

        assertThat(result.result()).isEqualTo(HealthCheckResult.FAILURE);
        assertThat(result.errorMessage()).contains("Invalid URL scheme");
    }

    @Test
    @DisplayName("Should return FAILURE when URL is malformed")
    void shouldReturnFailureForMalformedUrl() {
        String malformedUrl = "not a valid url";

        ProbeResult result = prober.probe(malformedUrl, 1000);

        assertThat(result.result()).isEqualTo(HealthCheckResult.FAILURE);
        assertThat(result.errorMessage()).isNotNull();
    }
}
