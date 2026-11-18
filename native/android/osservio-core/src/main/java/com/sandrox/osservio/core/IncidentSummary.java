package com.sandrox.osservio.core;

/** Lightweight metadata for a committed snapshot. Attachments are loaded on request. */
public final class IncidentSummary {
    /** Source-window records left out at capture. Null means unreported. Earlier source evictions are separate. */
    public static final class OmittedCounts {
        private static final OmittedCounts UNKNOWN = new OmittedCounts(null, null, null, null);
        public final Integer breadcrumbs, logs, http, webSockets;
        public OmittedCounts(Integer breadcrumbs, Integer logs, Integer http, Integer webSockets) {
            this.breadcrumbs = breadcrumbs; this.logs = logs; this.http = http; this.webSockets = webSockets;
        }
    }
    public final OmittedCounts omitted;
    public final String id;
    public final long timestampMillis;
    public final IncidentSnapshot.Note note;
    public final boolean hasEnvironment;
    /** Null means not attached; zero means the attached window retained no records. */
    public final Integer breadcrumbCount;
    public final Integer logCount;
    public final Integer httpCount;
    public final Integer webSocketCount;
    public final SystemDiagnostic.Kind diagnosticKind;
    public IncidentSummary(String id, long timestampMillis, IncidentSnapshot.Note note, boolean hasEnvironment, Integer breadcrumbCount) {
        this(id, timestampMillis, note, hasEnvironment, breadcrumbCount, null, null, null);
    }
    public IncidentSummary(String id, long timestampMillis, IncidentSnapshot.Note note, boolean hasEnvironment, Integer breadcrumbCount,
                           Integer logCount, Integer httpCount, Integer webSocketCount) {
        this(id, timestampMillis, note, hasEnvironment, breadcrumbCount, logCount, httpCount, webSocketCount, null);
    }
    public IncidentSummary(String id, long timestampMillis, IncidentSnapshot.Note note, boolean hasEnvironment, Integer breadcrumbCount,
                           Integer logCount, Integer httpCount, Integer webSocketCount, SystemDiagnostic.Kind diagnosticKind) {
        this(id, timestampMillis, note, hasEnvironment, breadcrumbCount, logCount, httpCount, webSocketCount, diagnosticKind, OmittedCounts.UNKNOWN);
    }
    public IncidentSummary(String id, long timestampMillis, IncidentSnapshot.Note note, boolean hasEnvironment, Integer breadcrumbCount,
                           Integer logCount, Integer httpCount, Integer webSocketCount, SystemDiagnostic.Kind diagnosticKind, OmittedCounts omitted) {
        this.omitted = java.util.Objects.requireNonNull(omitted);
        this.id = id; this.timestampMillis = timestampMillis; this.note = note;
        this.hasEnvironment = hasEnvironment; this.breadcrumbCount = breadcrumbCount;
        this.logCount = logCount; this.httpCount = httpCount; this.webSocketCount = webSocketCount;
        this.diagnosticKind = diagnosticKind;
    }
}
