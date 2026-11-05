package com.sandrox.osservio.store;

public final class BreadcrumbPersistenceOptions {
    public final int recordsPerRun;
    public final int textBytesPerRun;
    public final int retainedRuns;
    public final long maximumAgeMillis;
    public final int databaseByteLimit;
    public final int pendingEventLimit;
    public final int pendingTextByteLimit;
    public final int flushIntervalMillis;
    public BreadcrumbPersistenceOptions() { this(200, 65536, 4, 604800000L, 4194304, 64, 131072, 1000); }
    public BreadcrumbPersistenceOptions(int recordsPerRun, int textBytesPerRun, int retainedRuns, long maximumAgeMillis,
                                        int databaseByteLimit, int pendingEventLimit, int pendingTextByteLimit, int flushIntervalMillis) {
        if (recordsPerRun < 1 || recordsPerRun > 2000 || textBytesPerRun < 64 || textBytesPerRun > 1048576
            || retainedRuns < 1 || retainedRuns > 16 || maximumAgeMillis < 60000 || maximumAgeMillis > 2592000000L
            || databaseByteLimit < 1048576 || databaseByteLimit > 67108864
            || pendingEventLimit < 1 || pendingEventLimit > 256 || pendingTextByteLimit < 64 || pendingTextByteLimit > 1048576
            || flushIntervalMillis < 100 || flushIntervalMillis > 5000) throw new IllegalArgumentException("Invalid breadcrumb persistence limits");
        this.recordsPerRun = recordsPerRun; this.textBytesPerRun = textBytesPerRun; this.retainedRuns = retainedRuns;
        this.maximumAgeMillis = maximumAgeMillis; this.databaseByteLimit = databaseByteLimit;
        this.pendingEventLimit = pendingEventLimit; this.pendingTextByteLimit = pendingTextByteLimit; this.flushIntervalMillis = flushIntervalMillis;
    }
}
