package com.sandrox.osservio.core;

import java.io.File;
import java.net.URI;
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
        /** Read-only sharing address, prepared off the UI thread. No permission is granted by preparation. */
        public final URI contentURI;
        public final long byteCount;
        public PreparedFile(File file, URI contentURI, long byteCount) {
            this.file = Objects.requireNonNull(file, "file");
            this.contentURI = Objects.requireNonNull(contentURI, "contentURI");
            if (!file.isAbsolute() || byteCount <= 0 || !"content".equals(contentURI.getScheme())
                    || contentURI.getRawAuthority() == null || !contentURI.getRawAuthority().matches("[A-Za-z0-9._-]+")
                    || contentURI.getPath() == null || !contentURI.getPath().startsWith("/")
                    || contentURI.getRawQuery() != null || contentURI.getRawFragment() != null)
                throw new IllegalArgumentException("Invalid prepared export");
            this.byteCount = byteCount;
        }
    }
    interface Completion { void complete(PreparedFile file, Error error); }
    /** Completes once on any thread, including immediate rejection. Rejection returns null.
     * Success means a complete file is ready; it does not mean anything was sent. */
    Task prepare(IncidentSnapshot snapshot, Completion completion);
}
