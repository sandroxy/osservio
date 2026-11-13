ALTER TABLE incident_logs ADD COLUMN details_state TEXT;
ALTER TABLE incident_logs ADD COLUMN details_blob TEXT;
ALTER TABLE incident_logs ADD COLUMN details_bytes INTEGER NOT NULL DEFAULT 0;
UPDATE incident_windows SET payload_evicted=0 WHERE kind='logs' AND payload_evicted IS NULL;
PRAGMA user_version=8;
