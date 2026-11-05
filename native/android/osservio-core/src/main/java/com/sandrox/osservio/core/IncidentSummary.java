package com.sandrox.osservio.core;

/** Lightweight metadata for a committed manual snapshot. Attachments are loaded on request. */
public final class IncidentSummary {
    public final String id;
    public final long timestampMillis;
    public final IncidentSnapshot.Note note;
    public final boolean hasEnvironment;
    /** Null means not attached; zero means an attached empty history. */
    public final Integer breadcrumbCount;
    public IncidentSummary(String id, long timestampMillis, IncidentSnapshot.Note note, boolean hasEnvironment, Integer breadcrumbCount) {
        this.id = id; this.timestampMillis = timestampMillis; this.note = note;
        this.hasEnvironment = hasEnvironment; this.breadcrumbCount = breadcrumbCount;
    }
}
