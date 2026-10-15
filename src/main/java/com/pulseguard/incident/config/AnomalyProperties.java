package com.pulseguard.incident.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "pulseguard.anomaly")
public class AnomalyProperties {

    /**
     * Master switch for the statistical anomaly detection engine.
     */
    private boolean enabled = true;

    /**
     * Observation window duration to compute rolling statistics over.
     */
    private Duration observationWindow = Duration.ofMinutes(15);

    /**
     * Baseline window duration (strictly preceding the observation window).
     */
    private Duration baselineWindow = Duration.ofHours(1);

    /**
     * Minimum number of health check samples required in the window to perform evaluation.
     */
    private int minSamples = 10;

    /**
     * Latency regression multiplier: current p95 must exceed baseline p95 by this factor.
     */
    private double latencyRegressionMultiplier = 2.0;

    /**
     * Minimum absolute latency (ms) for an anomaly to be triggered via regression multiplier.
     * Prevents false alerts on trivial baseline increases (e.g., 5ms -> 12ms).
     */
    private long minAnomalyLatencyMs = 200;

    /**
     * Hard absolute p95 latency threshold (ms) that flags high latency regardless of baseline.
     */
    private long latencyThresholdMs = 2000;

    /**
     * Critical latency multiplier over baseline p95 for immediate escalation to CRITICAL.
     */
    private double criticalLatencyMultiplier = 3.5;

    /**
     * Hard absolute p95 critical latency threshold (ms) for immediate escalation to CRITICAL.
     */
    private long criticalLatencyThresholdMs = 5000;

    /**
     * Rolling error rate threshold (0.0 to 1.0) above which an anomaly is triggered.
     */
    private double errorRateThreshold = 0.25;

    /**
     * Critical rolling error rate threshold (0.0 to 1.0) for immediate escalation to CRITICAL.
     */
    private double criticalErrorRateThreshold = 0.60;

    /**
     * Consecutive anomalous evaluations required before creating an incident (anti-flapping).
     */
    private int consecutiveBreachesThreshold = 3;

    /**
     * Consecutive normal evaluations required before resolving an open anomaly incident.
     */
    private int consecutiveSuccessesThreshold = 3;

    /**
     * Consecutive anomalous evaluations required to escalate an incident to CRITICAL severity.
     */
    private int escalationConsecutiveBreachesThreshold = 5;

    /**
     * Upper bound on historical check rows loaded per query to prevent memory exhaustion.
     */
    private int maxHistoryChecks = 500;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getObservationWindow() {
        return observationWindow;
    }

    public void setObservationWindow(Duration observationWindow) {
        if (observationWindow != null && !observationWindow.isNegative() && !observationWindow.isZero()) {
            this.observationWindow = observationWindow;
        }
    }

    public Duration getBaselineWindow() {
        return baselineWindow;
    }

    public void setBaselineWindow(Duration baselineWindow) {
        if (baselineWindow != null && !baselineWindow.isNegative() && !baselineWindow.isZero()) {
            this.baselineWindow = baselineWindow;
        }
    }

    public int getMinSamples() {
        return minSamples;
    }

    public void setMinSamples(int minSamples) {
        this.minSamples = Math.max(1, minSamples);
    }

    public double getLatencyRegressionMultiplier() {
        return latencyRegressionMultiplier;
    }

    public void setLatencyRegressionMultiplier(double latencyRegressionMultiplier) {
        this.latencyRegressionMultiplier = Math.max(1.0, latencyRegressionMultiplier);
    }

    public long getMinAnomalyLatencyMs() {
        return minAnomalyLatencyMs;
    }

    public void setMinAnomalyLatencyMs(long minAnomalyLatencyMs) {
        this.minAnomalyLatencyMs = Math.max(0, minAnomalyLatencyMs);
    }

    public long getLatencyThresholdMs() {
        return latencyThresholdMs;
    }

    public void setLatencyThresholdMs(long latencyThresholdMs) {
        this.latencyThresholdMs = Math.max(0, latencyThresholdMs);
    }

    public double getCriticalLatencyMultiplier() {
        return criticalLatencyMultiplier;
    }

    public void setCriticalLatencyMultiplier(double criticalLatencyMultiplier) {
        this.criticalLatencyMultiplier = Math.max(1.0, criticalLatencyMultiplier);
    }

    public long getCriticalLatencyThresholdMs() {
        return criticalLatencyThresholdMs;
    }

    public void setCriticalLatencyThresholdMs(long criticalLatencyThresholdMs) {
        this.criticalLatencyThresholdMs = Math.max(0, criticalLatencyThresholdMs);
    }

    public double getErrorRateThreshold() {
        return errorRateThreshold;
    }

    public void setErrorRateThreshold(double errorRateThreshold) {
        this.errorRateThreshold = Math.max(0.0, Math.min(1.0, errorRateThreshold));
    }

    public double getCriticalErrorRateThreshold() {
        return criticalErrorRateThreshold;
    }

    public void setCriticalErrorRateThreshold(double criticalErrorRateThreshold) {
        this.criticalErrorRateThreshold = Math.max(0.0, Math.min(1.0, criticalErrorRateThreshold));
    }

    public int getConsecutiveBreachesThreshold() {
        return consecutiveBreachesThreshold;
    }

    public void setConsecutiveBreachesThreshold(int consecutiveBreachesThreshold) {
        this.consecutiveBreachesThreshold = Math.max(1, consecutiveBreachesThreshold);
    }

    public int getConsecutiveSuccessesThreshold() {
        return consecutiveSuccessesThreshold;
    }

    public void setConsecutiveSuccessesThreshold(int consecutiveSuccessesThreshold) {
        this.consecutiveSuccessesThreshold = Math.max(1, consecutiveSuccessesThreshold);
    }

    public int getEscalationConsecutiveBreachesThreshold() {
        return escalationConsecutiveBreachesThreshold;
    }

    public void setEscalationConsecutiveBreachesThreshold(int escalationConsecutiveBreachesThreshold) {
        this.escalationConsecutiveBreachesThreshold = Math.max(1, escalationConsecutiveBreachesThreshold);
    }

    public int getMaxHistoryChecks() {
        return maxHistoryChecks;
    }

    public void setMaxHistoryChecks(int maxHistoryChecks) {
        this.maxHistoryChecks = Math.max(10, maxHistoryChecks);
    }
}
