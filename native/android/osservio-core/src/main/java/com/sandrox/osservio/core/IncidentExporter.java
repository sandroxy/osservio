package com.sandrox.osservio.core;

import java.io.File;
import java.util.Objects;

/** Explicit asynchronous file preparation from frozen evidence. No capture, archive mutation, or transport. */
public interface IncidentExporter {
    enum Error { BUSY, CLOSED, CANCELLED, TOO_LARGE, UNAVAILABLE, STORAGE_FULL, INVALID_RECORD }
    interface Task {
        /** Best effort before completion is finalized. Does not retract an already delivered file. */
        void cancel();
    }
    /** A completed temporary ZIP. Copy it to retain it independently of the exporter's cache. */
    final class PreparedFile {
        public final File file;
        public final long byteCount;
        public PreparedFile(File file, long byteCount) {
            this.file = Objects.requireNonNull(file, "file");
            if (!file.isAbsolute() || byteCount <= 0) throw new IllegalArgumentException("Invalid prepared export");
            this.byteCount = byteCount;
        }
    }
    interface Completion { void complete(PreparedFile file, Error error); }
    /** Completes once on any thread, including immediate rejection. Rejection returns null.
     * Success means a complete file is ready; it does not mean anything was sent. */
    Task prepare(IncidentSnapshot snapshot, Completion completion);
}
