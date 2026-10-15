-- Migration V5: Add INCIDENT_ESCALATED to notification_outbox event_type check constraint.
-- Allows adaptive alerting to dispatch notifications when incident severity escalates from WARNING to CRITICAL.

ALTER TABLE notification_outbox DROP CONSTRAINT chk_notification_outbox_event_type;

ALTER TABLE notification_outbox ADD CONSTRAINT chk_notification_outbox_event_type
    CHECK (event_type IN ('INCIDENT_OPENED', 'INCIDENT_OCCURRENCE', 'INCIDENT_RESOLVED', 'INCIDENT_ESCALATED'));
