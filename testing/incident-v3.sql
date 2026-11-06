ALTER TABLE incidents ADD COLUMN diagnostic_kind TEXT;
CREATE TABLE incident_diagnostics (
    incident_id TEXT PRIMARY KEY NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    source TEXT NOT NULL, interval_start INTEGER NOT NULL, interval_end INTEGER NOT NULL,
    reason INTEGER, status INTEGER, process_id INTEGER, importance INTEGER,
    pss INTEGER, rss INTEGER, signal INTEGER, exception_type INTEGER, exception_code TEXT, hang_duration INTEGER
);
CREATE TABLE diagnostic_metadata (
    incident_id TEXT NOT NULL REFERENCES incident_diagnostics(incident_id) ON DELETE CASCADE,
    key TEXT NOT NULL, value TEXT NOT NULL, truncated INTEGER NOT NULL,
    PRIMARY KEY(incident_id, key)
);
INSERT INTO incidents(id,timestamp,saved_at,private,truncated,text_bytes,diagnostic_kind)
VALUES ('eeeeeeee-eeee-eeee-eeee-000000000001',123,100,0,0,20,'crash');
INSERT INTO incident_diagnostics(incident_id,source,interval_start,interval_end,signal,exception_type,exception_code)
VALUES ('eeeeeeee-eeee-eeee-eeee-000000000001','metricKit',90,100,11,9223372036854775807,'18446744073709551615');
PRAGMA user_version=3;
