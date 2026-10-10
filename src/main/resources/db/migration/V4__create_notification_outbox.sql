-- Transactional outbox for notification delivery.
--
-- One row = one (event, channel) delivery. Rows are written in the same transaction as the incident
-- state change that produced them. incident_id / service_id are intentionally NOT foreign keys: an
-- outbox row is a self-contained snapshot (payload) that must survive cascade deletes so that
-- dead-letter diagnostics are not silently destroyed together with the incident.
CREATE TABLE IF NOT EXISTS notification_outbox (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    channel VARCHAR(30) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    incident_id UUID NOT NULL,
    service_id UUID NOT NULL,
    payload VARCHAR(4000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_token VARCHAR(64),
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(1000),
    last_http_status INTEGER,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_notification_outbox_event_channel UNIQUE (event_id, channel),
    CONSTRAINT chk_notification_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'DELIVERED', 'DEAD')),
    CONSTRAINT chk_notification_outbox_attempts CHECK (attempt_count >= 0),
    CONSTRAINT chk_notification_outbox_channel CHECK (channel IN ('WEBHOOK', 'SLACK')),
    CONSTRAINT chk_notification_outbox_event_type CHECK (event_type IN ('INCIDENT_OPENED', 'INCIDENT_OCCURRENCE', 'INCIDENT_RESOLVED'))
);

-- Claim scan: PENDING rows that are due, and PROCESSING rows whose lease expired.
CREATE INDEX idx_notification_outbox_due ON notification_outbox(status, next_attempt_at);
CREATE INDEX idx_notification_outbox_lease ON notification_outbox(status, lease_expires_at);
-- Occurrence rate-limit lookup and per-incident inspection.
CREATE INDEX idx_notification_outbox_incident ON notification_outbox(incident_id, event_type, created_at);
CREATE INDEX idx_notification_outbox_created_at ON notification_outbox(created_at);

-- Append-only record of every delivery attempt and its outcome.
CREATE TABLE IF NOT EXISTS notification_delivery_attempts (
    id UUID PRIMARY KEY,
    outbox_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL,
    attempted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    duration_ms BIGINT NOT NULL,
    outcome VARCHAR(30) NOT NULL,
    http_status INTEGER,
    error_message VARCHAR(1000),
    CONSTRAINT fk_notification_attempts_outbox FOREIGN KEY (outbox_id) REFERENCES notification_outbox(id) ON DELETE CASCADE,
    CONSTRAINT uk_notification_attempts_outbox_number UNIQUE (outbox_id, attempt_number),
    CONSTRAINT chk_notification_attempts_outcome CHECK (outcome IN ('SUCCESS', 'RETRYABLE_FAILURE', 'PERMANENT_FAILURE')),
    CONSTRAINT chk_notification_attempts_duration CHECK (duration_ms >= 0)
);

CREATE INDEX idx_notification_attempts_outbox ON notification_delivery_attempts(outbox_id, attempt_number);
