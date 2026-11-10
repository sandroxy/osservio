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
    static List<InspectorFilterSheet.Group> network(Context context, List<HTTPEvent> http, List<InspectorQuery.Connection> sockets, int scope, InspectorQuery query) {
        List<InspectorFilterSheet.Group> groups = new ArrayList<>();
        Set<String> hosts = new TreeSet<>(), sources = new TreeSet<>();
        if (scope != 2) for (HTTPEvent event : http) { hosts.add(InspectorQuery.host(event.url)); sources.add(event.source.name()); }
        if (scope != 1) for (InspectorQuery.Connection connection : sockets) {
            hosts.add(InspectorQuery.host(connection.latest.url)); sources.add(connection.latest.source.name());
        }
        groups.add(strings(context, InspectorQuery.Field.HOST, R.string.osservio_ui_host, hosts));
        if (sources.size() > 1 || (query.values.containsKey(InspectorQuery.Field.SOURCE) && !query.values.get(InspectorQuery.Field.SOURCE).isEmpty())) {
            InspectorFilterSheet.Group source = group(context, InspectorQuery.Field.SOURCE, R.string.osservio_ui_source);
            for (String key : sources) source.option(key, source(context, key)); groups.add(source);
        }
        if (scope == 1) {
            InspectorFilterSheet.Group content = group(context, InspectorQuery.Field.CONTENT, R.string.osservio_ui_content);
            int[] labels = {R.string.osservio_ui_content_json, R.string.osservio_ui_content_text, R.string.osservio_ui_content_image,
                R.string.osservio_ui_content_media, R.string.osservio_ui_content_other, R.string.osservio_ui_content_unknown};
            for (int i = 0; i < labels.length; i++) content.option(InspectorHTTPContent.CATEGORIES[i], context.getString(labels[i]));
            groups.add(content);
            Set<String> methods = new TreeSet<>(); for (HTTPEvent event : http) methods.add(event.method);
            groups.add(strings(context, InspectorQuery.Field.METHOD, R.string.osservio_ui_method, methods));
            InspectorFilterSheet.Group states = group(context, InspectorQuery.Field.STATE, R.string.osservio_ui_state);
            for (HTTPEvent.State state : HTTPEvent.State.values()) states.option(state.name(), HTTPPresentation.state(context, state));
            groups.add(states);
            InspectorFilterSheet.Group codes = group(context, InspectorQuery.Field.STATUS, R.string.osservio_ui_status);
            for (int i = 1; i <= 5; i++) codes.option(i + "xx", i + "xx");
            codes.option("none", context.getString(R.string.osservio_ui_no_status)); groups.add(codes);
        } else if (scope == 2) {
            InspectorFilterSheet.Group states = group(context, InspectorQuery.Field.STATE, R.string.osservio_ui_connection_state);
            for (WebSocketEvent.Kind kind : WebSocketEvent.Kind.values()) {
                if (kind != WebSocketEvent.Kind.MESSAGE && kind != WebSocketEvent.Kind.MESSAGE_RESULT)
                    states.option(kind.name(), WebSocketPresentation.kind(context, kind));
            }
            states.option("unknown", context.getString(R.string.osservio_ui_unknown)); groups.add(states);
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
        for (String value : values) group.option(value, value.isEmpty() ? context.getString(R.string.osservio_ui_unknown) : value);
        return group;
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
        for (InspectorFilterSheet.Group group : groups) {
            Set<String> selected = query.values.get(group.field);
            if (selected == null || selected.isEmpty()) continue;
            List<String> labels = new ArrayList<>();
            for (String value : new TreeSet<>(selected)) {
                if (labels.size() == 2) break;
                String label = group.options.get(value); if (label == null) label = value;
                labels.add(label.length() > 48 ? label.substring(0, 48) + "…" : label);
            }
            parts.add(group.title + ": " + android.text.TextUtils.join(", ", labels) + (selected.size() > 2 ? " +" + (selected.size() - 2) : ""));
        }
        if (query.recentMinutes > 0) {
            int label = query.recentMinutes == 5 ? R.string.osservio_ui_time_5 : query.recentMinutes == 30 ? R.string.osservio_ui_time_30
                : query.recentMinutes == 60 ? R.string.osservio_ui_time_60 : R.string.osservio_ui_time_1440;
            parts.add(context.getString(label));
        }
        return parts.isEmpty() ? context.getString(R.string.osservio_ui_no_filters) : android.text.TextUtils.join(" · ", parts);
    }
}
