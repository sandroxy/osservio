package com.sandrox.osservio.store;

/** Independent retention and admission limits for explicit problem snapshots. */
public final class IncidentPersistenceOptions {
    public final int retainedIncidents;
    public final int retainedDiagnostics;
    public final long maximumAgeMillis;
    public final int textBytesPerIncident;
    public final int databaseByteLimit;
    public final int pendingTextByteLimit;
    public IncidentPersistenceOptions() { this(10, 604800000L, 131072, 16777216, 524288); }
    public IncidentPersistenceOptions(int retainedIncidents, long maximumAgeMillis, int textBytesPerIncident,
                                      int databaseByteLimit, int pendingTextByteLimit) {
        this(retainedIncidents, maximumAgeMillis, textBytesPerIncident, databaseByteLimit, pendingTextByteLimit, 10);
    }
    public IncidentPersistenceOptions(int retainedIncidents, long maximumAgeMillis, int textBytesPerIncident,
                                      int databaseByteLimit, int pendingTextByteLimit, int retainedDiagnostics) {
        if (retainedIncidents < 1 || retainedIncidents > 16 || maximumAgeMillis < 60000 || maximumAgeMillis > 2592000000L
            || textBytesPerIncident < 64 || textBytesPerIncident > 2097152 || databaseByteLimit < 1048576 || databaseByteLimit > 67108864
            || pendingTextByteLimit < 64 || pendingTextByteLimit > 4194304 || retainedDiagnostics < 1 || retainedDiagnostics > 16)
            throw new IllegalArgumentException("Invalid incident persistence limit");
        this.retainedIncidents = retainedIncidents; this.maximumAgeMillis = maximumAgeMillis; this.textBytesPerIncident = textBytesPerIncident;
        this.databaseByteLimit = databaseByteLimit; this.pendingTextByteLimit = pendingTextByteLimit;
        this.retainedDiagnostics = retainedDiagnostics;
    }
}
