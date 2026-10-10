package com.pulseguard.notification.dispatcher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffPolicyTest {

    @Test
    @DisplayName("delayAfterAttempt calculates exponential backoff capped by maxBackoff")
    void delayAfterAttemptGrowsExponentiallyAndCaps() {
        Duration initial = Duration.ofSeconds(5);
        Duration max = Duration.ofMinutes(10);
        // Deterministic zero-jitter supplier (random returns 0.5 -> 2*0.5 - 1 = 0 -> multiplier 1.0)
        BackoffPolicy policy = new BackoffPolicy(initial, max, 0.2, () -> 0.5);

        // Attempt 1: 5 * 2^0 = 5s
        assertThat(policy.delayAfterAttempt(1)).isEqualTo(Duration.ofSeconds(5));
        // Attempt 2: 5 * 2^1 = 10s
        assertThat(policy.delayAfterAttempt(2)).isEqualTo(Duration.ofSeconds(10));
        // Attempt 3: 5 * 2^2 = 20s
        assertThat(policy.delayAfterAttempt(3)).isEqualTo(Duration.ofSeconds(20));
        // Attempt 4: 5 * 2^3 = 40s
        assertThat(policy.delayAfterAttempt(4)).isEqualTo(Duration.ofSeconds(40));
        // High attempt: capped at 10 minutes (600s)
        assertThat(policy.delayAfterAttempt(10)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("jitter stays strictly within configured boundaries")
    void jitterStaysWithinBounds() {
        Duration initial = Duration.ofSeconds(10);
        Duration max = Duration.ofMinutes(5);

        // Min jitter: random returns 0.0 -> factor 1.0 - 0.2 = 0.8 -> 8 seconds
        BackoffPolicy minPolicy = new BackoffPolicy(initial, max, 0.2, () -> 0.0);
        assertThat(minPolicy.delayAfterAttempt(1)).isEqualTo(Duration.ofMillis(8000));

        // Max jitter: random returns 1.0 -> factor 1.0 + 0.2 = 1.2 -> 12 seconds
        BackoffPolicy maxPolicy = new BackoffPolicy(initial, max, 0.2, () -> 1.0);
        assertThat(maxPolicy.delayAfterAttempt(1)).isEqualTo(Duration.ofMillis(12000));
    }
}
