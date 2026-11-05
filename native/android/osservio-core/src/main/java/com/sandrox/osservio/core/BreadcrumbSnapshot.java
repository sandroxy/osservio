package com.sandrox.osservio.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BreadcrumbSnapshot {
    public final List<BreadcrumbEvent> events;
    public final int retainedTextBytes;
    public final long evictedCount;
    public final long revision;
    public BreadcrumbSnapshot(List<BreadcrumbEvent> events, int retainedTextBytes, long evictedCount, long revision) {
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
        this.retainedTextBytes = retainedTextBytes; this.evictedCount = evictedCount; this.revision = revision;
    }
}
