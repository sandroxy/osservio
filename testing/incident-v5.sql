CREATE TABLE signal_crashes (
    incident_id TEXT PRIMARY KEY NOT NULL REFERENCES incident_diagnostics(incident_id) ON DELETE CASCADE,
    report_id TEXT NOT NULL, signal_name TEXT NOT NULL, signal_code TEXT NOT NULL, fault_address TEXT NOT NULL,
    thread_number INTEGER, frame_count INTEGER NOT NULL, image_count INTEGER NOT NULL, image_cache_incomplete INTEGER NOT NULL
);
CREATE TABLE signal_crash_frames (
    incident_id TEXT NOT NULL REFERENCES signal_crashes(incident_id) ON DELETE CASCADE,
    position INTEGER NOT NULL, address TEXT NOT NULL, PRIMARY KEY(incident_id, position)
);
CREATE TABLE signal_crash_images (
    incident_id TEXT NOT NULL REFERENCES signal_crashes(incident_id) ON DELETE CASCADE,
    position INTEGER NOT NULL, base_address TEXT NOT NULL, size TEXT NOT NULL, uuid TEXT, cpu_type TEXT, cpu_subtype TEXT,
    PRIMARY KEY(incident_id, position)
);
INSERT INTO incidents(id,timestamp,saved_at,private,truncated,text_bytes,diagnostic_kind)
VALUES ('eeeeeeee-eeee-eeee-eeee-000000000002',1234,100,0,0,45,'nativeCrash');
INSERT INTO incident_diagnostics(incident_id,source,interval_start,interval_end)
VALUES ('eeeeeeee-eeee-eeee-eeee-000000000002','appleSignal',1000,1000);
INSERT INTO signal_crashes VALUES ('eeeeeeee-eeee-eeee-eeee-000000000002','12345678-1234-1234-1234-abcdef123456','SIGABRT','#0','0',NULL,0,0,0);
INSERT INTO incidents(id,timestamp,saved_at,private,truncated,text_bytes,diagnostic_kind)
VALUES ('eeeeeeee-eeee-eeee-eeee-000000000003',1234,100,0,0,0,'nativeCrash');
INSERT INTO incident_diagnostics(incident_id,source,interval_start,interval_end,reason,status,process_id,importance)
VALUES ('eeeeeeee-eeee-eeee-eeee-000000000003','androidExitInfo',1000,1000,5,6,123,100);
PRAGMA user_version=5;
