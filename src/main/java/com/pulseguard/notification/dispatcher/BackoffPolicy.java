package com.pulseguard.notification.dispatcher;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * Bounded exponential backoff with symmetric jitter.
 *
 * <p>{@code delay(n) = clamp(initial * 2^(n-1) * (1 + jitter * (2r - 1)), 1ms, max)} for {@code r} in
 * [0, 1). {@code n} is the number of the attempt that just failed (1-based).
 */
public class BackoffPolicy {

    private final Duration initial;
    private final Duration max;
    private final double jitter;
    private final DoubleSupplier random;

    public BackoffPolicy(Duration initial, Duration max, double jitter, DoubleSupplier random) {
        this.initial = initial;
        this.max = max;
        this.jitter = jitter;
        this.random = random;
    }

    public Duration delayAfterAttempt(int failedAttemptNumber) {
        int exponent = Math.max(0, Math.min(failedAttemptNumber - 1, 40));
        double base = (double) initial.toMillis() * Math.pow(2, exponent);
        double capped = Math.min(base, (double) max.toMillis());
        double factor = 1.0 + jitter * (2.0 * random.getAsDouble() - 1.0);
        long millis = Math.round(capped * factor);
        return Duration.ofMillis(Math.max(1L, Math.min(millis, max.toMillis())));
    }
}
