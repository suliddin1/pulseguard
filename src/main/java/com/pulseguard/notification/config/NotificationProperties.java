package com.pulseguard.notification.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Configuration for the notification outbox, dispatcher and channels ({@code pulseguard.notifications.*}).
 *
 * <p>Secrets (webhook URLs, HMAC secret) live in nested {@link Webhook} / {@link Slack} objects. This class
 * and its nested classes intentionally do not override {@code toString()} with field values, so they cannot
 * leak secrets if a properties object is ever logged.
 */
@Validated
@ConfigurationProperties(prefix = "pulseguard.notifications")
public class NotificationProperties {

    @Valid
    @NotNull
    private Dispatcher dispatcher = new Dispatcher();

    @Valid
    @NotNull
    private Retry retry = new Retry();

    @Valid
    @NotNull
    private Http http = new Http();

    @Valid
    @NotNull
    private RateLimit rateLimit = new RateLimit();

    @Valid
    @NotNull
    private Occurrence occurrence = new Occurrence();

    @Valid
    @NotNull
    private Security security = new Security();

    @Valid
    @NotNull
    private Webhook webhook = new Webhook();

    @Valid
    @NotNull
    private Slack slack = new Slack();

    public Dispatcher getDispatcher() {
        return dispatcher;
    }

    public void setDispatcher(Dispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    public Http getHttp() {
        return http;
    }

    public void setHttp(Http http) {
        this.http = http;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public Occurrence getOccurrence() {
        return occurrence;
    }

    public void setOccurrence(Occurrence occurrence) {
        this.occurrence = occurrence;
    }

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public Webhook getWebhook() {
        return webhook;
    }

    public void setWebhook(Webhook webhook) {
        this.webhook = webhook;
    }

    public Slack getSlack() {
        return slack;
    }

    public void setSlack(Slack slack) {
        this.slack = slack;
    }

    /** Background dispatcher: polling, concurrency and lease settings. */
    public static class Dispatcher {

        /** Whether the background dispatcher polls the outbox. Events are still enqueued when disabled. */
        private boolean enabled = true;

        @Min(100)
        private long pollIntervalMs = 2000;

        @Min(0)
        private long initialDelayMs = 3000;

        /** Maximum rows claimed per poll. */
        @Min(1)
        @Max(500)
        private int batchSize = 20;

        /** Maximum deliveries in flight at once (executor pool size). */
        @Min(1)
        @Max(64)
        private int maxConcurrentDeliveries = 4;

        /** Bounded executor queue; claims never exceed free capacity, this is a safety margin only. */
        @Min(0)
        @Max(1000)
        private int queueCapacity = 4;

        /** How long a claim is valid. Must exceed the worst-case delivery time (total HTTP timeout). */
        @NotNull
        private Duration leaseDuration = Duration.ofMinutes(2);

        @Min(0)
        @Max(300)
        private int terminationTimeoutSeconds = 30;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public long getInitialDelayMs() {
            return initialDelayMs;
        }

        public void setInitialDelayMs(long initialDelayMs) {
            this.initialDelayMs = initialDelayMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMaxConcurrentDeliveries() {
            return maxConcurrentDeliveries;
        }

        public void setMaxConcurrentDeliveries(int maxConcurrentDeliveries) {
            this.maxConcurrentDeliveries = maxConcurrentDeliveries;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public Duration getLeaseDuration() {
            return leaseDuration;
        }

        public void setLeaseDuration(Duration leaseDuration) {
            this.leaseDuration = leaseDuration;
        }

        public int getTerminationTimeoutSeconds() {
            return terminationTimeoutSeconds;
        }

        public void setTerminationTimeoutSeconds(int terminationTimeoutSeconds) {
            this.terminationTimeoutSeconds = terminationTimeoutSeconds;
        }
    }

    /** Retry and exponential backoff policy. */
    public static class Retry {

        /** Total delivery attempts (including the first) before a row becomes DEAD. */
        @Min(1)
        @Max(50)
        private int maxAttempts = 6;

        @NotNull
        private Duration initialBackoff = Duration.ofSeconds(5);

        @NotNull
        private Duration maxBackoff = Duration.ofMinutes(10);

        /** Symmetric jitter fraction: delay is multiplied by a random factor in [1 - j, 1 + j]. */
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double jitter = 0.2;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getInitialBackoff() {
            return initialBackoff;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }

        public Duration getMaxBackoff() {
            return maxBackoff;
        }

        public void setMaxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
        }

        public double getJitter() {
            return jitter;
        }

        public void setJitter(double jitter) {
            this.jitter = jitter;
        }
    }

    /** Outbound HTTP limits. */
    public static class Http {

        @NotNull
        private Duration connectTimeout = Duration.ofSeconds(3);

        @NotNull
        private Duration readTimeout = Duration.ofSeconds(5);

        /** Hard deadline for the whole request/response exchange. */
        @NotNull
        private Duration totalTimeout = Duration.ofSeconds(10);

        @Min(256)
        @Max(1_048_576)
        private int maxPayloadBytes = 16_384;

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public Duration getTotalTimeout() {
            return totalTimeout;
        }

        public void setTotalTimeout(Duration totalTimeout) {
            this.totalTimeout = totalTimeout;
        }

        public int getMaxPayloadBytes() {
            return maxPayloadBytes;
        }

        public void setMaxPayloadBytes(int maxPayloadBytes) {
            this.maxPayloadBytes = maxPayloadBytes;
        }
    }

    /** Per-channel, per-instance, in-memory token bucket. */
    public static class RateLimit {

        /** Deliveries per minute per channel on this instance. 0 disables rate limiting. */
        @Min(0)
        @Max(100_000)
        private int perMinute = 120;

        public int getPerMinute() {
            return perMinute;
        }

        public void setPerMinute(int perMinute) {
            this.perMinute = perMinute;
        }
    }

    /** Anti-spam policy for incident occurrence notifications. */
    public static class Occurrence {

        /** Notify on every Nth occurrence of an open incident. 0 disables occurrence notifications. */
        @Min(0)
        private int notifyEvery = 10;

        /** Minimum time between occurrence notifications for the same incident. */
        @NotNull
        private Duration minInterval = Duration.ofMinutes(10);

        public int getNotifyEvery() {
            return notifyEvery;
        }

        public void setNotifyEvery(int notifyEvery) {
            this.notifyEvery = notifyEvery;
        }

        public Duration getMinInterval() {
            return minInterval;
        }

        public void setMinInterval(Duration minInterval) {
            this.minInterval = minInterval;
        }
    }

    /** Destination security policy. */
    public static class Security {

        /**
         * DEV/TEST ONLY. Allows plain http and private/loopback/link-local destinations and non-Slack hosts
         * for the Slack channel. Never enable in production.
         */
        private boolean allowUnsafeDestinations = false;

        /** Optional exact-host allow-list applied to every destination. Empty means no host allow-list. */
        private Set<String> allowedHosts = new LinkedHashSet<>();

        public boolean isAllowUnsafeDestinations() {
            return allowUnsafeDestinations;
        }

        public void setAllowUnsafeDestinations(boolean allowUnsafeDestinations) {
            this.allowUnsafeDestinations = allowUnsafeDestinations;
        }

        public Set<String> getAllowedHosts() {
            return allowedHosts;
        }

        public void setAllowedHosts(Set<String> allowedHosts) {
            this.allowedHosts = allowedHosts == null ? new LinkedHashSet<>() : allowedHosts;
        }
    }

    /** Generic webhook channel. */
    public static class Webhook {

        private boolean enabled = false;

        /** Destination URL. Secret: never logged or returned by the API. */
        private String url = "";

        /** Optional HMAC-SHA256 signing secret. Secret: never logged or returned by the API. */
        private String secret = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url == null ? "" : url.trim();
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret == null ? "" : secret.trim();
        }
    }

    /** Slack Incoming Webhook channel. */
    public static class Slack {

        private boolean enabled = false;

        /** Incoming Webhook URL. Secret: the URL path is the credential. */
        private String webhookUrl = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getWebhookUrl() {
            return webhookUrl;
        }

        public void setWebhookUrl(String webhookUrl) {
            this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        }
    }
}
