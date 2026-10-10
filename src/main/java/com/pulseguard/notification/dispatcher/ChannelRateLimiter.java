package com.pulseguard.notification.dispatcher;

import com.pulseguard.notification.model.NotificationChannelType;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Per-channel token bucket (burst = one minute's allowance). In-memory and per application instance:
 * with N instances the effective limit is N times the configured value (documented limitation).
 */
public class ChannelRateLimiter {

    private static final class Bucket {
        double tokens;
        long lastRefillNanos;
    }

    private final Clock clock;
    private final int perMinute;
    private final Map<NotificationChannelType, Bucket> buckets = new EnumMap<>(NotificationChannelType.class);

    /** @param perMinute deliveries per minute per channel; {@code 0} disables limiting. */
    public ChannelRateLimiter(Clock clock, int perMinute) {
        this.clock = clock;
        this.perMinute = perMinute;
    }

    public synchronized boolean tryAcquire(NotificationChannelType channel) {
        if (perMinute <= 0) {
            return true;
        }
        Bucket bucket = refilled(channel);
        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0;
            return true;
        }
        return false;
    }

    /** Time until a token should be available (at least 1 second, to avoid hot loops). */
    public synchronized Duration timeUntilNextToken(NotificationChannelType channel) {
        if (perMinute <= 0) {
            return Duration.ZERO;
        }
        Bucket bucket = refilled(channel);
        double missing = Math.max(0.0, 1.0 - bucket.tokens);
        long millis = (long) Math.ceil(missing / perMinute * 60_000.0);
        return Duration.ofMillis(Math.max(1000L, millis));
    }

    private Bucket refilled(NotificationChannelType channel) {
        long now = nanos();
        Bucket bucket = buckets.get(channel);
        if (bucket == null) {
            bucket = new Bucket();
            bucket.tokens = perMinute;
            bucket.lastRefillNanos = now;
            buckets.put(channel, bucket);
            return bucket;
        }
        double elapsedMinutes = (now - bucket.lastRefillNanos) / 60_000_000_000.0;
        bucket.tokens = Math.min(perMinute, bucket.tokens + elapsedMinutes * perMinute);
        bucket.lastRefillNanos = now;
        return bucket;
    }

    private long nanos() {
        java.time.Instant instant = clock.instant();
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }
}
