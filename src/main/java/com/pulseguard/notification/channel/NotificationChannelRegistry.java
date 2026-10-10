package com.pulseguard.notification.channel;

import com.pulseguard.notification.model.NotificationChannelType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Lookup of available channel implementations by type. */
@Component
public class NotificationChannelRegistry {

    private final Map<NotificationChannelType, NotificationChannel> channels = new EnumMap<>(NotificationChannelType.class);

    public NotificationChannelRegistry(List<NotificationChannel> implementations) {
        for (NotificationChannel channel : implementations) {
            NotificationChannel previous = channels.put(channel.type(), channel);
            if (previous != null) {
                throw new IllegalStateException("Duplicate notification channel implementation for " + channel.type());
            }
        }
    }

    public Optional<NotificationChannel> find(NotificationChannelType type) {
        return Optional.ofNullable(channels.get(type));
    }

    public List<NotificationChannelType> enabledTypes() {
        return channels.values().stream().filter(NotificationChannel::isEnabled).map(NotificationChannel::type).toList();
    }
}
