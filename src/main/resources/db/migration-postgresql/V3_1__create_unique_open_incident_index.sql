CREATE UNIQUE INDEX IF NOT EXISTS uk_incidents_service_type_open 
ON incidents(service_id, incident_type) 
WHERE status = 'OPEN';
