package com.pulseguard.notification.repository;

import com.pulseguard.notification.model.NotificationAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationAttemptRepository extends JpaRepository<NotificationAttempt, UUID> {

    List<NotificationAttempt> findByOutboxIdOrderByAttemptNumberAsc(UUID outboxId);

    long countByOutboxId(UUID outboxId);
}
