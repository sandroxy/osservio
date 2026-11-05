package com.sandrox.osservio.store;

import com.sandrox.osservio.core.LogEvent;
import com.sandrox.osservio.core.LogHistory;
import com.sandrox.osservio.core.LogSink;
import com.sandrox.osservio.core.LogSnapshot;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

/** Bounded by event count and UTF-8 text bytes. Object overhead is bounded by count. */
public final class MemoryLogStore implements LogSink, LogHistory {
    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayDeque<LogEvent> events = new ArrayDeque<>();
    private final int recordLimit;
    private final int byteLimit;
    private int bytes;
    private long evicted;
    private long revision;

    public MemoryLogStore() { this(500, 524288); }

    public MemoryLogStore(int recordLimit, int textByteLimit) {
        if (recordLimit < 1 || recordLimit > 10000 || textByteLimit < 1 || textByteLimit > 16777216) {
            throw new IllegalArgumentException("Invalid log store limit");
        }
        this.recordLimit = recordLimit;
        this.byteLimit = textByteLimit;
    }

    @Override public WriteResult append(LogEvent event) {
        if (!lock.tryLock()) return WriteResult.BUSY;
        try {
            if (event.textByteCount > byteLimit) return WriteResult.TOO_LARGE;
            while (!events.isEmpty() && (events.size() == recordLimit || bytes > byteLimit - event.textByteCount)) {
                bytes -= events.removeFirst().textByteCount;
                if (evicted < Long.MAX_VALUE) evicted++;
            }
            events.addLast(event);
            bytes += event.textByteCount;
            revision++;
            return WriteResult.STORED;
        } finally { lock.unlock(); }
    }

    @Override public LogSnapshot snapshot() {
        if (!lock.tryLock()) return null;
        try { return new LogSnapshot(new ArrayList<>(events), bytes, evicted, revision); }
        finally { lock.unlock(); }
    }

    @Override public void clear() {
        lock.lock();
        try { events.clear(); bytes = 0; revision++; }
        finally { lock.unlock(); }
    }
}
