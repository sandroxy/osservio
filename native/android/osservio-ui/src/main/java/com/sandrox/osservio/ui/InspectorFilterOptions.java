package com.sandrox.osservio.ui;

import android.content.Context;
import com.sandrox.osservio.core.HTTPEvent;
import com.sandrox.osservio.core.LogEvent;
import com.sandrox.osservio.core.LogLevel;
import com.sandrox.osservio.core.WebSocketEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

final class InspectorFilterOptions {
    private InspectorFilterOptions() { }
    static InspectorFilterSheet.Group group(Context context, InspectorQuery.Field field, int label) {
        return new InspectorFilterSheet.Group(field, context.getString(label));
    }
    static List<InspectorFilterSheet.Group> logs(Context context, List<LogEvent> events) {
        List<InspectorFilterSheet.Group> groups = new ArrayList<>();
        InspectorFilterSheet.Group levels = group(context, InspectorQuery.Field.LEVEL, R.string.osservio_ui_level);
        for (LogLevel level : LogLevel.values()) levels.option(level.name(), level == LogLevel.WARNING ? "WARN" : level.name());
        groups.add(levels);
        Set<String> categories = new TreeSet<>(); for (LogEvent event : events) categories.add(event.category);
        groups.add(strings(context, InspectorQuery.Field.CATEGORY, R.string.osservio_ui_category, categories));
        return groups;
    }
    static List<InspectorFilterSheet.Group> network(Context context, List<HTTPEvent> http, List<WebSocketEvent> sockets, InspectorQuery query) {
        return network(context, http, sockets, query, true, true);
    }
    static List<InspectorFilterSheet.Group> network(Context context, List<HTTPEvent> http, List<WebSocketEvent> sockets, InspectorQuery query,
                                                   boolean includeHTTP, boolean includeSockets) {
        List<InspectorFilterSheet.Group> groups = new ArrayList<>();
        InspectorFilterSheet.Group transport = group(context, InspectorQuery.Field.TRANSPORT, R.string.osservio_ui_transport);
        if (includeHTTP) transport.option("http", "HTTP"); if (includeSockets) transport.option("webSocket", "WebSocket"); groups.add(transport);
        Set<String> hosts = new TreeSet<>(), sources = new TreeSet<>(), methods = new TreeSet<>(), connections = new TreeSet<>();
        Set<HTTPEvent.State> states = new TreeSet<>(); Set<WebSocketEvent.Kind> kinds = new TreeSet<>(); Set<String> contents = new TreeSet<>();
        for (HTTPEvent event : http) { hosts.add(InspectorQuery.host(event.url)); sources.add(event.source.name()); methods.add(event.method); states.add(event.state); contents.add(InspectorHTTPContent.category(event.responseHeaders)); }
        for (WebSocketEvent event : sockets) { hosts.add(InspectorQuery.host(event.url)); sources.add(event.source.name()); connections.add(event.connectionId); kinds.add(event.kind); }
        groups.add(strings(context, InspectorQuery.Field.HOST, R.string.osservio_ui_host, hosts));
        if (sources.size() > 1 || (query.values.containsKey(InspectorQuery.Field.SOURCE) && !query.values.get(InspectorQuery.Field.SOURCE).isEmpty())) {
            InspectorFilterSheet.Group source = group(context, InspectorQuery.Field.SOURCE, R.string.osservio_ui_source);
            for (String key : sources) source.option(key, source(context, key)); groups.add(source);
        }
        if (includeHTTP) {
            groups.add(strings(context, InspectorQuery.Field.METHOD, R.string.osservio_ui_http_method, methods));
            InspectorFilterSheet.Group codes = group(context, InspectorQuery.Field.STATUS, R.string.osservio_ui_http_status);
            for (int i = 1; i <= 5; i++) codes.option(i + "xx", label(context, InspectorQuery.Field.STATUS, i + "xx"));
            codes.option("none", context.getString(R.string.osservio_ui_no_status));
            Set<Integer> exactCodes = new TreeSet<>(); for (HTTPEvent event : http) if (event.statusCode != null) exactCodes.add(event.statusCode);
            for (Integer code : exactCodes) codes.option(code.toString(), code.toString());
            groups.add(codes);
            InspectorFilterSheet.Group state = group(context, InspectorQuery.Field.STATE, R.string.osservio_ui_http_state);
            for (HTTPEvent.State entry : states) state.option(entry.name(), HTTPPresentation.state(context, entry)); groups.add(state);
            InspectorFilterSheet.Group content = group(context, InspectorQuery.Field.CONTENT, R.string.osservio_ui_http_content);
            int[] labels = {R.string.osservio_ui_content_json, R.string.osservio_ui_content_text, R.string.osservio_ui_content_image,
                R.string.osservio_ui_content_media, R.string.osservio_ui_content_other, R.string.osservio_ui_content_unknown};
            for (int i = 0; i < labels.length; i++) if (contents.contains(InspectorHTTPContent.CATEGORIES[i])) content.option(InspectorHTTPContent.CATEGORIES[i], context.getString(labels[i]));
            groups.add(content);
        }
        if (includeSockets) {
            groups.add(group(context, InspectorQuery.Field.ACTIVITY, R.string.osservio_ui_activity)
                .option("messages", context.getString(R.string.osservio_ui_activity_messages)).option("connections", context.getString(R.string.osservio_ui_activity_connections)));
            groups.add(group(context, InspectorQuery.Field.DIRECTION, R.string.osservio_ui_socket_direction)
                .option("INCOMING", context.getString(R.string.osservio_ws_incoming)).option("OUTGOING", context.getString(R.string.osservio_ws_outgoing))
                );
            InspectorFilterSheet.Group kind = group(context, InspectorQuery.Field.KIND, R.string.osservio_ui_socket_kind);
            for (WebSocketEvent.Kind entry : kinds) kind.option(entry.name(), WebSocketPresentation.kind(context, entry)); groups.add(kind);
            Set<String> outcomes = new TreeSet<>(), closeCodes = new TreeSet<>();
            for (WebSocketEvent event : sockets) { if (event.outcome != null) outcomes.add(event.outcome.name()); if (event.closeCode != null) closeCodes.add(event.closeCode.toString()); }
            InspectorFilterSheet.Group outcome = group(context, InspectorQuery.Field.OUTCOME, R.string.osservio_ui_outcome);
            for (String value : outcomes) outcome.option(value, label(context, InspectorQuery.Field.OUTCOME, value)); groups.add(outcome);
            groups.add(strings(context, InspectorQuery.Field.CLOSE_CODE, R.string.osservio_ui_close_code, closeCodes));
            InspectorFilterSheet.Group connection = group(context, InspectorQuery.Field.CONNECTION, R.string.osservio_ui_connection);
            for (InspectorQuery.Connection entry : InspectorQuery.connections(sockets)) connection.option(entry.first.connectionId,
                InspectorRecordPresentation.address(context, entry.first.url) + "\n" + InspectorValuePresentation.eventTime(entry.first.timestampMillis, false) + " · " + InspectorRecordPresentation.identity(entry.first.connectionId));
            groups.add(connection);
        }
        return groups;
    }
    static List<InspectorFilterSheet.Group> timeline(Context context) {
        List<InspectorFilterSheet.Group> result = new ArrayList<>();
        result.add(group(context, InspectorQuery.Field.DIRECTION, R.string.osservio_ui_direction)
            .option("INCOMING", context.getString(R.string.osservio_ws_incoming))
            .option("OUTGOING", context.getString(R.string.osservio_ws_outgoing))
            .option("none", context.getString(R.string.osservio_ui_no_direction)));
        InspectorFilterSheet.Group kinds = group(context, InspectorQuery.Field.KIND, R.string.osservio_ui_kind);
        for (WebSocketEvent.Kind kind : WebSocketEvent.Kind.values()) kinds.option(kind.name(), WebSocketPresentation.kind(context, kind));
        result.add(kinds); return result;
    }
    private static InspectorFilterSheet.Group strings(Context context, InspectorQuery.Field field, int title, Set<String> values) {
        InspectorFilterSheet.Group group = group(context, field, title);
        for (String value : values) group.option(value, value.isEmpty() ? context.getString(R.string.osservio_ui_unknown) : field == InspectorQuery.Field.CONNECTION ? InspectorRecordPresentation.connection(context, value) : value);
        return group;
    }
    static String label(Context context, InspectorQuery.Field field, String value) {
        if (value.isEmpty()) return context.getString(R.string.osservio_ui_unknown);
        switch (field) {
            case STATUS:
                if (value.equals("none")) return context.getString(R.string.osservio_ui_no_status);
                String[] ranges = {"1xx", "2xx", "3xx", "4xx", "5xx"};
                int[] rangeLabels = {R.string.osservio_ui_status_1xx, R.string.osservio_ui_status_2xx, R.string.osservio_ui_status_3xx, R.string.osservio_ui_status_4xx, R.string.osservio_ui_status_5xx};
                for (int i = 0; i < ranges.length; i++) if (ranges[i].equals(value)) return context.getString(rangeLabels[i]); return value;
            case TRANSPORT: return value.equals("http") ? "HTTP" : "WebSocket";
            case LEVEL: return value.equals("WARNING") ? "WARN" : value;
            case SOURCE: return source(context, value);
            case CONNECTION: return InspectorRecordPresentation.connection(context, value);
            case ACTIVITY: return context.getString(value.equals("messages") ? R.string.osservio_ui_activity_messages : R.string.osservio_ui_activity_connections);
            case SEARCH_SCOPE:
                switch (value) {
                    case "address": return context.getString(R.string.osservio_ui_scope_address);
                    case "headers": return context.getString(R.string.osservio_ui_scope_headers);
                    case "request": return context.getString(R.string.osservio_ui_scope_request);
                    case "response": return context.getString(R.string.osservio_ui_scope_response);
                    case "message": return context.getString(R.string.osservio_ui_scope_message);
                    case "identity": return context.getString(R.string.osservio_ui_scope_identity);
                    default: return context.getString(R.string.osservio_ui_scope_metadata);
                }
            case STATE: return HTTPPresentation.state(context, HTTPEvent.State.valueOf(value));
            case OUTCOME: return WebSocketPresentation.outcome(context, WebSocketEvent.Outcome.valueOf(value));
            case DIRECTION: return context.getString(value.equals("none") ? R.string.osservio_ui_no_direction : value.equals("INCOMING") ? R.string.osservio_ws_incoming : R.string.osservio_ws_outgoing);
            default: return value;
        }
    }
    static List<InspectorFilterSheet.Group> ordered(List<InspectorFilterSheet.Group> groups) {
        List<InspectorFilterSheet.Group> result = new ArrayList<>();
        for (InspectorQuery.Field field : InspectorQuery.FIELD_ORDER) for (InspectorFilterSheet.Group group : groups) if (group.field == field) result.add(group);
        return result;
    }
    static String source(Context context, String value) {
        switch (value) {
            case "OKHTTP": return "OkHttp";
            case "URL_SESSION": return "URLSession";
            case "HOST": return context.getString(R.string.osservio_ws_host);
            default: return value;
        }
    }
    static String summary(Context context, InspectorQuery query, List<InspectorFilterSheet.Group> groups) {
        List<String> parts = new ArrayList<>();
        for (InspectorFilterSheet.Group group : ordered(groups)) {
            Set<String> selected = query.values.get(group.field);
            if (selected == null || selected.isEmpty()) continue;
            List<String> labels = new ArrayList<>();
            for (String value : new TreeSet<>(selected)) {
                if (labels.size() == 2) break;
                String label = group.options.get(value); if (label == null) label = label(context, group.field, value);
                labels.add(label.length() > 48 ? label.substring(0, 48) + "…" : label);
            }
            parts.add(group.title + ": " + android.text.TextUtils.join(", ", labels) + (selected.size() > 2 ? " +" + (selected.size() - 2) : ""));
        }
        if (query.recentMinutes > 0) {
            int label = query.recentMinutes == 5 ? R.string.osservio_ui_time_5 : query.recentMinutes == 30 ? R.string.osservio_ui_time_30
                : query.recentMinutes == 60 ? R.string.osservio_ui_time_60 : R.string.osservio_ui_time_1440;
            parts.add(context.getString(label));
        }
        if (!query.excludedText.isEmpty()) parts.add(context.getString(R.string.osservio_ui_exclude) + ": " + query.excludedText);
        if (!query.fieldPath.isEmpty()) parts.add(query.fieldPath + " = " + query.fieldValue);
        if (query.minimumDurationMillis > 0) parts.add("HTTP ≥ " + query.minimumDurationMillis + " ms");
        return parts.isEmpty() ? context.getString(R.string.osservio_ui_no_filters) : android.text.TextUtils.join(" · ", parts);
    }
}
