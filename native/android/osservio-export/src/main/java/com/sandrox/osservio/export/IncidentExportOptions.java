package com.sandrox.osservio.export;

/** Completed-cache bounds. One additional in-flight ZIP may occupy up to MAXIMUM_FILE_BYTES. */
public final class IncidentExportOptions {
    public static final long MAXIMUM_FILE_BYTES = 17L * 1048576;
    public final int fileCount;
    public final long cacheBytes;
    public final long maximumAgeMillis;
    public IncidentExportOptions() { this(4, 32L * 1048576, 86400000L); }
    public IncidentExportOptions(int fileCount, long cacheBytes, long maximumAgeMillis) {
        if (fileCount < 1 || fileCount > 16 || cacheBytes < 1048576 || cacheBytes > 134217728
                || maximumAgeMillis < 60000 || maximumAgeMillis > 604800000)
            throw new IllegalArgumentException("Invalid export cache limits");
        this.fileCount = fileCount; this.cacheBytes = cacheBytes; this.maximumAgeMillis = maximumAgeMillis;
    }
}
