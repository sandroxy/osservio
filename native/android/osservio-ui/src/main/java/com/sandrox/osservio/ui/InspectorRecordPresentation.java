package com.sandrox.osservio.ui;

import android.content.Context;
import com.sandrox.osservio.core.HTTPEvent;
import com.sandrox.osservio.core.LogEvent;
import com.sandrox.osservio.core.LogLevel;
import com.sandrox.osservio.core.WebSocketEvent;
import java.util.ArrayList;
import java.util.List;

/** One record summary for live lists, frozen evidence and search results. */
final class InspectorRecordPresentation {
    private InspectorRecordPresentation() { }
    static final class Row {
        final String badge, title, subtitle; final long time; final int tone;
        Row(String badge, String title, String subtitle, long time, int tone) { this.badge = badge; this.title = title; this.subtitle = subtitle; this.time = time; this.tone = tone; }
        void bind(InspectorWidgets.EventRow row, Context context) {
            row.bind(badge, title, subtitle, time, tone == 2 ? InspectorTheme.color(context, R.color.osservio_error)
                : tone == 1 ? InspectorTheme.color(context, R.color.osservio_warning) : InspectorTheme.secondary(context));
        }
    }
    static String compact(String text, int limit) {
        int end = Math.min(text.length(), limit);
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end).replace('\r', ' ').replace('\n', ' ') + (end < text.length() ? "…" : "");
    }
    static String identity(String id) { return id.length() <= 16 ? id : id.substring(0, 8) + "…" + id.substring(id.length() - 4); }
    static String connection(Context context, String id) { return context.getString(R.string.osservio_ui_connection) + " · " + identity(id); }
    static String joined(String... parts) {
        List<String> result = new ArrayList<>(); for (String part : parts) if (part != null && !part.isEmpty()) result.add(part);
        return android.text.TextUtils.join(" · ", result);
    }
    static String path(Context context, String source) {
        InspectorURL url = new InspectorURL(source);
        return !url.available ? context.getString(R.string.osservio_ui_url_unavailable) : url.pathHidden ? context.getString(R.string.osservio_ui_path_hidden) : url.path;
    }
    static String address(Context context, String source) {
        InspectorURL url = new InspectorURL(source);
        return joined(url.origin, path(context, source), url.queryHidden ? context.getString(R.string.osservio_ui_query_hidden) : "");
    }
    static Row log(Context context, LogEvent event, String preview) {
        return new Row(event.level == LogLevel.WARNING ? "WARN" : event.level.name(),
            event.isPrivate ? context.getString(R.string.osservio_ui_private_content) : compact(preview == null ? event.message : preview, 600),
            joined(event.category, event.isTruncated ? context.getString(R.string.osservio_truncated) : ""), event.timestampMillis,
            event.level == LogLevel.ERROR ? 2 : event.level == LogLevel.WARNING ? 1 : 0);
    }
    static Row http(Context context, HTTPEvent event, String preview) {
        InspectorURL url = new InspectorURL(event.url);
        String result = event.failure != null ? HTTPPresentation.failure(context, event.failure) : event.state == HTTPEvent.State.COMPLETED ? "" : HTTPPresentation.state(context, event.state);
        return new Row(event.method + (event.statusCode == null ? "" : " · " + event.statusCode), path(context, event.url),
            joined(url.origin, result, event.durationMillis == null ? "" : event.durationMillis + " ms", url.queryHidden ? context.getString(R.string.osservio_ui_query_hidden) : "")
                + (preview == null ? "" : "\n" + preview), event.timestampMillis,
            event.state == HTTPEvent.State.FAILED || event.statusCode != null && event.statusCode >= 400 ? 2 : 0);
    }
    static Row socket(Context context, WebSocketEvent event, String preview, boolean scoped) {
        boolean message = event.kind == WebSocketEvent.Kind.MESSAGE, result = event.kind == WebSocketEvent.Kind.MESSAGE_RESULT;
        String direction = event.direction == null ? null : context.getString(event.direction == WebSocketEvent.Direction.INCOMING ? R.string.osservio_ws_incoming : R.string.osservio_ws_outgoing);
        String badge = "Socket · " + (message && direction != null ? direction : WebSocketPresentation.kind(context, event.kind));
        String title = preview != null ? compact(preview, 180) : message ? payloadLabel(context, event.payload)
            : result && event.outcome != null ? WebSocketPresentation.outcome(context, event.outcome) : path(context, event.url);
        List<String> details = new ArrayList<>();
        if (!scoped) { details.add(message || result ? address(context, event.url) : new InspectorURL(event.url).origin); details.add(connection(context, event.connectionId)); }
        if (event.failure != null) details.add(WebSocketPresentation.failure(context, event.failure));
        if (event.closeCode != null) details.add(context.getString(R.string.osservio_ui_close_code_short) + " " + event.closeCode);
        if (message && event.payload != null && event.payload.state == WebSocketEvent.PayloadState.TRUNCATED) details.add(context.getString(R.string.osservio_ui_preview_partial));
        if (message && event.payload != null && event.payload.byteCount != null) details.add(InspectorValuePresentation.bytes(event.payload.byteCount));
        if (message && event.outcome != null && event.outcome != WebSocketEvent.Outcome.RECEIVED) details.add(WebSocketPresentation.outcome(context, event.outcome));
        if (result && event.relatedMessageId != null) details.add(context.getString(R.string.osservio_ui_related_message) + " · " + identity(event.relatedMessageId));
        return new Row(badge, title, joined(details.toArray(new String[0])), event.timestampMillis,
            event.kind == WebSocketEvent.Kind.FAILED || event.outcome == WebSocketEvent.Outcome.FAILED || event.outcome == WebSocketEvent.Outcome.REJECTED ? 2 : 0);
    }
    static String payloadLabel(Context context, WebSocketEvent.PayloadReference reference) {
        if (reference == null) return context.getString(R.string.osservio_ws_no_payload);
        int key;
        switch (reference.state) {
            case AVAILABLE: key = R.string.osservio_ui_message_text; break;
            case TRUNCATED: key = R.string.osservio_ui_message_truncated; break;
            case OMITTED: key = R.string.osservio_ui_private_content; break;
            case BINARY: key = R.string.osservio_ui_binary_message; break;
            case INVALID_TEXT: key = R.string.osservio_ui_unreadable_message; break;
            default: throw new IllegalStateException("Unknown payload state");
        }
        return context.getString(key);
    }
    static void prepareSearch(Context context, java.util.List<InspectorRecordSearch.Record> records) {
        for (InspectorRecordSearch.Record record : records) {
            if (record.log != null) record.displayText = joined(record.log.level == LogLevel.WARNING ? "WARN" : record.log.level.name(),
                record.log.isPrivate ? context.getString(R.string.osservio_ui_private_content) : "");
            else if (record.http != null) record.displayText = joined(address(context, record.http.url), HTTPPresentation.state(context, record.http.state),
                record.http.failure == null ? "" : HTTPPresentation.failure(context, record.http.failure));
            else record.displayText = joined(address(context, record.socket.url), WebSocketPresentation.kind(context, record.socket.kind),
                record.socket.direction == null ? "" : context.getString(record.socket.direction == WebSocketEvent.Direction.INCOMING ? R.string.osservio_ws_incoming : R.string.osservio_ws_outgoing),
                record.socket.outcome == null ? "" : WebSocketPresentation.outcome(context, record.socket.outcome),
                record.socket.kind == WebSocketEvent.Kind.MESSAGE ? payloadLabel(context, record.socket.payload) : "",
                record.socket.failure == null ? "" : WebSocketPresentation.failure(context, record.socket.failure));
        }
    }
    static String matchPreview(Context context, InspectorRecordSearch.Result result, String key) {
        if (result == null) return null; String preview = result.previews.get(key), source = result.previewSources.get(key);
        if (preview == null || source == null || source.isEmpty()) return preview;
        int label = source.equals("request") ? R.string.osservio_ui_search_request_body : source.equals("response") ? R.string.osservio_ui_search_response_body : R.string.osservio_ui_search_message;
        return context.getString(label) + ": " + preview;
    }
    static String context(Context context, LogEvent event) { return joined(event.level == LogLevel.WARNING ? "WARN" : event.level.name(), event.category, InspectorValuePresentation.eventTime(event.timestampMillis, false), context.getString(R.string.osservio_ui_record_identity) + " · " + identity(event.sessionId) + " #" + event.sequence); }
    static String context(Context context, HTTPEvent event) { return joined(event.method, context.getString(R.string.osservio_ui_record_identity) + " · " + identity(event.id), InspectorValuePresentation.eventTime(event.timestampMillis, false), address(context, event.url)); }
    static String context(Context context, WebSocketEvent event) {
        return joined(WebSocketPresentation.kind(context, event.kind), event.direction == null ? "" : context.getString(event.direction == WebSocketEvent.Direction.INCOMING ? R.string.osservio_ws_incoming : R.string.osservio_ws_outgoing),
            connection(context, event.connectionId), context.getString(R.string.osservio_ui_record_identity) + " · " + identity(event.id), InspectorValuePresentation.eventTime(event.timestampMillis, false));
    }
}
