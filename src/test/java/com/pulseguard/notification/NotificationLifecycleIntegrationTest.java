package com.pulseguard.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseguard.healthcheck.model.HealthCheckResult;
import com.pulseguard.healthcheck.prober.HttpHealthProber;
import com.pulseguard.healthcheck.prober.ProbeResult;
import com.pulseguard.healthcheck.service.HealthCheckExecutionService;
import com.pulseguard.notification.config.NotificationProperties;
import com.pulseguard.notification.dispatcher.NotificationDispatcher;
import com.pulseguard.notification.dto.NotificationDetailResponse;
import com.pulseguard.notification.dto.NotificationResponse;
import com.pulseguard.notification.model.NotificationChannelType;
import com.pulseguard.notification.model.NotificationEventType;
import com.pulseguard.notification.model.NotificationOutbox;
import com.pulseguard.notification.model.OutboxStatus;
import com.pulseguard.notification.repository.NotificationOutboxRepository;
import com.pulseguard.service.dto.CreateServiceRequest;
import com.pulseguard.service.dto.ServiceResponse;
import com.pulseguard.service.service.ServiceManagementService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class NotificationLifecycleIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ServiceManagementService serviceManagementService;

    @Autowired
    private HealthCheckExecutionService healthCheckExecutionService;

    @Autowired
    private NotificationOutboxRepository outboxRepository;

    @Autowired
    private NotificationDispatcher notificationDispatcher;

    @Autowired
    private NotificationProperties properties;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private HttpHealthProber httpHealthProber;

    private HttpServer mockReceiver;
    private int receiverPort;
    private final List<String> receivedEvents = Collections.synchronizedList(new ArrayList<>());
    private final AtomicReference<String> lastSignature = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        outboxRepository.deleteAll();

        mockReceiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiverPort = mockReceiver.getAddress().getPort();
        mockReceiver.createContext("/webhook", exchange -> {
            String event = exchange.getRequestHeaders().getFirst("X-PulseGuard-Event");
            String sig = exchange.getRequestHeaders().getFirst("X-PulseGuard-Signature");
            if (event != null) {
                receivedEvents.add(event);
            }
            lastSignature.set(sig);
            try (InputStream in = exchange.getRequestBody()) {
                lastBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        mockReceiver.start();

        // Enable webhook channel pointing to test receiver
        properties.getWebhook().setEnabled(true);
        properties.getWebhook().setUrl("http://127.0.0.1:" + receiverPort + "/webhook");
        properties.getWebhook().setSecret("integration-test-secret");
        properties.getSecurity().setAllowUnsafeDestinations(true);
    }

    @AfterEach
    void tearDown() {
        if (mockReceiver != null) {
            mockReceiver.stop(0);
        }
        properties.getWebhook().setEnabled(false);
    }

    @Test
    @DisplayName("End-to-end: incident creation enqueues outbox, dispatcher delivers webhook, and API returns record")
    void fullNotificationLifecycle() throws Exception {
        // 1. Register a service
        ServiceResponse service = serviceManagementService.createService(new CreateServiceRequest(
                "Orders Microservice",
                "Order fulfillment service",
                "https://orders.internal.example.com/health",
                30,
                3000
        ));
        UUID serviceId = service.id();

        // 2. Simulate 3 consecutive health check failures -> triggers incident
        when(httpHealthProber.probe(anyString(), anyInt()))
                .thenReturn(new ProbeResult(HealthCheckResult.FAILURE, 503, 350, "Service Unavailable"));

        healthCheckExecutionService.executeHealthCheck(serviceId);
        healthCheckExecutionService.executeHealthCheck(serviceId);
        healthCheckExecutionService.executeHealthCheck(serviceId);

        // 3. Verify outbox row exists in PENDING state
        List<NotificationOutbox> pendingRows = outboxRepository.findAll();
        assertThat(pendingRows).hasSize(1);
        NotificationOutbox pending = pendingRows.get(0);
        assertThat(pending.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(pending.getChannel()).isEqualTo(NotificationChannelType.WEBHOOK);
        assertThat(pending.getEventType()).isEqualTo(NotificationEventType.INCIDENT_OPENED);
        assertThat(pending.getServiceId()).isEqualTo(serviceId);
        assertThat(pending.getAttemptCount()).isZero();

        // 4. Run dispatcher batch
        int dispatched = notificationDispatcher.dispatchBatch();
        assertThat(dispatched).isEqualTo(1);
        waitForCondition(() -> !receivedEvents.isEmpty(), 5000);

        // 5. Verify local receiver received webhook
        assertThat(receivedEvents).contains("INCIDENT_OPENED");
        assertThat(lastSignature.get()).startsWith("sha256=");
        JsonNode json = objectMapper.readTree(lastBody.get());
        assertThat(json.get("eventType").asText()).isEqualTo("INCIDENT_OPENED");
        assertThat(json.get("service").get("name").asText()).isEqualTo("Orders Microservice");

        // 6. Verify outbox row in DB updated to DELIVERED
        waitForCondition(() -> {
            NotificationOutbox r = outboxRepository.findById(pending.getId()).orElse(null);
            return r != null && r.getStatus() == OutboxStatus.DELIVERED;
        }, 5000);
        NotificationOutbox delivered = outboxRepository.findById(pending.getId()).orElseThrow();
        assertThat(delivered.getStatus()).isEqualTo(OutboxStatus.DELIVERED);
        assertThat(delivered.getDeliveredAt()).isNotNull();
        assertThat(delivered.getLastHttpStatus()).isEqualTo(200);
        assertThat(delivered.getAttemptCount()).isEqualTo(1);

        // 7. Verify management API returns the delivered notification
        ResponseEntity<NotificationDetailResponse> detailResp = restTemplate.getForEntity(
                "/api/v1/notifications/" + pending.getId(), NotificationDetailResponse.class
        );
        assertThat(detailResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detailResp.getBody()).isNotNull();
        assertThat(detailResp.getBody().id()).isEqualTo(pending.getId());
        assertThat(detailResp.getBody().status()).isEqualTo(OutboxStatus.DELIVERED);
        assertThat(detailResp.getBody().attempts()).hasSize(1);
        assertThat(detailResp.getBody().attempts().get(0).httpStatus()).isEqualTo(200);

        // 8. Service recovers -> INCIDENT_RESOLVED event enqueued
        when(httpHealthProber.probe(anyString(), anyInt()))
                .thenReturn(new ProbeResult(HealthCheckResult.SUCCESS, 200, 45, null));
        healthCheckExecutionService.executeHealthCheck(serviceId);

        List<NotificationOutbox> allRows = outboxRepository.findAll();
        assertThat(allRows).hasSize(2);
        NotificationOutbox resolvedRow = allRows.stream()
                .filter(r -> r.getEventType() == NotificationEventType.INCIDENT_RESOLVED)
                .findFirst().orElseThrow();
        assertThat(resolvedRow.getStatus()).isEqualTo(OutboxStatus.PENDING);

        // 9. Dispatch resolved event
        notificationDispatcher.dispatchBatch();
        waitForCondition(() -> receivedEvents.contains("INCIDENT_RESOLVED"), 5000);
        assertThat(receivedEvents).contains("INCIDENT_RESOLVED");

        waitForCondition(() -> {
            NotificationOutbox r = outboxRepository.findById(resolvedRow.getId()).orElse(null);
            return r != null && r.getStatus() == OutboxStatus.DELIVERED;
        }, 5000);
        NotificationOutbox resolvedDelivered = outboxRepository.findById(resolvedRow.getId()).orElseThrow();
        assertThat(resolvedDelivered.getStatus()).isEqualTo(OutboxStatus.DELIVERED);
    }

    private void waitForCondition(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
    }
}
