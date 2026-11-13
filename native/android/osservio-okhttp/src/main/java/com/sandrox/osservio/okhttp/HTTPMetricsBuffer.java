package com.sandrox.osservio.okhttp;

import com.sandrox.osservio.core.HTTPMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/** One bounded call-local buffer. No payload, address, or Throwable is retained in recorded metrics. */
final class HTTPMetricsBuffer {
    private static final class Span {
        final HTTPMetrics.Phase phase;
        final long start;
        Object key;
        Long end;
        Span(HTTPMetrics.Phase phase, Object key, long start) { this.phase = phase; this.key = key; this.start = start; }
    }
    private final ReentrantLock lock = new ReentrantLock();
    private final List<Span> spans = new ArrayList<>();
    private volatile boolean limited;
    private boolean active;
    private long origin;
    private Long requestEnd;
    boolean activate() {
        if (!lock.tryLock()) { limited = true; return false; }
        try { if (!active) { origin = System.nanoTime(); active = true; } return true; } finally { lock.unlock(); }
    }
    private long now() { return Math.max(0, (System.nanoTime() - origin) / 1_000_000); }
    void start(HTTPMetrics.Phase phase, Object key) {
        if (!lock.tryLock()) { limited = true; return; }
        try {
            if (!active) return;
            if (phase == HTTPMetrics.Phase.REQUEST_HEADERS || phase == HTTPMetrics.Phase.REQUEST_BODY) requestEnd = null;
            if (phase == HTTPMetrics.Phase.RESPONSE_HEADERS && requestEnd != null) {
                Span waiting = add(HTTPMetrics.Phase.WAITING, null, requestEnd);
                if (waiting != null) waiting.end = now();
                requestEnd = null;
            }
            add(phase, key, now());
        } finally { lock.unlock(); }
    }
    private Span add(HTTPMetrics.Phase phase, Object key, long time) {
        if (spans.size() >= 32) { limited = true; return null; }
        Span span = new Span(phase, key, time); spans.add(span); return span;
    }
    void end(HTTPMetrics.Phase phase, Object key) {
        if (!lock.tryLock()) { limited = true; return; }
        try {
            if (!active) return;
            Span match = null;
            for (Span span : spans) if (span.phase == phase && span.end == null && Objects.equals(span.key, key)) {
                if (match != null) { limited = true; return; }
                match = span;
            }
            if (match == null) { limited = true; return; }
            match.end = now(); match.key = null;
            if (phase == HTTPMetrics.Phase.REQUEST_HEADERS || phase == HTTPMetrics.Phase.REQUEST_BODY) requestEnd = match.end;
        } finally { lock.unlock(); }
    }
    HTTPMetrics snapshot(String protocol, HTTPMetrics.Cache cache) {
        if (!lock.tryLock()) return new HTTPMetrics(java.util.Collections.emptyList(), protocol, cache, null, true);
        try {
            List<HTTPMetrics.Timing> values = new ArrayList<>(spans.size());
            for (Span span : spans) values.add(new HTTPMetrics.Timing(span.phase, span.start, span.end));
            active = false; spans.clear(); requestEnd = null;
            return new HTTPMetrics(values, protocol, cache, null, limited);
        } finally { lock.unlock(); }
    }
}
