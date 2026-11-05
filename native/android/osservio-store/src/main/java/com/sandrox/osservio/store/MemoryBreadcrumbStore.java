package com.sandrox.osservio.store;

import com.sandrox.osservio.core.BreadcrumbEvent;
import com.sandrox.osservio.core.BreadcrumbHistory;
import com.sandrox.osservio.core.BreadcrumbSink;
import com.sandrox.osservio.core.BreadcrumbSnapshot;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

/** A bounded recent window. This implementation never persists restart-relevant events. */
public final class MemoryBreadcrumbStore implements BreadcrumbSink, BreadcrumbHistory {
    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayDeque<BreadcrumbEvent> events = new ArrayDeque<>();
    private final int recordLimit;
    private final int byteLimit;
    private int bytes;
    private long evicted;
    private long revision;

    public MemoryBreadcrumbStore() { this(200, 65536); }

    public MemoryBreadcrumbStore(int recordLimit, int textByteLimit) {
        if (recordLimit < 1 || recordLimit > 2000 || textByteLimit < 1 || textByteLimit > 1048576) {
            throw new IllegalArgumentException("Invalid breadcrumb store limit");
        }
        this.recordLimit = recordLimit;
        this.byteLimit = textByteLimit;
    }

    @Override public WriteResult append(BreadcrumbEvent event) {
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

    @Override public BreadcrumbSnapshot snapshot() {
        if (!lock.tryLock()) return null;
        try { return new BreadcrumbSnapshot(new ArrayList<>(events), bytes, evicted, revision); }
        finally { lock.unlock(); }
    }

    @Override public void clear() {
        lock.lock();
        try { events.clear(); bytes = 0; revision++; }
        finally { lock.unlock(); }
    }
}
