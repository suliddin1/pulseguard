package com.pulseguard.notification.channel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseguard.notification.config.NotificationProperties;
import com.pulseguard.notification.http.OutboundHttpClient;
import com.pulseguard.notification.model.NotificationChannelType;
import com.pulseguard.notification.payload.NotificationPayload;
import com.pulseguard.notification.security.DestinationNotAllowedException;
import com.pulseguard.notification.security.PermanentDeliveryException;
import com.pulseguard.notification.security.Redactor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Slack Incoming Webhook channel. Slack has no idempotency mechanism, so an at-least-once redelivery can
 * produce a duplicate message (documented in the README).
 */
@Component
public class SlackNotificationChannel extends AbstractHttpNotificationChannel {

    /** Hosts Slack serves Incoming Webhooks from. */
    public static final Set<String> SLACK_HOSTS = Set.of("hooks.slack.com", "hooks.slack-gov.com");

    private static final int MAX_DETAILS_CHARS = 500;

    private final NotificationProperties properties;
    private final ObjectMapper objectMapper;

    public SlackNotificationChannel(
            NotificationProperties properties,
            OutboundHttpClient httpClient,
            Redactor redactor,
            ObjectMapper objectMapper
    ) {
        super(httpClient, redactor);
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public NotificationChannelType type() {
        return NotificationChannelType.SLACK;
    }

    @Override
    public boolean isEnabled() {
        return properties.getSlack().isEnabled();
    }

    @Override
    public DeliveryResult deliver(NotificationMessage message) {
        try {
            URI uri = parse(properties.getSlack().getWebhookUrl());
            requireSlackHost(uri, properties.getSecurity().isAllowUnsafeDestinations());

            byte[] body = objectMapper.writeValueAsBytes(Map.of("text", renderText(message.payload())));
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json; charset=utf-8");
            headers.put("User-Agent", "PulseGuard-Slack/1");
            return post(uri, headers, body);
        } catch (PermanentDeliveryException ex) {
            return DeliveryResult.permanent(null, redactor().describe(ex));
        } catch (JsonProcessingException ex) {
            return DeliveryResult.permanent(null, "payload_serialization_failed");
        } catch (RuntimeException ex) {
            return DeliveryResult.permanent(null, redactor().describe(ex));
        }
    }

    /** Slack webhooks must point at Slack, otherwise this channel would be a generic request forwarder. */
    public static void requireSlackHost(URI uri, boolean allowUnsafe) throws DestinationNotAllowedException {
        if (allowUnsafe) {
            return;
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!SLACK_HOSTS.contains(host)) {
            throw new DestinationNotAllowedException("slack webhook host must be hooks.slack.com or hooks.slack-gov.com");
        }
    }

    static String renderText(NotificationPayload payload) {
        NotificationPayload.IncidentInfo incident = payload.incident();
        String icon;
        String title;
        switch (payload.eventType()) {
            case INCIDENT_OPENED -> {
                icon = ":rotating_light:";
                title = "Incident opened";
            }
            case INCIDENT_OCCURRENCE -> {
                icon = ":warning:";
                title = "Incident ongoing";
            }
            case INCIDENT_RESOLVED -> {
                icon = ":white_check_mark:";
                title = "Incident resolved";
            }
            default -> {
                icon = ":information_source:";
                title = "Incident update";
            }
        }
        StringBuilder text = new StringBuilder();
        text.append(icon).append(" *").append(title).append("* \u2014 ")
                .append(escape(payload.service().name())).append('\n');
        text.append(escape(incident.summary())).append('\n');
        text.append("Severity: ").append(incident.severity())
                .append(" | Type: ").append(incident.type())
                .append(" | Occurrences: ").append(incident.occurrenceCount());
        if (incident.details() != null && !incident.details().isBlank()) {
            String details = incident.details().length() > MAX_DETAILS_CHARS
                    ? incident.details().substring(0, MAX_DETAILS_CHARS) + "..."
                    : incident.details();
            text.append('\n').append("> ").append(escape(details).replace("\n", "\n> "));
        }
        return text.toString();
    }

    /** Slack mrkdwn requires these three characters to be HTML-escaped. */
    static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
