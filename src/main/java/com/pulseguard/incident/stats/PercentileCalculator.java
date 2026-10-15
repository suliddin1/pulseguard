package com.pulseguard.incident.stats;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic percentile calculation on sorted numerical observations.
 *
 * <p>Uses the standard linear interpolation method (equivalent to NIST / R-7 / NumPy default / Excel PERCENTILE.INC).
 * For rank index {@code idx = (P / 100) * (N - 1)}, the percentile is computed as:
 * <pre>
 *   k = floor(idx)
 *   f = idx - k
 *   P = values[k] + f * (values[k + 1] - values[k])
 * </pre>
 * This provides continuous, deterministic percentile values without step-function artifacts
 * on small sample sizes.
 */
public final class PercentileCalculator {

    private PercentileCalculator() {
    }

    /**
     * Computes the specified percentile on a sorted list of non-negative latency values.
     *
     * @param sortedValues non-null, sorted ascending list of values
     * @param percentile percentile rank between 0.0 and 100.0 (inclusive)
     * @return the calculated percentile value
     * @throws IllegalArgumentException if the list is empty or percentile is out of [0, 100]
     */
    public static double calculatePercentile(List<Long> sortedValues, double percentile) {
        Objects.requireNonNull(sortedValues, "sortedValues must not be null");
        if (sortedValues.isEmpty()) {
            throw new IllegalArgumentException("Cannot compute percentile on an empty list");
        }
        if (percentile < 0.0 || percentile > 100.0) {
            throw new IllegalArgumentException("Percentile must be between 0.0 and 100.0, got: " + percentile);
        }

        int n = sortedValues.size();
        if (n == 1) {
            return sortedValues.get(0).doubleValue();
        }

        double index = (percentile / 100.0) * (n - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);

        if (lower == upper || lower >= n - 1) {
            return sortedValues.get(Math.min(lower, n - 1)).doubleValue();
        }

        double fraction = index - lower;
        double lowerVal = sortedValues.get(lower);
        double upperVal = sortedValues.get(upper);

        return lowerVal + fraction * (upperVal - lowerVal);
    }
}
