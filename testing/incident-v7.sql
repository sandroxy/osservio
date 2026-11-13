ALTER TABLE incident_http ADD COLUMN request_processed INTEGER NOT NULL DEFAULT 0;
ALTER TABLE incident_http ADD COLUMN response_processed INTEGER NOT NULL DEFAULT 0;
ALTER TABLE incident_websocket ADD COLUMN payload_processed INTEGER NOT NULL DEFAULT 0;
PRAGMA user_version=7;
