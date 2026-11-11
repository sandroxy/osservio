ALTER TABLE incident_diagnostics ADD COLUMN tombstone_state TEXT;
CREATE TABLE android_tombstones (incident_id TEXT PRIMARY KEY NOT NULL REFERENCES incident_diagnostics(incident_id) ON DELETE CASCADE,
    architecture INTEGER NOT NULL, signal_number INTEGER NOT NULL, signal_code INTEGER NOT NULL, thread_id INTEGER NOT NULL,
    fault_address TEXT, frame_count INTEGER NOT NULL);
CREATE TABLE android_tombstone_frames (incident_id TEXT NOT NULL REFERENCES android_tombstones(incident_id) ON DELETE CASCADE,
    position INTEGER NOT NULL, pc TEXT NOT NULL, relative_pc TEXT NOT NULL, build_id TEXT, PRIMARY KEY(incident_id, position));
PRAGMA user_version=6;
