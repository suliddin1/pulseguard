package com.pulseguard.notification.model;

/**
 * Incident lifecycle events that produce notifications.
 *
 * <p>There is deliberately no severity-escalation event: {@code Incident.severity} is assigned at creation
 * and the domain model has no transition that changes it, so such an event could never be generated truthfully.
 */
public enum NotificationEventType {
    INCIDENT_OPENED,
    INCIDENT_OCCURRENCE,
    INCIDENT_RESOLVED
}
