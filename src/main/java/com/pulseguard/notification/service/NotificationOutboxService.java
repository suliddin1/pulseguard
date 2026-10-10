package com.pulseguard.notification.service;

import com.pulseguard.notification.config.NotificationProperties;
import com.pulseguard.notification.metrics.NotificationMetrics;
import com.pulseguard.notification.metrics.NotificationMetrics.DeadReason;
import com.pulseguard.notification.model.AttemptOutcome;
import com.pulseguard.notification.model.NotificationAttempt;
import com.pulseguard.notification.model.NotificationOutbox;
import com.pulseguard.notification.model.OutboxStatus;
import com.pulseguard.notification.repository.NotificationAttemptRepository;
import com.pulseguard.notification.repository.NotificationOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Claim / complete operations on the outbox. Every method is a short transaction that performs database
 * work only: no network calls ever happen inside these transactions, and no lock is held while a
 * notification is being delivered.
 *
 * <p>{@code now} is always passed in so behaviour (leases, backoff) is deterministic under test.
 */
@Service
public class NotificationOutboxService {

    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxService.class);

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationAttemptRepository attemptRepository;
    private final NotificationProperties properties;
    private final NotificationMetrics metrics;

    public NotificationOutboxService(
            NotificationOutboxRepository outboxRepository,
            NotificationAttemptRepository attemptRepository,
            NotificationProperties properties,
            NotificationMetrics metrics
    ) {
        this.outboxRepository = outboxRepository;
        this.attemptRepository = attemptRepository;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * Claims up to {@code limit} due rows (new, retry-due, or abandoned with an expired lease).
     *
     * <p>Each candidate is claimed with an atomic conditional UPDATE; losing a race simply yields zero
     * updated rows and the candidate is skipped, so concurrent workers/instances never claim the same row.
     */
    @Transactional
    public List<ClaimedNotification> claimDue(Instant now, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Duration lease = properties.getDispatcher().getLeaseDuration();
        List<UUID> candidates = outboxRepository.findClaimableIds(
                now, OutboxStatus.PENDING, OutboxStatus.PROCESSING, PageRequest.of(0, limit));

        List<ClaimedNotification> claimed = new ArrayList<>();
        for (UUID id : candidates) {
            String token = UUID.randomUUID().toString();
            int updated = outboxRepository.claim(
                    id, token, now.plus(lease), now, OutboxStatus.PENDING, OutboxStatus.PROCESSING);
            if (updated != 1) {
                continue; // another worker won the race
            }
            NotificationOutbox row = outboxRepository.findById(id).orElse(null);
            if (row == null) {
                continue;
            }
            if (row.getAttemptCount() > properties.getRetry().getMaxAttempts()) {
                // Re-claimed after lease expiry more often than allowed: the delivery keeps killing its
                // worker or never completes. Stop it instead of looping forever.
                outboxRepository.completeDead(id, token, now, "lease_attempts_exceeded", row.getLastHttpStatus(),
                        OutboxStatus.DEAD, OutboxStatus.PROCESSING);
                metrics.dead(row.getChannel(), DeadReason.LEASE_ATTEMPTS_EXCEEDED);
                log.warn("Notification {} dead-lettered: attempts exceeded after lease expiry", id);
                continue;
            }
            claimed.add(new ClaimedNotification(
                    row.getId(), row.getEventId(), row.getChannel(), row.getEventType(),
                    row.getPayload(), row.getAttemptCount(), token));
        }
        return claimed;
    }

    /** Records the attempt and marks the row DELIVERED. Returns false if the lease had been lost. */
    @Transactional
    public boolean recordDelivered(ClaimedNotification claim, Instant attemptedAt, long durationMs, Integer httpStatus, Instant now) {
        recordAttempt(claim, attemptedAt, durationMs, AttemptOutcome.SUCCESS, httpStatus, null);
        return outboxRepository.completeDelivered(
                claim.id(), claim.leaseToken(), now, httpStatus, OutboxStatus.DELIVERED, OutboxStatus.PROCESSING) == 1;
    }

    /** Records the failed attempt and schedules the next one. Returns false if the lease had been lost. */
    @Transactional
    public boolean recordRetry(
            ClaimedNotification claim, Instant attemptedAt, long durationMs, Integer httpStatus,
            String error, Instant nextAttemptAt, Instant now
    ) {
        String safeError = NotificationAttempt.truncate(error);
        recordAttempt(claim, attemptedAt, durationMs, AttemptOutcome.RETRYABLE_FAILURE, httpStatus, safeError);
        return outboxRepository.completeRetry(
                claim.id(), claim.leaseToken(), nextAttemptAt, now, safeError, httpStatus,
                OutboxStatus.PENDING, OutboxStatus.PROCESSING) == 1;
    }

    /**
     * Records the final failed attempt and dead-letters the row, preserving diagnostics. {@code outcome}
     * distinguishes exhausted retries (RETRYABLE_FAILURE) from permanent failures.
     */
    @Transactional
    public boolean recordDead(
            ClaimedNotification claim, Instant attemptedAt, long durationMs, AttemptOutcome outcome,
            Integer httpStatus, String error, Instant now
    ) {
        String safeError = NotificationAttempt.truncate(error);
        recordAttempt(claim, attemptedAt, durationMs, outcome, httpStatus, safeError);
        return outboxRepository.completeDead(
                claim.id(), claim.leaseToken(), now, safeError, httpStatus, OutboxStatus.DEAD, OutboxStatus.PROCESSING) == 1;
    }

    /** Dead-letters without a delivery attempt (e.g. channel disabled), still recording why. */
    @Transactional
    public boolean recordDeadWithoutAttempt(ClaimedNotification claim, String error, Instant now) {
        return outboxRepository.completeDead(
                claim.id(), claim.leaseToken(), now, NotificationAttempt.truncate(error), null,
                OutboxStatus.DEAD, OutboxStatus.PROCESSING) == 1;
    }

    /** Returns a claim to PENDING without consuming an attempt. */
    @Transactional
    public boolean release(ClaimedNotification claim, Instant nextAttemptAt, Instant now) {
        return outboxRepository.release(
                claim.id(), claim.leaseToken(), nextAttemptAt, now, OutboxStatus.PENDING, OutboxStatus.PROCESSING) == 1;
    }

    private void recordAttempt(
            ClaimedNotification claim, Instant attemptedAt, long durationMs,
            AttemptOutcome outcome, Integer httpStatus, String error
    ) {
        // Kept even if the lease turns out to be lost: it is an honest record that a request was made.
        attemptRepository.save(new NotificationAttempt(
                claim.id(), claim.attemptNumber(), attemptedAt, durationMs, outcome, httpStatus, error));
    }
}
