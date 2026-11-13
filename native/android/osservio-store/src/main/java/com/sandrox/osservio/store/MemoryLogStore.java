package com.sandrox.osservio.store;

import com.sandrox.osservio.core.LogEvent;
import com.sandrox.osservio.core.LogHistory;
import com.sandrox.osservio.core.LogClock;
import com.sandrox.osservio.core.LogLevel;
import com.sandrox.osservio.core.LogSink;
import com.sandrox.osservio.core.LogSnapshot;
import com.sandrox.osservio.core.LogDetailsSink;
import com.sandrox.osservio.core.LogDetailsHistory;
import com.sandrox.osservio.core.LogDetailsContent;
import com.sandrox.osservio.core.LogDetailsReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/** Bounded by event count and UTF-8 text bytes. Object overhead is bounded by count. */
public final class MemoryLogStore implements LogDetailsSink, LogDetailsHistory {
    private final ReentrantLock lock = new ReentrantLock();
    private static final class Entry {
        final LogEvent event; final long admittedAt;
        Entry(LogEvent event, long admittedAt) { this.event = event; this.admittedAt = admittedAt; }
    }
    private final ArrayDeque<Entry> events = new ArrayDeque<>();
    private final int recordLimit;
    private final int byteLimit;
    private final int errorReserve;
    private final long errorProtectionMillis;
    private final LogClock clock;
    private int bytes;
    private int errors;
    private long evicted;
    private long revision;
    private final int detailsByteLimit;
    private final LinkedHashMap<String, LogDetailsContent> details = new LinkedHashMap<>();
    private int detailsBytes;
    private long evictedDetails;

    public MemoryLogStore() { this(500, 524288); }

    public MemoryLogStore(int recordLimit, int textByteLimit) {
        this(recordLimit, textByteLimit, Math.min(32, Math.max(1, recordLimit / 4)), 30000L, LogClock.SYSTEM);
    }

    public MemoryLogStore(int recordLimit, int textByteLimit, int errorReserve, long errorProtectionMillis, LogClock clock) {
        this(recordLimit, textByteLimit, errorReserve, errorProtectionMillis, clock, 262144);
    }
    public MemoryLogStore(int recordLimit, int textByteLimit, int errorReserve, long errorProtectionMillis, LogClock clock, int detailsByteLimit) {
        if (recordLimit < 1 || recordLimit > 10000 || textByteLimit < 1 || textByteLimit > 16777216
            || errorReserve < 0 || errorReserve > Math.min(64, recordLimit) || errorProtectionMillis < 1 || errorProtectionMillis > 300000L
            || detailsByteLimit < 0 || detailsByteLimit > 16777216) {
            throw new IllegalArgumentException("Invalid log store limit");
        }
        this.recordLimit = recordLimit;
        this.byteLimit = textByteLimit;
        this.errorReserve = errorReserve; this.errorProtectionMillis = errorProtectionMillis; this.clock = Objects.requireNonNull(clock, "clock");
        this.detailsByteLimit = detailsByteLimit;
    }

    @Override public WriteResult append(LogEvent event) {
        return write(event, null);
    }
    @Override public WriteResult append(LogEvent event, LogDetailsContent content) {
        if (content == null || event.details == null || event.details.state != LogDetailsReference.State.AVAILABLE
            || !content.id.equals(event.details.blobId) || content.byteCount != event.details.textByteCount) throw new IllegalArgumentException("Invalid log details reference");
        return write(event, content);
    }
    private WriteResult write(LogEvent event, LogDetailsContent content) {
        if (!lock.tryLock()) return WriteResult.BUSY;
        try {
            if (event.textByteCount > byteLimit) return WriteResult.TOO_LARGE;
            long now = clock.monotonicMillis(); int incomingErrors = event.level == LogLevel.ERROR ? 1 : 0;
            int remainingErrors = errors + incomingErrors, availableCount = events.size(), availableBytes = bytes;
            // Reject atomically if protected entries leave insufficient capacity.
            for (Entry entry : events) {
                if (availableCount < recordLimit && availableBytes <= byteLimit - event.textByteCount) break;
                boolean protectedEntry = isProtected(entry, remainingErrors, now);
                if (entry.event.level == LogLevel.ERROR) remainingErrors--;
                if (!protectedEntry) { availableCount--; availableBytes -= entry.event.textByteCount; }
            }
            if (availableCount >= recordLimit || availableBytes > byteLimit - event.textByteCount) return WriteResult.PROTECTED_CAPACITY;
            remainingErrors = errors + incomingErrors;
            Iterator<Entry> iterator = events.iterator();
            while (iterator.hasNext() && (events.size() == recordLimit || bytes > byteLimit - event.textByteCount)) {
                Entry entry = iterator.next(); boolean protectedEntry = isProtected(entry, remainingErrors, now);
                if (entry.event.level == LogLevel.ERROR) remainingErrors--;
                if (protectedEntry) continue;
                bytes -= entry.event.textByteCount;
                if (entry.event.details != null) removeDetails(entry.event.details.blobId);
                if (entry.event.level == LogLevel.ERROR) errors--;
                iterator.remove();
                if (evicted < Long.MAX_VALUE) evicted++;
            }
            events.addLast(new Entry(event, now)); errors += incomingErrors;
            bytes += event.textByteCount;
            if (content != null) {
                if (content.byteCount > detailsByteLimit) { if (evictedDetails < Long.MAX_VALUE) evictedDetails++; }
                else {
                    while (detailsBytes > detailsByteLimit - content.byteCount && !details.isEmpty()) removeDetails(details.keySet().iterator().next());
                    removeDetails(content.id); details.put(content.id, content); detailsBytes += content.byteCount;
                }
            }
            revision++;
            return WriteResult.STORED;
        } finally { lock.unlock(); }
    }

    private boolean isProtected(Entry entry, int remainingErrors, long now) {
        return entry.event.level == LogLevel.ERROR && remainingErrors <= errorReserve && now >= entry.admittedAt
            && now - entry.admittedAt < errorProtectionMillis;
    }

    @Override public LogSnapshot snapshot() {
        if (!lock.tryLock()) return null;
        try {
            ArrayList<LogEvent> snapshot = new ArrayList<>(events.size());
            for (Entry entry : events) snapshot.add(entry.event);
            return new LogSnapshot(snapshot, bytes, evicted, revision, detailsBytes, evictedDetails);
        }
        finally { lock.unlock(); }
    }

    @Override public Lookup details(String id) {
        if (!lock.tryLock()) return Lookup.busy();
        try { LogDetailsContent content = details.get(id); return content == null ? Lookup.unavailable() : Lookup.available(content); }
        finally { lock.unlock(); }
    }
    private void removeDetails(String id) {
        LogDetailsContent content = details.remove(id);
        if (content != null) { detailsBytes -= content.byteCount; if (evictedDetails < Long.MAX_VALUE) evictedDetails++; }
    }

    @Override public void clear() {
        lock.lock();
        try { events.clear(); bytes = 0; errors = 0; details.clear(); detailsBytes = 0; revision++; }
        finally { lock.unlock(); }
    }
}
