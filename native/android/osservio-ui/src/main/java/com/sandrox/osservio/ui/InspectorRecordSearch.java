package com.sandrox.osservio.ui;

import com.sandrox.osservio.core.HTTPEvent;
import com.sandrox.osservio.core.HTTPHeader;
import com.sandrox.osservio.core.HTTPHistory;
import com.sandrox.osservio.core.LogEvent;
import com.sandrox.osservio.core.WebSocketEvent;
import com.sandrox.osservio.core.WebSocketHistory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Searches sanitized retained evidence on demand. No host streams or persistent body index. */
final class InspectorRecordSearch {
    static final int MAX_RECORDS = 4_000, MAX_UNITS = 2_097_152;
    static final class Record {
        final LogEvent log;
        final HTTPEvent http;
        final WebSocketEvent socket;
        Record(LogEvent value) { log = value; http = null; socket = null; }
        Record(HTTPEvent value) { log = null; http = value; socket = null; }
        Record(WebSocketEvent value) { log = null; http = null; socket = value; }
        String key() { return log != null ? "log:" + log.sessionId + ":" + log.sequence : http != null ? "http:" + http.id : "socket:" + socket.id; }
        long time() { return log != null ? log.timestampMillis : http != null ? http.timestampMillis : socket.timestampMillis; }
        boolean matchesFacets(InspectorQuery query, long now) { return log != null ? query.matchesFacets(log, now) : http != null ? query.matchesFacets(http, now) : query.matchesFacets(socket, now); }
        boolean contains(String term) {
            if (term.isEmpty()) return true;
            if (log != null) return has(term, log.category);
            if (http != null) {
                if (has(term, http.url, http.responseURL, http.method, http.id, http.statusCode == null ? "" : http.statusCode.toString())) return true;
                for (HTTPHeader header : http.requestHeaders) if (has(term, header.name, header.value)) return true;
                for (HTTPHeader header : http.responseHeaders) if (has(term, header.name, header.value)) return true;
                return false;
            }
            return has(term, socket.url, socket.id, socket.connectionId, socket.kind.name(), socket.outcome == null ? "" : socket.outcome.name());
        }
        private static boolean has(String term, String... fields) {
            for (String field : fields) if (InspectorQuery.hasText(field, term)) return true;
            return false;
        }
    }
    static final class Result {
        final Set<String> keys = new HashSet<>();
        final Map<String, String> previews = new HashMap<>();
        int unavailable, truncated;
        boolean limited;
    }
    private static final class Payload {
        final String text;
        final boolean truncated;
        Payload(String text, boolean truncated) { this.text = text; this.truncated = truncated; }
    }
    private InspectorRecordSearch() { }
    static int newestFirst(Record left, Record right) {
        if (left.socket != null && right.socket != null) return InspectorQuery.compareTimeline(left.socket, right.socket);
        return left.time() == right.time() ? left.key().compareTo(right.key()) : Long.compare(right.time(), left.time());
    }
    static Result run(List<Record> records, InspectorQuery query, long now, HTTPHistory http, WebSocketHistory sockets,
                      int unitLimit, InspectorJsonDocument.Cancellation cancelled) {
        Result result = new Result(); int spent = 0; long started = System.nanoTime();
        for (int position = 0; position < records.size(); position++) {
            if (cancelled.isCancelled()) return null;
            if (position >= MAX_RECORDS || System.nanoTime() - started > 750_000_000L) { result.limited = true; break; }
            Record record = records.get(position);
            if (!record.matchesFacets(query, now)) continue;
            boolean metadata = record.contains(query.text);
            if (!query.excludedText.isEmpty() && record.contains(query.excludedText)) continue;
            if (metadata && query.fieldPath.isEmpty() && query.excludedText.isEmpty()) { result.keys.add(record.key()); continue; }
            if (spent >= unitLimit) { result.limited = true; continue; }
            boolean matched = metadata && query.fieldPath.isEmpty(), excluded = false, exclusionUnknown = false;
            for (Payload payload : payloads(record, http, sockets)) {
                if (cancelled.isCancelled()) return null;
                String text = payload.text;
                if (text == null) { result.unavailable++; exclusionUnknown = true; continue; }
                int count = text.length();
                if (count > InspectorJsonDocument.MAX_UNITS || count > unitLimit - spent) { result.limited = true; exclusionUnknown = true; continue; }
                spent += count;
                if (payload.truncated) { result.truncated++; exclusionUnknown = true; }
                if (!query.excludedText.isEmpty() && InspectorQuery.hasText(text, query.excludedText)) { excluded = true; break; }
                boolean fieldMatches = true;
                if (!query.fieldPath.isEmpty()) {
                    if (payload.truncated) fieldMatches = false;
                    else {
                        InspectorJsonField.Match field = InspectorJsonField.matches(text, query.fieldPath, query.fieldValue, cancelled);
                        fieldMatches = field == InspectorJsonField.Match.YES;
                        if (field == InspectorJsonField.Match.UNAVAILABLE) result.unavailable++;
                    }
                }
                if (fieldMatches && (metadata || InspectorQuery.hasText(text, query.text))) {
                    matched = true;
                    if (!result.previews.containsKey(record.key())) result.previews.put(record.key(), preview(text, query.text));
                }
            }
            if (matched && !excluded && (query.excludedText.isEmpty() || !exclusionUnknown)) result.keys.add(record.key()); else result.previews.remove(record.key());
        }
        return cancelled.isCancelled() ? null : result;
    }
    private static List<Payload> payloads(Record record, HTTPHistory http, WebSocketHistory sockets) {
        if (record.log != null) return Collections.singletonList(new Payload(record.log.isPrivate ? null : record.log.message, record.log.isTruncated));
        if (record.http != null) {
            List<Payload> result = new ArrayList<>(2);
            for (HTTPEvent.BodyReference reference : new HTTPEvent.BodyReference[] {record.http.requestBody, record.http.responseBody}) {
                HTTPHistory.BodyLookup lookup = reference.blobId == null || http == null ? null : http.body(reference.blobId);
                result.add(new Payload(lookup == null || lookup.state != HTTPHistory.BodyLookup.State.AVAILABLE ? null : lookup.body.text,
                    reference.state == HTTPEvent.BodyState.TRUNCATED));
            }
            return result;
        }
        WebSocketEvent.PayloadReference reference = record.socket.payload;
        if (reference == null) return Collections.emptyList();
        WebSocketHistory.PayloadLookup lookup = reference.blobId == null || sockets == null ? null : sockets.payload(reference.blobId);
        return Collections.singletonList(new Payload(lookup == null || lookup.state != WebSocketHistory.PayloadLookup.State.AVAILABLE ? null : lookup.payload.text,
            reference.state == WebSocketEvent.PayloadState.TRUNCATED));
    }
    private static String preview(String text, String term) {
        int hit = 0;
        if (!term.isEmpty()) for (int i = 0; i <= text.length() - term.length(); i++) {
            if (text.regionMatches(true, i, term, 0, term.length())) { hit = i; break; }
        }
        int start = Math.max(0, hit - 30), end;
        if (start > 0 && Character.isLowSurrogate(text.charAt(start))) start--;
        end = Math.min(text.length(), start + 180);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return (start > 0 ? "…" : "") + text.substring(start, end).replace('\n', ' ') + (end < text.length() ? "…" : "");
    }
}
