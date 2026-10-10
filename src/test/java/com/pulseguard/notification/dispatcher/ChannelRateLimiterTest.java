package com.pulseguard.notification.dispatcher;

import com.pulseguard.notification.model.NotificationChannelType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelRateLimiterTest {

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration duration) {
            now.updateAndGet(i -> i.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @Test
    @DisplayName("ChannelRateLimiter throttles bursts and refills tokens smoothly")
    void rateLimiterThrottlesAndRefills() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-10T12:00:00Z"));
        // 2 tokens per minute = 1 token every 30 seconds
        ChannelRateLimiter limiter = new ChannelRateLimiter(clock, 2);

        // First 2 tokens should succeed immediately (burst allowance)
        assertThat(limiter.tryAcquire(NotificationChannelType.WEBHOOK)).isTrue();
        assertThat(limiter.tryAcquire(NotificationChannelType.WEBHOOK)).isTrue();

        // 3rd token in the same second should fail
        assertThat(limiter.tryAcquire(NotificationChannelType.WEBHOOK)).isFalse();
        assertThat(limiter.timeUntilNextToken(NotificationChannelType.WEBHOOK).toMillis()).isGreaterThan(0);

        // Channels are isolated
        assertThat(limiter.tryAcquire(NotificationChannelType.SLACK)).isTrue();

        // Advance clock by 35 seconds -> 1 token should have refilled for WEBHOOK
        clock.advance(Duration.ofSeconds(35));
        assertThat(limiter.tryAcquire(NotificationChannelType.WEBHOOK)).isTrue();
        // And now empty again
        assertThat(limiter.tryAcquire(NotificationChannelType.WEBHOOK)).isFalse();
    }

    @Test
    @DisplayName("0 per minute disables rate limiting")
    void zeroPerMinuteDisablesLimiter() {
        MutableClock clock = new MutableClock(Instant.now());
        ChannelRateLimiter limiter = new ChannelRateLimiter(clock, 0);

        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire(NotificationChannelType.WEBHOOK)).isTrue();
        }
    }
}
