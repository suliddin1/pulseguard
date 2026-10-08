package com.pulseguard.scheduler.service;

import com.pulseguard.service.model.MonitoredService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

@Component
public class DefaultDueCheckEvaluator implements DueCheckEvaluator {

    @Override
    public boolean isDue(MonitoredService service, Instant lastCheckedAt, Instant now) {
        if (service == null || !service.isEnabled()) {
            return false;
        }

        Objects.requireNonNull(now, "Reference timestamp 'now' must not be null");

        if (lastCheckedAt == null) {
            // Service has never been checked -> due immediately
            return true;
        }

        Instant nextDueTime = lastCheckedAt.plusSeconds(service.getCheckIntervalSeconds());
        return !now.isBefore(nextDueTime);
    }

    @Override
    public Instant calculateNextDueTime(MonitoredService service, Instant lastCheckedAt, Instant now) {
        if (lastCheckedAt == null) {
            return now;
        }
        return lastCheckedAt.plusSeconds(service.getCheckIntervalSeconds());
    }
}
