package com.pulseguard.incident.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PercentileCalculatorTest {

    @Test
    @DisplayName("Single value returns that exact value for any percentile")
    void singleValue() {
        List<Long> values = List.of(42L);
        assertThat(PercentileCalculator.calculatePercentile(values, 0.0)).isEqualTo(42.0);
        assertThat(PercentileCalculator.calculatePercentile(values, 50.0)).isEqualTo(42.0);
        assertThat(PercentileCalculator.calculatePercentile(values, 95.0)).isEqualTo(42.0);
        assertThat(PercentileCalculator.calculatePercentile(values, 100.0)).isEqualTo(42.0);
    }

    @Test
    @DisplayName("Two values interpolate linearly")
    void twoValuesInterpolation() {
        List<Long> values = List.of(10L, 20L);
        assertThat(PercentileCalculator.calculatePercentile(values, 0.0)).isEqualTo(10.0);
        assertThat(PercentileCalculator.calculatePercentile(values, 50.0)).isEqualTo(15.0);
        assertThat(PercentileCalculator.calculatePercentile(values, 75.0)).isEqualTo(17.5);
        assertThat(PercentileCalculator.calculatePercentile(values, 100.0)).isEqualTo(20.0);
    }

    @Test
    @DisplayName("Known sample set matches standard linear interpolation")
    void knownSampleSet() {
        // 11 values from 0 to 100 with step 10: 0, 10, 20, ..., 100
        List<Long> values = new ArrayList<>();
        for (long i = 0; i <= 100; i += 10) {
            values.add(i);
        }
        assertThat(values).hasSize(11);

        // idx = (P/100) * 10
        // P = 0 -> idx = 0 -> 0.0
        // P = 50 -> idx = 5 -> 50.0
        // P = 90 -> idx = 9 -> 90.0
        // P = 95 -> idx = 9.5 -> lower=9 (val 90), upper=10 (val 100) -> 90 + 0.5 * 10 = 95.0
        // P = 99 -> idx = 9.9 -> 90 + 0.9 * 10 = 99.0
        assertThat(PercentileCalculator.calculatePercentile(values, 0.0)).isEqualTo(0.0);
        assertThat(PercentileCalculator.calculatePercentile(values, 50.0)).isEqualTo(50.0);
        assertThat(PercentileCalculator.calculatePercentile(values, 90.0)).isEqualTo(90.0);
        assertThat(PercentileCalculator.calculatePercentile(values, 95.0)).isEqualTo(95.0);
        assertThat(PercentileCalculator.calculatePercentile(values, 99.0)).isEqualTo(99.0);
        assertThat(PercentileCalculator.calculatePercentile(values, 100.0)).isEqualTo(100.0);
    }

    @Test
    @DisplayName("Sample set of 100 values (1 to 100) gives deterministic p95 and p99")
    void sampleHundredValues() {
        List<Long> values = new ArrayList<>();
        for (long i = 1; i <= 100; i++) {
            values.add(i);
        }
        // N = 100
        // p95: idx = 0.95 * 99 = 94.05. lower = 94 (value 95), upper = 95 (value 96). 95 + 0.05 * 1 = 95.05
        double p95 = PercentileCalculator.calculatePercentile(values, 95.0);
        assertThat(p95).isCloseTo(95.05, within(0.001));

        // p99: idx = 0.99 * 99 = 98.01. lower = 98 (value 99), upper = 99 (value 100). 99 + 0.01 * 1 = 99.01
        double p99 = PercentileCalculator.calculatePercentile(values, 99.0);
        assertThat(p99).isCloseTo(99.01, within(0.001));
    }

    @Test
    @DisplayName("All identical values return that constant value")
    void identicalValues() {
        List<Long> values = Collections.nCopies(20, 150L);
        assertThat(PercentileCalculator.calculatePercentile(values, 95.0)).isEqualTo(150.0);
        assertThat(PercentileCalculator.calculatePercentile(values, 99.0)).isEqualTo(150.0);
    }

    @Test
    @DisplayName("Empty list throws IllegalArgumentException")
    void emptyListThrows() {
        List<Long> empty = List.of();
        assertThatThrownBy(() -> PercentileCalculator.calculatePercentile(empty, 95.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("Percentile out of [0, 100] range or NaN throws IllegalArgumentException")
    void outOfRangeThrows() {
        List<Long> values = List.of(10L, 20L);
        assertThatThrownBy(() -> PercentileCalculator.calculatePercentile(values, -1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PercentileCalculator.calculatePercentile(values, 100.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PercentileCalculator.calculatePercentile(values, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
