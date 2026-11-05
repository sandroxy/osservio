package com.sandrox.osservio.core;

/** A recording run, not a capture session or proof of a crash. */
public final class RetainedRun {
    public final String id;
    public final EnvironmentSnapshot.Platform platform;
    public final long processId;
    public final long recordingStartedAtMillis;
    public final long lastRecordedAtMillis;
    public final int eventCount;
    public final long evictedEvents;
    public final boolean isCurrent;
    public RetainedRun(String id, EnvironmentSnapshot.Platform platform, long processId, long recordingStartedAtMillis,
                       long lastRecordedAtMillis, int eventCount, long evictedEvents, boolean isCurrent) {
        this.id = id; this.platform = platform; this.processId = processId;
        this.recordingStartedAtMillis = recordingStartedAtMillis; this.lastRecordedAtMillis = lastRecordedAtMillis;
        this.eventCount = eventCount; this.evictedEvents = evictedEvents; this.isCurrent = isCurrent;
    }
}
