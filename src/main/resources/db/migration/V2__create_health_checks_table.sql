CREATE TABLE IF NOT EXISTS health_checks (
    id UUID PRIMARY KEY,
    service_id UUID NOT NULL,
    checked_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    response_time_ms BIGINT NOT NULL,
    http_status_code INTEGER,
    result VARCHAR(30) NOT NULL,
    error_message VARCHAR(1000),
    CONSTRAINT fk_health_checks_service FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE CASCADE,
    CONSTRAINT chk_health_checks_response_time CHECK (response_time_ms >= 0)
);

CREATE INDEX idx_health_checks_service_id ON health_checks(service_id);
CREATE INDEX idx_health_checks_checked_at ON health_checks(checked_at);
CREATE INDEX idx_health_checks_service_checked_at ON health_checks(service_id, checked_at DESC);
CREATE INDEX idx_health_checks_result ON health_checks(result);
