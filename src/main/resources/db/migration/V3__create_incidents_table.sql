CREATE TABLE IF NOT EXISTS incidents (
    id UUID PRIMARY KEY,
    service_id UUID NOT NULL,
    incident_type VARCHAR(50) NOT NULL,
    severity VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    summary VARCHAR(255) NOT NULL,
    details VARCHAR(2000),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_occurrence_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    occurrence_count BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_incidents_service FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE CASCADE,
    CONSTRAINT chk_incidents_occurrence_count CHECK (occurrence_count >= 1),
    CONSTRAINT chk_incidents_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT chk_incidents_severity CHECK (severity IN ('WARNING', 'CRITICAL')),
    CONSTRAINT chk_incidents_type CHECK (incident_type IN ('SERVICE_UNAVAILABLE', 'HIGH_LATENCY', 'HIGH_ERROR_RATE'))
);

CREATE INDEX idx_incidents_service_id ON incidents(service_id);
CREATE INDEX idx_incidents_status ON incidents(status);
CREATE INDEX idx_incidents_severity ON incidents(severity);
CREATE INDEX idx_incidents_type ON incidents(incident_type);
CREATE INDEX idx_incidents_started_at ON incidents(started_at DESC);
CREATE INDEX idx_incidents_service_status ON incidents(service_id, status);
CREATE INDEX idx_incidents_service_started_at ON incidents(service_id, started_at DESC);
