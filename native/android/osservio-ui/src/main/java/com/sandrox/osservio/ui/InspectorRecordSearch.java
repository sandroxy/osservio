package com.sandrox.osservio.ui;

import com.sandrox.osservio.core.HTTPEvent;
import com.sandrox.osservio.core.IncidentPayload;
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
    static final int MAX_UNITS = InspectorJsonDocument.MAX_UNITS;
    static final class Record {
        String displayText = "", displayAddress = "";
        final LogEvent log;
        final HTTPEvent http;
        final WebSocketEvent socket;
        Record(LogEvent value) { log = value; http = null; socket = null; }
        Record(HTTPEvent value) { log = null; http = value; socket = null; }
        Record(WebSocketEvent value) { log = null; http = null; socket = value; }
        String key() { return log != null ? "log:" + log.sessionId + ":" + log.sequence : http != null ? "http:" + http.id : "socket:" + socket.id; }
        long time() { return log != null ? log.timestampMillis : http != null ? http.timestampMillis : socket.timestampMillis; }
        boolean matchesFacets(InspectorQuery query, long now) { return log != null ? query.matchesFacets(log, now) : http != null ? query.matchesFacets(http, now) : query.matchesFacets(socket, now); }
        boolean contains(String term) { return term.isEmpty() || match(term, "all") != null; }
        Match match(String term, String scope) {
            if (term.isEmpty()) return null;
            Match found;
            if (log != null) {
                found = find(term, scope, "identity", "identity", log.sessionId + " #" + log.sequence);
                return found != null ? found : find(term, scope, "metadata", "metadata", log.category, displayText);
            }
            if (http != null) {
                found = find(term, scope, "address", "address", http.url, displayAddress, http.responseURL); if (found != null) return found;
                found = headers(term, scope, http.requestHeaders, "requestHeaders"); if (found != null) return found;
                found = headers(term, scope, http.responseHeaders, "responseHeaders"); if (found != null) return found;
                found = find(term, scope, "identity", "identity", http.id, http.sessionId + " #" + http.sequence); if (found != null) return found;
                return find(term, scope, "metadata", "metadata", http.method, http.statusCode == null ? "" : http.statusCode.toString(), displayText);
            }
            found = find(term, scope, "address", "address", socket.url, displayAddress); if (found != null) return found;
            found = headers(term, scope, socket.headers, "handshakeHeaders"); if (found != null) return found;
            found = find(term, scope, "identity", "identity", socket.id, socket.connectionId, socket.relatedMessageId, socket.sessionId + " #" + socket.sequence); if (found != null) return found;
            return find(term, scope, "metadata", "metadata", socket.kind.name(), socket.outcome == null ? "" : socket.outcome.name(),
                socket.statusCode == null ? "" : socket.statusCode.toString(), socket.closeCode == null ? "" : socket.closeCode.toString(), displayText);
        }
        private static Match find(String term, String scope, String field, String source, String... values) {
            if (!scope.equals("all") && !scope.equals(field)) return null;
            for (String value : values) if (InspectorQuery.hasText(value, term)) return new Match(source, value);
            return null;
        }
        private static Match headers(String term, String scope, List<HTTPHeader> headers, String source) {
            if (!scope.equals("all") && !scope.equals("headers")) return null;
            for (HTTPHeader header : headers) if (has(term, header.name, header.value)) return new Match(source, header.name + ": " + header.value);
            return null;
        }
        boolean headersLimited() { return http != null ? http.headersLimited : socket != null && socket.headersLimited; }
        private static boolean has(String term, String... fields) {
            for (String field : fields) if (InspectorQuery.hasText(field, term)) return true;
            return false;
        }
    }
    private static final class Match {
        final String source, text;
        Match(String source, String text) { this.source = source; this.text = text; }
    }
    static final class Result {
        final Set<String> keys = new HashSet<>();
        final List<Integer> positions = new ArrayList<>();
        int scanned, total;
        boolean complete = true;
        final Map<String, String> previews = new HashMap<>();
        final Map<String, String> previewSources = new HashMap<>();
        int unavailable, truncated;
        boolean limited;
        Result copy() {
            Result copy = new Result(); copy.keys.addAll(keys); copy.positions.addAll(positions);
            copy.previews.putAll(previews); copy.previewSources.putAll(previewSources);
            copy.scanned = scanned; copy.total = total; copy.complete = complete;
            copy.unavailable = unavailable; copy.truncated = truncated; copy.limited = limited; return copy;
        }
    }
    interface Preparation { void prepare(Record record); }
    interface Progress { void publish(Result result); }
    private static final class Payload {
        final String text;
        final boolean truncated; final String source;
        Payload(String text, boolean truncated) { this(text, truncated, "log"); }
        Payload(String text, boolean truncated, String source) { this.text = text; this.truncated = truncated; this.source = source; }
        String scope() { return source.equals("request") || source.equals("response") ? source : "message"; }
    }
    private InspectorRecordSearch() { }
    static int newestFirst(Record left, Record right) {
        if (left.socket != null && right.socket != null) return InspectorQuery.compareTimeline(left.socket, right.socket);
        return left.time() == right.time() ? left.key().compareTo(right.key()) : Long.compare(right.time(), left.time());
    }
    static Result run(List<Record> records, InspectorQuery query, long now, HTTPHistory http, WebSocketHistory sockets,
                      int unitLimit, InspectorJsonDocument.Cancellation cancelled) {
        return run(records, query, now, http, sockets, null, unitLimit, cancelled);
    }
    static Result run(List<Record> records, InspectorQuery query, long now, HTTPHistory http, WebSocketHistory sockets,
                      Map<String, IncidentPayload> saved, int unitLimit, InspectorJsonDocument.Cancellation cancelled) {
        return run(records, query, now, http, sockets, saved, null, unitLimit, cancelled);
    }
    static Result run(List<Record> records, InspectorQuery query, long now, HTTPHistory http, WebSocketHistory sockets,
                      Map<String, IncidentPayload> saved, com.sandrox.osservio.core.LogDetailsHistory logs, int unitLimit, InspectorJsonDocument.Cancellation cancelled) {
        return run(records, query, now, http, sockets, saved, logs, unitLimit, cancelled, record -> { }, result -> { });
    }
    static Result run(List<Record> records, InspectorQuery query, long now, HTTPHistory http, WebSocketHistory sockets,
                      Map<String, IncidentPayload> saved, com.sandrox.osservio.core.LogDetailsHistory logs, int unitLimit,
                      InspectorJsonDocument.Cancellation cancelled, Preparation preparation, Progress progress) {
        Result result = new Result(); result.total = records.size(); result.complete = false;
        long sliceStart = System.nanoTime(), publishedAt = 0; int sliceCount = 0;
        for (int position = 0; position < records.size(); position++) {
            if (cancelled.isCancelled()) return null;
            Record record = records.get(position);
            if (record.matchesFacets(query, now)) {
                preparation.prepare(record);
                if (match(record, query, http, sockets, saved, logs, unitLimit, cancelled, result)) {
                    result.keys.add(record.key()); result.positions.add(position);
                }
            }
            result.scanned = position + 1; sliceCount++;
            long tick = System.nanoTime();
            if (sliceCount >= 256 || tick - sliceStart >= 8_000_000L) {
                if (publishedAt == 0 || tick - publishedAt >= 100_000_000L) {
                    if (cancelled.isCancelled()) return null;
                    progress.publish(result.copy()); publishedAt = tick;
                }
                // Yield a bounded slice to host work, then continue the same retained window.
                try { Thread.sleep(1); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return null; }
                sliceStart = System.nanoTime(); sliceCount = 0;
            }
        }
        result.complete = true;
        return cancelled.isCancelled() ? null : result;
    }
    private static boolean match(Record record, InspectorQuery query, HTTPHistory http, WebSocketHistory sockets,
                             Map<String, IncidentPayload> saved, com.sandrox.osservio.core.LogDetailsHistory logs,
                             int unitLimit, InspectorJsonDocument.Cancellation cancelled, Result result) {
        Match hit = record.match(query.text, query.searchScope());
        boolean metadata = query.text.isEmpty() || hit != null;
        boolean incompleteHeaders = query.searches("headers") && record.headersLimited();
        if (incompleteHeaders) result.truncated++;
        if (!query.excludedText.isEmpty() && record.match(query.excludedText, query.searchScope()) != null) return false;
        if (hit != null) { result.previews.put(record.key(), preview(hit.text, query.text)); result.previewSources.put(record.key(), hit.source); }
        if (metadata && query.fieldPath.isEmpty() && query.excludedText.isEmpty()) { return true; }
        boolean matched = metadata && query.fieldPath.isEmpty(), excluded = false, exclusionUnknown = incompleteHeaders;
        for (Payload payload : payloads(record, query, http, sockets, saved, logs)) {
            if (cancelled.isCancelled()) return false;
            String text = payload.text;
            if (text == null) { result.unavailable++; exclusionUnknown |= query.searches(payload.scope()); continue; }
            int count = text.length();
            if (count > InspectorJsonDocument.MAX_UNITS || count > unitLimit) { result.limited = true; exclusionUnknown |= query.searches(payload.scope()); continue; }
            if (payload.truncated) { result.truncated++; exclusionUnknown |= query.searches(payload.scope()); }
            if (query.searches(payload.scope()) && !query.excludedText.isEmpty() && InspectorQuery.hasText(text, query.excludedText)) { excluded = true; break; }
            boolean fieldMatches = true;
            if (!query.fieldPath.isEmpty()) {
                if (payload.truncated) fieldMatches = false;
                else {
                    InspectorJsonField.Match field = InspectorJsonField.matches(text, query.fieldPath, query.fieldValue, cancelled);
                    fieldMatches = field == InspectorJsonField.Match.YES;
                    if (field == InspectorJsonField.Match.UNAVAILABLE) result.unavailable++;
                }
            }
            if (fieldMatches && (metadata || query.searches(payload.scope()) && InspectorQuery.hasText(text, query.text))) {
                matched = true;
                if (!result.previews.containsKey(record.key())) { result.previews.put(record.key(), preview(text, query.text)); result.previewSources.put(record.key(), payload.source); }
            }
        }
        if (matched && !excluded && (query.excludedText.isEmpty() || !exclusionUnknown)) return true;
        result.previews.remove(record.key()); result.previewSources.remove(record.key());
        return false;
    }
    private static List<Payload> payloads(Record record, InspectorQuery query, HTTPHistory http, WebSocketHistory sockets, Map<String, IncidentPayload> saved, com.sandrox.osservio.core.LogDetailsHistory logs) {
        if (record.log != null) {
            if (!query.searches("message") && query.fieldPath.isEmpty()) return Collections.emptyList();
            List<Payload> result = new ArrayList<>(2); result.add(new Payload(record.log.isPrivate ? null : record.log.message, record.log.isTruncated));
            if (record.log.details != null) {
                String text = null, id = record.log.details.blobId;
                if (id != null) {
                    if (saved != null) { IncidentPayload payload = saved.get(id); if (payload != null && payload.state == IncidentPayload.State.AVAILABLE) text = payload.text; }
                    else if (logs != null) { com.sandrox.osservio.core.LogDetailsHistory.Lookup lookup = logs.details(id); if (lookup.content != null) text = lookup.content.text; }
                }
                result.add(new Payload(text, false, "logDetails"));
            }
            return result;
        }
        if (record.http != null) {
            List<Payload> result = new ArrayList<>(2); int index = 0;
            for (HTTPEvent.BodyReference reference : new HTTPEvent.BodyReference[] {record.http.requestBody, record.http.responseBody}) {
                String source = index++ == 0 ? "request" : "response";
                if (!query.searches(source) && query.fieldPath.isEmpty()) continue;
                String text = null;
                if (reference.blobId != null) {
                    if (saved != null) { IncidentPayload payload = saved.get(reference.blobId); if (payload != null && payload.state == IncidentPayload.State.AVAILABLE) text = payload.text; }
                    else if (http != null) { HTTPHistory.BodyLookup lookup = http.body(reference.blobId); if (lookup.state == HTTPHistory.BodyLookup.State.AVAILABLE) text = lookup.body.text; }
                }
                result.add(new Payload(text, reference.state == HTTPEvent.BodyState.TRUNCATED, source));
            }
            return result;
        }
        WebSocketEvent.PayloadReference reference = record.socket.payload;
        if (reference == null || !query.searches("message") && query.fieldPath.isEmpty()) return Collections.emptyList();
        String text = null;
        if (reference.blobId != null) {
            if (saved != null) { IncidentPayload payload = saved.get(reference.blobId); if (payload != null && payload.state == IncidentPayload.State.AVAILABLE) text = payload.text; }
            else if (sockets != null) { WebSocketHistory.PayloadLookup lookup = sockets.payload(reference.blobId); if (lookup.state == WebSocketHistory.PayloadLookup.State.AVAILABLE) text = lookup.payload.text; }
        }
        return Collections.singletonList(new Payload(text, reference.state == WebSocketEvent.PayloadState.TRUNCATED, "message"));
    }
    static String preview(String text, String term) {
        int hit = 0;
        if (!term.isEmpty()) for (int i = 0; i <= text.length() - term.length(); i++) {
            if (text.regionMatches(true, i, term, 0, term.length())) { hit = i; break; }
        }
        int start = text.length() <= 180 ? 0 : Math.max(0, hit - 30), end;
        if (start > 0 && Character.isLowSurrogate(text.charAt(start))) start--;
        end = Math.min(text.length(), start + 180);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return (start > 0 ? "…" : "") + text.substring(start, end).replace('\n', ' ') + (end < text.length() ? "…" : "");
    }
}
