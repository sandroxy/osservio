CREATE TABLE incident_windows (incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    kind TEXT NOT NULL, observed_at INTEGER NOT NULL, event_count INTEGER NOT NULL, source_count INTEGER NOT NULL,
    source_evicted INTEGER NOT NULL, payload_evicted INTEGER, text_bytes INTEGER NOT NULL, payload_bytes INTEGER NOT NULL,
    PRIMARY KEY(incident_id, kind));
CREATE TABLE incident_logs (incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    position INTEGER NOT NULL, session_id TEXT NOT NULL COLLATE NOCASE, sequence INTEGER NOT NULL,
    timestamp INTEGER NOT NULL, level TEXT NOT NULL, category TEXT NOT NULL, message TEXT NOT NULL,
    private INTEGER NOT NULL, truncated INTEGER NOT NULL, PRIMARY KEY(incident_id, position), UNIQUE(incident_id, session_id, sequence));
CREATE TABLE incident_http (incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    position INTEGER NOT NULL, id TEXT NOT NULL COLLATE NOCASE, session_id TEXT NOT NULL, sequence INTEGER NOT NULL,
    revision INTEGER NOT NULL, timestamp INTEGER NOT NULL, method TEXT NOT NULL, source TEXT NOT NULL,
    url TEXT NOT NULL, response_url TEXT, headers_limited INTEGER NOT NULL, status_code INTEGER, state TEXT NOT NULL,
    failure TEXT, duration TEXT, request_state TEXT NOT NULL, request_blob TEXT, request_bytes INTEGER NOT NULL,
    response_state TEXT NOT NULL, response_blob TEXT, response_bytes INTEGER NOT NULL,
    PRIMARY KEY(incident_id, position), UNIQUE(incident_id, id));
CREATE TABLE incident_websocket (incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    position INTEGER NOT NULL, id TEXT NOT NULL COLLATE NOCASE, connection_id TEXT NOT NULL, session_id TEXT NOT NULL,
    sequence INTEGER NOT NULL, timestamp INTEGER NOT NULL, url TEXT NOT NULL, source TEXT NOT NULL, kind TEXT NOT NULL,
    headers_limited INTEGER NOT NULL, status_code INTEGER, close_code INTEGER, failure TEXT, direction TEXT,
    outcome TEXT, related_message TEXT, payload_state TEXT, payload_blob TEXT, payload_count INTEGER,
    PRIMARY KEY(incident_id, position), UNIQUE(incident_id, id));
CREATE TABLE incident_headers (incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    family TEXT NOT NULL, event_position INTEGER NOT NULL, scope TEXT NOT NULL, position INTEGER NOT NULL,
    name TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(incident_id, family, event_position, scope, position));
CREATE TABLE incident_payloads (incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    family TEXT NOT NULL, id TEXT NOT NULL COLLATE NOCASE, state TEXT NOT NULL, text TEXT,
    PRIMARY KEY(incident_id, family, id));
INSERT INTO incident_windows VALUES ('12345678-1234-1234-1234-123456789abc', 'logs', 200, 1, 4, 7, NULL, 19, 0);
INSERT INTO incident_logs VALUES ('12345678-1234-1234-1234-123456789abc', 0,
    '42345678-1234-1234-1234-123456789abc', 9007199254740993, -100, 'info', 'legacy.log', '[private]', 1, 0);
UPDATE incidents SET text_bytes=59;
PRAGMA user_version=2;
