package com.pulseguard.notification.config;

import com.pulseguard.notification.channel.SlackNotificationChannel;
import com.pulseguard.notification.dispatcher.BackoffPolicy;
import com.pulseguard.notification.dispatcher.ChannelRateLimiter;
import com.pulseguard.notification.http.OutboundHttpClient;
import com.pulseguard.notification.http.SafeSocketHttpClient;
import com.pulseguard.notification.security.DestinationPolicy;
import com.pulseguard.notification.security.HostResolver;
import com.pulseguard.notification.security.Redactor;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.net.ssl.SSLSocketFactory;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NotificationConfiguration.class);

    private final NotificationProperties properties;

    public NotificationConfiguration(NotificationProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void validateOnStartup() {
        if (properties.getSecurity().isAllowUnsafeDestinations()) {
            log.warn("CRITICAL SECURITY WARNING: 'pulseguard.notifications.security.allow-unsafe-destinations' is ENABLED. "
                    + "Private/loopback addresses and plain HTTP are permitted. Never use this in production.");
        }

        DestinationPolicy policy = new DestinationPolicy(
                HostResolver.SYSTEM,
                properties.getSecurity().isAllowUnsafeDestinations(),
                properties.getSecurity().getAllowedHosts()
        );

        if (properties.getWebhook().isEnabled()) {
            String url = properties.getWebhook().getUrl();
            if (url.isBlank()) {
                throw new IllegalStateException("Webhook notification channel is enabled but no URL is configured");
            }
            try {
                URI uri = URI.create(url);
                policy.validateStatic(uri);
            } catch (Exception ex) {
                throw new IllegalStateException("Webhook URL violates outbound destination policy: " + ex.getMessage());
            }
        }

        if (properties.getSlack().isEnabled()) {
            String url = properties.getSlack().getWebhookUrl();
            if (url.isBlank()) {
                throw new IllegalStateException("Slack notification channel is enabled but no webhook URL is configured");
            }
            try {
                URI uri = URI.create(url);
                policy.validateStatic(uri);
                SlackNotificationChannel.requireSlackHost(uri, properties.getSecurity().isAllowUnsafeDestinations());
            } catch (Exception ex) {
                throw new IllegalStateException("Slack webhook URL violates destination policy: " + ex.getMessage());
            }
        }
    }

    @Bean(name = "notificationTaskExecutor")
    public ThreadPoolTaskExecutor notificationTaskExecutor(NotificationProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int maxConcurrency = Math.max(1, properties.getDispatcher().getMaxConcurrentDeliveries());
        int corePoolSize = Math.max(1, maxConcurrency / 2);

        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxConcurrency);
        executor.setQueueCapacity(properties.getDispatcher().getQueueCapacity());
        executor.setThreadNamePrefix("pg-notify-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.getDispatcher().getTerminationTimeoutSeconds());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    public HostResolver hostResolver() {
        return HostResolver.SYSTEM;
    }

    @Bean
    @ConditionalOnMissingBean
    public SSLSocketFactory sslSocketFactory() {
        return (SSLSocketFactory) SSLSocketFactory.getDefault();
    }

    @Bean
    public DestinationPolicy destinationPolicy(NotificationProperties properties, HostResolver hostResolver) {
        return new DestinationPolicy(
                hostResolver,
                properties.getSecurity().isAllowUnsafeDestinations(),
                properties.getSecurity().getAllowedHosts()
        );
    }

    @Bean
    public Redactor redactor(NotificationProperties properties) {
        List<String> secrets = new ArrayList<>();
        if (properties.getWebhook().getUrl() != null && !properties.getWebhook().getUrl().isBlank()) {
            secrets.add(properties.getWebhook().getUrl());
        }
        if (properties.getWebhook().getSecret() != null && !properties.getWebhook().getSecret().isBlank()) {
            secrets.add(properties.getWebhook().getSecret());
        }
        if (properties.getSlack().getWebhookUrl() != null && !properties.getSlack().getWebhookUrl().isBlank()) {
            secrets.add(properties.getSlack().getWebhookUrl());
        }
        return new Redactor(secrets);
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboundHttpClient outboundHttpClient(
            DestinationPolicy policy,
            NotificationProperties properties,
            SSLSocketFactory sslSocketFactory
    ) {
        return new SafeSocketHttpClient(
                policy,
                properties.getHttp().getConnectTimeout(),
                properties.getHttp().getReadTimeout(),
                properties.getHttp().getTotalTimeout(),
                properties.getHttp().getMaxPayloadBytes(),
                sslSocketFactory
        );
    }

    @Bean
    public BackoffPolicy backoffPolicy(NotificationProperties properties) {
        return new BackoffPolicy(
                properties.getRetry().getInitialBackoff(),
                properties.getRetry().getMaxBackoff(),
                properties.getRetry().getJitter(),
                ThreadLocalRandom.current()::nextDouble
        );
    }

    @Bean
    public ChannelRateLimiter channelRateLimiter(Clock clock, NotificationProperties properties) {
        return new ChannelRateLimiter(clock, properties.getRateLimit().getPerMinute());
    }
}
