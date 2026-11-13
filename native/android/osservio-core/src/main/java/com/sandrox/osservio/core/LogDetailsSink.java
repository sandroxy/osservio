package com.sandrox.osservio.core;

public interface LogDetailsSink extends LogSink {
    /** Atomically admits metadata and attempts bounded payload retention without waiting. */
    WriteResult append(LogEvent event, LogDetailsContent details);
}
