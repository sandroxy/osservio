package com.sandrox.osservio.core;

public interface HTTPSink {
    enum WriteResult { STORED, BUSY, TOO_LARGE, MISSING }
    /** Atomic, non-blocking metadata/body update. Updates never recreate cleared or evicted records. */
    WriteResult write(HTTPUpdate update);
}
