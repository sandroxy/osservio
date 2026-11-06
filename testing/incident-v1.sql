PRAGMA application_id=0x4f535649;
PRAGMA user_version=1;
CREATE TABLE incidents (ordinal INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE COLLATE NOCASE,
    timestamp INTEGER NOT NULL, saved_at INTEGER NOT NULL, note TEXT, private INTEGER NOT NULL, truncated INTEGER NOT NULL,
    environment_platform TEXT, environment_time INTEGER, breadcrumb_count INTEGER, source_count INTEGER, source_evicted INTEGER, text_bytes INTEGER NOT NULL);
CREATE TABLE environment_facts (incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    key TEXT NOT NULL, type TEXT NOT NULL, text_value TEXT, number_value INTEGER, truncated INTEGER NOT NULL, PRIMARY KEY(incident_id, key));
CREATE TABLE incident_breadcrumbs (incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    position INTEGER NOT NULL, id TEXT NOT NULL COLLATE NOCASE, session_id TEXT NOT NULL, sequence INTEGER NOT NULL,
    timestamp INTEGER NOT NULL, elapsed TEXT, kind TEXT NOT NULL, label TEXT NOT NULL, message TEXT, private INTEGER NOT NULL,
    truncated INTEGER NOT NULL, value INTEGER, retention TEXT NOT NULL, PRIMARY KEY(incident_id, position), UNIQUE(incident_id, id));
INSERT INTO incidents VALUES (1, '12345678-1234-1234-1234-123456789abc', -17, 100, 'legacy', 0, 0, 'android', -23, 1, 3, 7, 40);
INSERT INTO environment_facts (incident_id, key, type, text_value, number_value, truncated) VALUES
    ('12345678-1234-1234-1234-123456789abc', 'osVersion', 'unavailable', NULL, NULL, 0),
    ('12345678-1234-1234-1234-123456789abc', 'appIdentifier', 'text', 'com.example.fixture', NULL, 0),
    ('12345678-1234-1234-1234-123456789abc', 'appVersion', 'unavailable', NULL, NULL, 0),
    ('12345678-1234-1234-1234-123456789abc', 'appBuild', 'unavailable', NULL, NULL, 0),
    ('12345678-1234-1234-1234-123456789abc', 'manufacturer', 'unavailable', NULL, NULL, 0),
    ('12345678-1234-1234-1234-123456789abc', 'deviceModel', 'unavailable', NULL, NULL, 0),
    ('12345678-1234-1234-1234-123456789abc', 'architecture', 'unavailable', NULL, NULL, 0),
    ('12345678-1234-1234-1234-123456789abc', 'physicalMemoryBytes', 'number', NULL, 9223372036854775807, 0),
    ('12345678-1234-1234-1234-123456789abc', 'processResidentBytes', 'unavailable', NULL, NULL, 0),
    ('12345678-1234-1234-1234-123456789abc', 'processProportionalBytes', 'unsupported', NULL, NULL, 0),
    ('12345678-1234-1234-1234-123456789abc', 'processFootprintBytes', 'unavailable', NULL, NULL, 0),
    ('12345678-1234-1234-1234-123456789abc', 'runtimeHeapBytes', 'unavailable', NULL, NULL, 0),
    ('12345678-1234-1234-1234-123456789abc', 'runtimeHeapLimitBytes', 'unavailable', NULL, NULL, 0),
    ('12345678-1234-1234-1234-123456789abc', 'processID', 'unavailable', NULL, NULL, 0),
    ('12345678-1234-1234-1234-123456789abc', 'batteryPercent', 'unavailable', NULL, NULL, 0),
    ('12345678-1234-1234-1234-123456789abc', 'lowPowerMode', 'flag', NULL, 0, 0),
    ('12345678-1234-1234-1234-123456789abc', 'thermalState', 'unavailable', NULL, NULL, 0);
INSERT INTO incident_breadcrumbs VALUES ('12345678-1234-1234-1234-123456789abc', 0,
    '22345678-1234-1234-1234-123456789abc', '32345678-1234-1234-1234-123456789abc', 9223372036854775807,
    -9223372036854775808, '9223372036854775807', 'hostMarker', 'legacy', '[private]', 1, 0, -9223372036854775808, 'restartRelevant');
