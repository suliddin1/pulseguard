package com.pulseguard.notification.repository;

import com.pulseguard.notification.model.NotificationChannelType;
import com.pulseguard.notification.model.NotificationEventType;
import com.pulseguard.notification.model.NotificationOutbox;
import com.pulseguard.notification.model.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Outbox persistence. All state transitions are single-statement compare-and-set updates, so correctness
 * does not depend on row locks held across calls or on a particular database's {@code SKIP LOCKED} support:
 * a claim succeeds only for the one caller whose UPDATE still sees the row as eligible, and every
 * completion is fenced by the lease token that caller obtained.
 */
public interface NotificationOutboxRepository
        extends JpaRepository<NotificationOutbox, UUID>, JpaSpecificationExecutor<NotificationOutbox> {

    boolean existsByEventIdAndChannel(UUID eventId, NotificationChannelType channel);

    boolean existsByIncidentIdAndEventTypeAndCreatedAtAfter(UUID incidentId, NotificationEventType eventType, Instant after);

    long countByStatus(OutboxStatus status);

    /** Ids of rows that are due (PENDING) or abandoned (PROCESSING with an expired lease), oldest first. */
    @Query("""
            select o.id from NotificationOutbox o
            where (o.status = :pending and o.nextAttemptAt <= :now)
               or (o.status = :processing and o.leaseExpiresAt < :now)
            order by o.nextAttemptAt asc
            """)
    List<UUID> findClaimableIds(
            @Param("now") Instant now,
            @Param("pending") OutboxStatus pending,
            @Param("processing") OutboxStatus processing,
            Pageable pageable
    );

    /**
     * Atomically claims one row. Returns 1 only for the caller whose UPDATE matched; concurrent claimers
     * (other threads or other application instances) see 0 because the row is no longer eligible.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationOutbox o
               set o.status = :processing,
                   o.leaseToken = :token,
                   o.leaseExpiresAt = :leaseUntil,
                   o.attemptCount = o.attemptCount + 1,
                   o.updatedAt = :now
             where o.id = :id
               and ((o.status = :pending and o.nextAttemptAt <= :now)
                 or (o.status = :processing and o.leaseExpiresAt < :now))
            """)
    int claim(
            @Param("id") UUID id,
            @Param("token") String token,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("now") Instant now,
            @Param("pending") OutboxStatus pending,
            @Param("processing") OutboxStatus processing
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationOutbox o
               set o.status = :delivered, o.leaseToken = null, o.leaseExpiresAt = null,
                   o.deliveredAt = :now, o.updatedAt = :now, o.lastError = null, o.lastHttpStatus = :httpStatus
             where o.id = :id and o.status = :processing and o.leaseToken = :token
            """)
    int completeDelivered(
            @Param("id") UUID id,
            @Param("token") String token,
            @Param("now") Instant now,
            @Param("httpStatus") Integer httpStatus,
            @Param("delivered") OutboxStatus delivered,
            @Param("processing") OutboxStatus processing
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationOutbox o
               set o.status = :pending, o.leaseToken = null, o.leaseExpiresAt = null,
                   o.nextAttemptAt = :nextAttemptAt, o.updatedAt = :now,
                   o.lastError = :error, o.lastHttpStatus = :httpStatus
             where o.id = :id and o.status = :processing and o.leaseToken = :token
            """)
    int completeRetry(
            @Param("id") UUID id,
            @Param("token") String token,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("now") Instant now,
            @Param("error") String error,
            @Param("httpStatus") Integer httpStatus,
            @Param("pending") OutboxStatus pending,
            @Param("processing") OutboxStatus processing
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationOutbox o
               set o.status = :dead, o.leaseToken = null, o.leaseExpiresAt = null,
                   o.updatedAt = :now, o.lastError = :error, o.lastHttpStatus = :httpStatus
             where o.id = :id and o.status = :processing and o.leaseToken = :token
            """)
    int completeDead(
            @Param("id") UUID id,
            @Param("token") String token,
            @Param("now") Instant now,
            @Param("error") String error,
            @Param("httpStatus") Integer httpStatus,
            @Param("dead") OutboxStatus dead,
            @Param("processing") OutboxStatus processing
    );

    /** Gives back a claim without consuming an attempt (used when rate limited before sending). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update NotificationOutbox o
               set o.status = :pending, o.leaseToken = null, o.leaseExpiresAt = null,
                   o.attemptCount = o.attemptCount - 1,
                   o.nextAttemptAt = :nextAttemptAt, o.updatedAt = :now
             where o.id = :id and o.status = :processing and o.leaseToken = :token and o.attemptCount > 0
            """)
    int release(
            @Param("id") UUID id,
            @Param("token") String token,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("now") Instant now,
            @Param("pending") OutboxStatus pending,
            @Param("processing") OutboxStatus processing
    );
}
