package com.sandrox.osservio.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LogSnapshot {
    public final List<LogEvent> events;
    public final int retainedTextBytes;
    public final long evictedCount;
    public final long revision;
    public final int retainedDetailsBytes;
    public final long evictedDetails;

    public LogSnapshot(List<LogEvent> events, int retainedTextBytes, long evictedCount, long revision) {
        this(events, retainedTextBytes, evictedCount, revision, 0, 0);
    }
    public LogSnapshot(List<LogEvent> events, int retainedTextBytes, long evictedCount, long revision, int retainedDetailsBytes, long evictedDetails) {
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
        this.retainedTextBytes = retainedTextBytes;
        this.evictedCount = evictedCount;
        this.revision = revision;
        this.retainedDetailsBytes = retainedDetailsBytes; this.evictedDetails = evictedDetails;
    }
}
