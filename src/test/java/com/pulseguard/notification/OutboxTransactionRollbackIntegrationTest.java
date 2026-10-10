package com.pulseguard.notification;

import com.pulseguard.incident.model.Incident;
import com.pulseguard.incident.model.IncidentSeverity;
import com.pulseguard.incident.model.IncidentType;
import com.pulseguard.notification.config.NotificationProperties;
import com.pulseguard.notification.repository.NotificationOutboxRepository;
import com.pulseguard.notification.service.NotificationEnqueuer;
import com.pulseguard.service.model.MonitoredService;
import com.pulseguard.service.repository.ServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class OutboxTransactionRollbackIntegrationTest {

    @Autowired
    private NotificationEnqueuer notificationEnqueuer;

    @Autowired
    private NotificationOutboxRepository outboxRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private NotificationProperties properties;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        properties.getWebhook().setEnabled(true);
        properties.getWebhook().setUrl("https://api.example.com/events");
        properties.getSecurity().setAllowUnsafeDestinations(true);
    }

    @Test
    @DisplayName("Transactional Outbox: outbox write rolls back completely if enclosing transaction rolls back")
    void outboxRowRollsBackWithTransaction() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        MonitoredService service = serviceRepository.save(
                new MonitoredService("Rollback Service", "desc", "https://rb.com", 30, 3000)
        );
        Incident incident = new Incident(service, IncidentType.SERVICE_UNAVAILABLE, IncidentSeverity.CRITICAL, "Crash", "err", Instant.now());

        assertThatThrownBy(() -> {
            txTemplate.execute(status -> {
                notificationEnqueuer.incidentOpened(incident);
                // Deliberately trigger a rollback
                throw new RuntimeException("Simulated transaction rollback");
            });
        }).isInstanceOf(RuntimeException.class).hasMessage("Simulated transaction rollback");

        // Outbox must be completely empty: rollback unwound the outbox INSERT
        assertThat(outboxRepository.count()).isZero();
    }
}
