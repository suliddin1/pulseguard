package com.pulseguard.notification.dispatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseguard.notification.channel.DeliveryResult;
import com.pulseguard.notification.channel.NotificationChannel;
import com.pulseguard.notification.channel.NotificationChannelRegistry;
import com.pulseguard.notification.channel.NotificationMessage;
import com.pulseguard.notification.config.NotificationProperties;
import com.pulseguard.notification.metrics.NotificationMetrics;
import com.pulseguard.notification.metrics.NotificationMetrics.DeadReason;
import com.pulseguard.notification.model.AttemptOutcome;
import com.pulseguard.notification.payload.NotificationPayload;
import com.pulseguard.notification.service.ClaimedNotification;
import com.pulseguard.notification.service.NotificationOutboxService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Background polling dispatcher for outbox notification delivery.
 *
 * <p>Safely coordinates claims, rate limits, async bounded dispatch, exponential backoff retries,
 * and crash-recovery of expired leases.
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationOutboxService outboxService;
    private final NotificationChannelRegistry channelRegistry;
    private final ChannelRateLimiter rateLimiter;
    private final BackoffPolicy backoffPolicy;
    private final NotificationProperties properties;
    private final NotificationMetrics metrics;
    private final ObjectMapper objectMapper;
    private final TaskExecutor taskExecutor;
    private final Clock clock;

    private final AtomicInteger inFlightDeliveries = new AtomicInteger(0);
    private volatile boolean accepting = true;

    public NotificationDispatcher(
            NotificationOutboxService outboxService,
            NotificationChannelRegistry channelRegistry,
            ChannelRateLimiter rateLimiter,
            BackoffPolicy backoffPolicy,
            NotificationProperties properties,
            NotificationMetrics metrics,
            ObjectMapper objectMapper,
            @Qualifier("notificationTaskExecutor") TaskExecutor taskExecutor,
            Clock clock
    ) {
        this.outboxService = outboxService;
        this.channelRegistry = channelRegistry;
        this.rateLimiter = rateLimiter;
        this.backoffPolicy = backoffPolicy;
        this.properties = properties;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.taskExecutor = taskExecutor;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${pulseguard.notifications.dispatcher.poll-interval-ms:2000}",
            initialDelayString = "${pulseguard.notifications.dispatcher.initial-delay-ms:3000}"
    )
    public void poll() {
        if (!properties.getDispatcher().isEnabled() || !accepting) {
            return;
        }
        dispatchBatch();
    }

    /**
     * Polls and dispatches due claims up to available executor capacity.
     *
     * @return number of notifications claimed and submitted in this iteration.
     */
    public int dispatchBatch() {
        if (!accepting) {
            return 0;
        }

        int maxConcurrent = properties.getDispatcher().getMaxConcurrentDeliveries();
        int currentInFlight = inFlightDeliveries.get();
        int availableSlots = Math.max(0, maxConcurrent - currentInFlight);
        if (availableSlots <= 0) {
            return 0;
        }

        int batchSize = Math.min(properties.getDispatcher().getBatchSize(), availableSlots);
        Instant now = clock.instant();
        List<ClaimedNotification> claims = outboxService.claimDue(now, batchSize);

        int submitted = 0;
        for (ClaimedNotification claim : claims) {
            if (!accepting) {
                // If shutting down mid-batch, release claim
                outboxService.release(claim, now, now);
                continue;
            }

            inFlightDeliveries.incrementAndGet();
            try {
                taskExecutor.execute(() -> {
                    try {
                        deliverClaim(claim);
                    } finally {
                        inFlightDeliveries.decrementAndGet();
                    }
                });
                submitted++;
            } catch (RejectedExecutionException ex) {
                inFlightDeliveries.decrementAndGet();
                log.warn("Notification executor rejected task for claim {}, releasing back to pending", claim.id());
                outboxService.release(claim, clock.instant().plusSeconds(2), clock.instant());
            }
        }

        return submitted;
    }

    public void deliverClaim(ClaimedNotification claim) {
        Instant attemptedAt = clock.instant();

        NotificationChannel channel = channelRegistry.find(claim.channel()).orElse(null);
        if (channel == null || !channel.isEnabled()) {
            log.info("Channel {} is missing or disabled; dead-lettering notification {}", claim.channel(), claim.id());
            outboxService.recordDeadWithoutAttempt(claim, "channel_disabled", attemptedAt);
            metrics.dead(claim.channel(), DeadReason.CHANNEL_DISABLED);
            return;
        }

        if (!rateLimiter.tryAcquire(claim.channel())) {
            Duration waitTime = rateLimiter.timeUntilNextToken(claim.channel());
            log.debug("Rate limit exceeded for channel {}, releasing claim {} for {} ms",
                    claim.channel(), claim.id(), waitTime.toMillis());
            outboxService.release(claim, attemptedAt.plus(waitTime), attemptedAt);
            metrics.rateLimited(claim.channel());
            return;
        }

        NotificationPayload payload;
        try {
            payload = objectMapper.readValue(claim.payload(), NotificationPayload.class);
        } catch (Exception ex) {
            log.error("Failed to parse outbox payload for notification {}: {}", claim.id(), ex.getMessage());
            outboxService.recordDeadWithoutAttempt(claim, "payload_unreadable", attemptedAt);
            metrics.dead(claim.channel(), DeadReason.PAYLOAD_UNREADABLE);
            return;
        }

        metrics.attempt(claim.channel());
        long startNanos = System.nanoTime();
        DeliveryResult result = channel.deliver(new NotificationMessage(claim.id(), claim.attemptNumber(), payload));
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        Instant finishedAt = clock.instant();

        if (result.kind() == DeliveryResult.Kind.SUCCESS) {
            boolean saved = outboxService.recordDelivered(claim, attemptedAt, durationMs, result.httpStatus(), finishedAt);
            if (saved) {
                metrics.delivered(claim.channel());
                log.debug("Delivered notification {} via {} in {} ms (status {})",
                        claim.id(), claim.channel(), durationMs, result.httpStatus());
            } else {
                log.warn("Delivery completed for {} but lease was lost (duration: {} ms)", claim.id(), durationMs);
            }
        } else if (result.kind() == DeliveryResult.Kind.PERMANENT) {
            boolean saved = outboxService.recordDead(
                    claim, attemptedAt, durationMs, AttemptOutcome.PERMANENT_FAILURE,
                    result.httpStatus(), result.message(), finishedAt
            );
            if (saved) {
                metrics.failed(claim.channel(), false);
                metrics.dead(claim.channel(), DeadReason.PERMANENT_FAILURE);
                log.warn("Permanent failure delivering notification {} via {}: status={}, error={}",
                        claim.id(), claim.channel(), result.httpStatus(), result.message());
            }
        } else {
            // RETRYABLE
            int maxAttempts = properties.getRetry().getMaxAttempts();
            if (claim.attemptNumber() >= maxAttempts) {
                boolean saved = outboxService.recordDead(
                        claim, attemptedAt, durationMs, AttemptOutcome.RETRYABLE_FAILURE,
                        result.httpStatus(), result.message(), finishedAt
                );
                if (saved) {
                    metrics.failed(claim.channel(), true);
                    metrics.dead(claim.channel(), DeadReason.RETRIES_EXHAUSTED);
                    log.warn("Retries exhausted ({}/{}) for notification {} via {}: status={}, error={}",
                            claim.attemptNumber(), maxAttempts, claim.id(), claim.channel(),
                            result.httpStatus(), result.message());
                }
            } else {
                Duration delay = backoffPolicy.delayAfterAttempt(claim.attemptNumber());
                Instant nextAttemptAt = finishedAt.plus(delay);
                boolean saved = outboxService.recordRetry(
                        claim, attemptedAt, durationMs, result.httpStatus(),
                        result.message(), nextAttemptAt, finishedAt
                );
                if (saved) {
                    metrics.failed(claim.channel(), true);
                    metrics.retry(claim.channel());
                    log.info("Retryable failure for notification {} via {} (attempt {}/{}); retrying in {} ms: status={}, error={}",
                            claim.id(), claim.channel(), claim.attemptNumber(), maxAttempts,
                            delay.toMillis(), result.httpStatus(), result.message());
                }
            }
        }
    }

    @PreDestroy
    public void stop() {
        this.accepting = false;
        log.info("Notification dispatcher stopped accepting new tasks");
    }

    public int getInFlightCount() {
        return inFlightDeliveries.get();
    }

    public boolean isAccepting() {
        return accepting;
    }
}
