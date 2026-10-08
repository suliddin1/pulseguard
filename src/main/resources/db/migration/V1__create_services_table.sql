CREATE TABLE IF NOT EXISTS services (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    url VARCHAR(2048) NOT NULL,
    check_interval_seconds INTEGER NOT NULL DEFAULT 60,
    timeout_ms INTEGER NOT NULL DEFAULT 5000,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_services_name UNIQUE (name),
    CONSTRAINT chk_services_check_interval CHECK (check_interval_seconds >= 5 AND check_interval_seconds <= 86400),
    CONSTRAINT chk_services_timeout CHECK (timeout_ms >= 500 AND timeout_ms <= 60000)
);

CREATE INDEX idx_services_status ON services(status);
CREATE INDEX idx_services_enabled ON services(enabled);
CREATE INDEX idx_services_created_at ON services(created_at);
