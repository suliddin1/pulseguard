package com.pulseguard.notification.service;

import com.pulseguard.common.exception.ResourceNotFoundException;
import com.pulseguard.notification.dto.NotificationAttemptResponse;
import com.pulseguard.notification.dto.NotificationDetailResponse;
import com.pulseguard.notification.dto.NotificationResponse;
import com.pulseguard.notification.model.NotificationAttempt;
import com.pulseguard.notification.model.NotificationChannelType;
import com.pulseguard.notification.model.NotificationOutbox;
import com.pulseguard.notification.model.OutboxStatus;
import com.pulseguard.notification.repository.NotificationAttemptRepository;
import com.pulseguard.notification.repository.NotificationOutboxRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class NotificationManagementServiceImpl implements NotificationManagementService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationAttemptRepository attemptRepository;

    public NotificationManagementServiceImpl(
            NotificationOutboxRepository outboxRepository,
            NotificationAttemptRepository attemptRepository
    ) {
        this.outboxRepository = outboxRepository;
        this.attemptRepository = attemptRepository;
    }

    @Override
    public Page<NotificationResponse> getNotifications(
            OutboxStatus status,
            NotificationChannelType channel,
            UUID incidentId,
            Pageable pageable
    ) {
        Pageable effectivePageable = sanitizePageable(pageable);
        Specification<NotificationOutbox> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (channel != null) {
                predicates.add(cb.equal(root.get("channel"), channel));
            }
            if (incidentId != null) {
                predicates.add(cb.equal(root.get("incidentId"), incidentId));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return outboxRepository.findAll(spec, effectivePageable).map(NotificationResponse::from);
    }

    @Override
    public NotificationDetailResponse getNotificationById(UUID id) {
        NotificationOutbox notification = outboxRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notification", id));

        List<NotificationAttempt> attempts = attemptRepository.findByOutboxIdOrderByAttemptNumberAsc(id);
        List<NotificationAttemptResponse> attemptResponses = attempts.stream()
                .map(NotificationAttemptResponse::from)
                .toList();

        return NotificationDetailResponse.from(notification, attemptResponses);
    }

    private Pageable sanitizePageable(Pageable pageable) {
        int pageSize = Math.min(Math.max(1, pageable.getPageSize()), MAX_PAGE_SIZE);
        Sort sort = pageable.getSort().isSorted() ? pageable.getSort() : DEFAULT_SORT;
        return PageRequest.of(Math.max(0, pageable.getPageNumber()), pageSize, sort);
    }
}
