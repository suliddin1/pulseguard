package com.pulseguard.notification.service;

import com.pulseguard.notification.config.NotificationProperties;
import com.pulseguard.notification.model.NotificationChannelType;
import com.pulseguard.notification.model.NotificationEventType;
import com.pulseguard.notification.model.NotificationOutbox;
import com.pulseguard.notification.model.OutboxStatus;
import com.pulseguard.notification.repository.NotificationOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class NotificationOutboxServiceConcurrencyTest {

    @Autowired
    private NotificationOutboxService outboxService;

    @Autowired
    private NotificationOutboxRepository outboxRepository;

    @Autowired
    private NotificationProperties properties;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanUp() {
        outboxRepository.deleteAll();
    }

    @Test
    @DisplayName("Concurrent claimDue: multiple workers claim due rows without any duplicate claims")
    void concurrentClaimsNeverDuplicate() throws Exception {
        int totalRows = 30;
        Instant now = Instant.now();
        UUID incidentId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        List<NotificationOutbox> outboxList = new ArrayList<>();
        for (int i = 0; i < totalRows; i++) {
            outboxList.add(new NotificationOutbox(
                    UUID.randomUUID(),
                    NotificationChannelType.WEBHOOK,
                    NotificationEventType.INCIDENT_OPENED,
                    incidentId,
                    serviceId,
                    "{\"test\":" + i + "}",
                    now
            ));
        }
        outboxRepository.saveAll(outboxList);

        int workers = 6;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch readyLatch = new CountDownLatch(workers);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(workers);

        Set<UUID> allClaimedIds = ConcurrentHashMap.newKeySet();
        List<UUID> rawClaimLog = Collections.synchronizedList(new ArrayList<>());

        for (int w = 0; w < workers; w++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    List<ClaimedNotification> claimed = outboxService.claimDue(now, 10);
                    for (ClaimedNotification c : claimed) {
                        rawClaimLog.add(c.id());
                        allClaimedIds.add(c.id());
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        // ZERO duplicate claims: size of raw log must equal size of unique set
        assertThat(rawClaimLog).hasSameSizeAs(allClaimedIds);
        assertThat(allClaimedIds.size()).isLessThanOrEqualTo(totalRows);

        // Verify status in DB is PROCESSING with non-null leaseToken and attemptCount=1
        for (UUID id : allClaimedIds) {
            NotificationOutbox entity = outboxRepository.findById(id).orElseThrow();
            assertThat(entity.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
            assertThat(entity.getLeaseToken()).isNotNull();
            assertThat(entity.getAttemptCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Lease recovery: abandoned PROCESSING row with expired lease is re-claimed")
    void expiredLeaseIsReclaimed() {
        Instant past = Instant.now().minus(Duration.ofMinutes(10));
        UUID incidentId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();

        NotificationOutbox row = new NotificationOutbox(
                UUID.randomUUID(),
                NotificationChannelType.WEBHOOK,
                NotificationEventType.INCIDENT_OPENED,
                incidentId,
                serviceId,
                "{\"abandoned\":true}",
                past
        );
        outboxRepository.save(row);

        // Manually claim with past lease to simulate a crashed worker
        String oldToken = "old-crashed-token";
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status ->
                outboxRepository.claim(row.getId(), oldToken, past.plusSeconds(30), past.plusSeconds(1), OutboxStatus.PENDING, OutboxStatus.PROCESSING)
        );

        NotificationOutbox crashed = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(crashed.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
        assertThat(crashed.getLeaseToken()).isEqualTo(oldToken);

        // Now claim with current time
        Instant now = Instant.now();
        List<ClaimedNotification> reclaimed = outboxService.claimDue(now, 10);

        assertThat(reclaimed).hasSize(1);
        ClaimedNotification claim = reclaimed.get(0);
        assertThat(claim.id()).isEqualTo(row.getId());
        assertThat(claim.leaseToken()).isNotEqualTo(oldToken);
        assertThat(claim.attemptNumber()).isEqualTo(2); // attempt count incremented

        NotificationOutbox updated = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(updated.getAttemptCount()).isEqualTo(2);
        assertThat(updated.getLeaseToken()).isEqualTo(claim.leaseToken());
    }
}
