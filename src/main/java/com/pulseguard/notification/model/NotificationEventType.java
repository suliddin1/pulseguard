package com.pulseguard.notification.model;

/**
 * Incident lifecycle events that produce notifications.
 */
public enum NotificationEventType {
    INCIDENT_OPENED,
    INCIDENT_OCCURRENCE,
    INCIDENT_RESOLVED,
    INCIDENT_ESCALATED
}
