package com.sandrox.osservio.core;

public interface LogSink {
    enum WriteResult { STORED, BUSY, TOO_LARGE }

    /** Must return promptly without waiting for a lock, disk, or network. */
    WriteResult append(LogEvent event);
}
