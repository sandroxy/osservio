package com.sandrox.osservio.ui;

import com.sandrox.osservio.core.HTTPEvent;
import com.sandrox.osservio.core.LogEvent;
import com.sandrox.osservio.core.WebSocketEvent;
import com.sandrox.osservio.core.IncidentSummary;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** View-only criteria: OR within one field, AND between independent fields. */
final class InspectorQuery {
    enum Field { LEVEL, CATEGORY, METHOD, STATE, STATUS, HOST, SOURCE, DIRECTION, KIND, RECORD, CONTENT, TRANSPORT, CONNECTION, ACTIVITY, OUTCOME, CLOSE_CODE, SEARCH_SCOPE }
    static final Field[] FIELD_ORDER = {Field.TRANSPORT, Field.LEVEL, Field.RECORD, Field.HOST, Field.CATEGORY,
        Field.METHOD, Field.STATUS, Field.STATE, Field.ACTIVITY, Field.DIRECTION, Field.KIND, Field.OUTCOME,
        Field.CLOSE_CODE, Field.CONNECTION, Field.CONTENT, Field.SOURCE, Field.SEARCH_SCOPE};
    String text = "";
    String excludedText = "", fieldPath = "", fieldValue = "";
    long minimumDurationMillis;
    int recentMinutes;
    final Map<Field, Set<String>> values = new EnumMap<>(Field.class);
    InspectorQuery copy() {
        InspectorQuery result = new InspectorQuery(); result.text = text; result.recentMinutes = recentMinutes;
        result.excludedText = excludedText; result.fieldPath = fieldPath; result.fieldValue = fieldValue; result.minimumDurationMillis = minimumDurationMillis;
        for (Map.Entry<Field, Set<String>> entry : values.entrySet()) result.values.put(entry.getKey(), new HashSet<>(entry.getValue()));
        return result;
    }
    int activeCount() {
        int result = (recentMinutes > 0 ? 1 : 0) + (minimumDurationMillis > 0 ? 1 : 0)
            + (fieldPath.isEmpty() ? 0 : 1) + (excludedText.isEmpty() ? 0 : 1);
        for (Set<String> selected : values.values()) if (!selected.isEmpty()) result++;
        return result;
    }
    boolean allows(Field field, String value) {
        Set<String> selected = values.get(field);
        return selected == null || selected.isEmpty() || selected.contains(value);
    }
    boolean searchesPayload() { return !text.isEmpty() || !excludedText.isEmpty() || !fieldPath.isEmpty(); }
    static final Field[] HTTP_FIELDS = {Field.METHOD, Field.STATE, Field.STATUS, Field.CONTENT};
    static final Field[] SOCKET_FIELDS = {Field.ACTIVITY, Field.DIRECTION, Field.KIND, Field.CONNECTION, Field.OUTCOME, Field.CLOSE_CODE};
    String searchScope() { Set<String> selected = values.get(Field.SEARCH_SCOPE); return selected == null || selected.isEmpty() ? "all" : selected.iterator().next(); }
    String transport() { Set<String> selected = values.get(Field.TRANSPORT); return selected == null || selected.isEmpty() ? null : selected.iterator().next(); }
    boolean searches(String scope) { return searchScope().equals("all") || searchScope().equals(scope); }
    boolean allowsStatus(Integer code) {
        Set<String> selected = values.get(Field.STATUS);
        return selected == null || selected.isEmpty() || (code == null ? selected.contains("none")
            : selected.contains(code.toString()) || selected.contains(code / 100 + "xx"));
    }
    void select(Field field, String value) {
        if (field == Field.TRANSPORT) {
            if (value == null) values.remove(field); else values.put(field, new HashSet<>(Collections.singleton(value)));
            if (!"http".equals(value)) { for (Field key : HTTP_FIELDS) values.remove(key); minimumDurationMillis = 0; }
            if (!"webSocket".equals(value)) for (Field key : SOCKET_FIELDS) values.remove(key);
            if (!"http".equals(value) && (searchScope().equals("request") || searchScope().equals("response"))) values.remove(Field.SEARCH_SCOPE);
            if (!"webSocket".equals(value) && searchScope().equals("message")) values.remove(Field.SEARCH_SCOPE);
            return;
        }
        if (field == Field.SEARCH_SCOPE || field == Field.ACTIVITY) {
            if (value == null) values.remove(field); else values.put(field, new HashSet<>(Collections.singleton(value)));
            if (field == Field.ACTIVITY && "connections".equals(value)) { values.remove(Field.DIRECTION); values.remove(Field.OUTCOME); }
            if (field == Field.ACTIVITY && value != null) {
                if (value.equals("messages")) values.remove(Field.CLOSE_CODE);
                Set<String> kinds = values.get(Field.KIND);
                if (kinds != null) { java.util.Iterator<String> iterator = kinds.iterator(); while (iterator.hasNext()) {
                    String kind = iterator.next(); if ((kind.equals("MESSAGE") || kind.equals("MESSAGE_RESULT")) != value.equals("messages")) iterator.remove();
                } }
            }
            return;
        }
        if (value != null) {
            for (Field key : HTTP_FIELDS) if (key == field) select(Field.TRANSPORT, "http");
            for (Field key : SOCKET_FIELDS) if (key == field) select(Field.TRANSPORT, "webSocket");
        }
        if (value == null) { values.remove(field); return; }
        Set<String> selected = values.get(field);
        if (selected == null) { selected = new HashSet<>(); values.put(field, selected); }
        if (!selected.remove(value)) selected.add(value);
    }
    void resetFilters() {
        values.clear(); recentMinutes = 0; minimumDurationMillis = 0; fieldPath = ""; fieldValue = ""; excludedText = "";
    }
    private boolean empty(Field[] fields) {
        for (Field field : fields) if (values.containsKey(field) && !values.get(field).isEmpty()) return false;
        return true;
    }
    boolean includesTime(long timestamp, long now) {
        if (recentMinutes <= 0) return true;
        long duration = Math.min(recentMinutes, 1440) * 60000L;
        long lower = now < Long.MIN_VALUE + duration ? Long.MIN_VALUE : now - duration;
        return timestamp >= lower && timestamp <= now;
    }
    boolean contains(String... candidates) {
        if (text.isEmpty()) return true;
        String query = text.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) if (candidate != null && candidate.toLowerCase(Locale.ROOT).contains(query)) return true;
        return false;
    }
    boolean matches(LogEvent event, long now) {
        return matchesFacets(event, now) && contains(event.message, event.category)
            && (excludedText.isEmpty() || !hasText(event.message, excludedText) && !hasText(event.category, excludedText));
    }
    boolean matchesFacets(LogEvent event, long now) {
        return allows(Field.LEVEL, event.level.name()) && allows(Field.CATEGORY, event.category) && includesTime(event.timestampMillis, now);
    }
    boolean matches(HTTPEvent event, long now) {
        return matchesFacets(event, now) && contains(event.url, event.responseURL, event.method, event.statusCode == null ? null : event.statusCode.toString());
    }
    boolean matchesFacets(HTTPEvent event, long now) {
        return allows(Field.TRANSPORT, "http") && allows(Field.METHOD, event.method) && allows(Field.STATE, event.state.name())
            && allowsStatus(event.statusCode)
            && allows(Field.HOST, host(event.url)) && allows(Field.SOURCE, event.source.name())
            && allows(Field.CONTENT, InspectorHTTPContent.category(event.responseHeaders))
            && (minimumDurationMillis == 0 || event.durationMillis != null && event.durationMillis >= minimumDurationMillis)
            && empty(SOCKET_FIELDS) && includesTime(event.timestampMillis, now);
    }
    boolean matches(WebSocketEvent event, long now) {
        return matchesFacets(event, now) && contains(event.url, event.id, event.connectionId, event.kind.name(), event.outcome == null ? null : event.outcome.name());
    }
    boolean matchesFacets(WebSocketEvent event, long now) {
        return allows(Field.TRANSPORT, "webSocket") && allows(Field.CONNECTION, event.connectionId)
            && allows(Field.ACTIVITY, event.kind == WebSocketEvent.Kind.MESSAGE || event.kind == WebSocketEvent.Kind.MESSAGE_RESULT ? "messages" : "connections")
            && allows(Field.DIRECTION, event.direction == null ? "none" : event.direction.name())
            && allows(Field.OUTCOME, event.outcome == null ? "none" : event.outcome.name())
            && allows(Field.CLOSE_CODE, event.closeCode == null ? "none" : event.closeCode.toString())
            && allows(Field.KIND, event.kind.name()) && allows(Field.HOST, host(event.url))
            && allows(Field.SOURCE, event.source.name()) && empty(HTTP_FIELDS) && minimumDurationMillis == 0 && includesTime(event.timestampMillis, now);
    }
    boolean matches(Connection connection, long now) {
        return allows(Field.STATE, connection.state()) && allows(Field.HOST, host(connection.latest.url))
            && allows(Field.SOURCE, connection.latest.source.name()) && includesTime(connection.latest.timestampMillis, now)
            && contains(connection.latest.url, connection.latest.connectionId);
    }
    boolean matches(IncidentSummary incident, long now) { return matches(incident, now, ""); }
    boolean matches(IncidentSummary incident, long now, String visibleKind) {
        return allows(Field.RECORD, incident.diagnosticKind == null ? "manual" : incident.diagnosticKind.name())
            && includesTime(incident.timestampMillis, now)
            && contains(incident.id, incident.note == null || incident.note.isPrivate ? null : incident.note.text, visibleKind,
                incident.diagnosticKind == null ? "manual" : incident.diagnosticKind.name());
    }
    static boolean hasText(String text, String query) {
        if (text == null) return false;
        for (int i = 0; i <= text.length() - query.length(); i++) if (text.regionMatches(true, i, query, 0, query.length())) return true;
        return false;
    }
    static String host(String url) {
        try { String host = new URI(url).getHost(); return host == null ? "" : host.toLowerCase(Locale.ROOT); }
        catch (URISyntaxException invalid) { return ""; }
    }
    /** Preserves capture order for timestamp ties within a session; IDs do not imply cross-session causality. */
    static int compareTimeline(WebSocketEvent left, WebSocketEvent right) {
        if (left.timestampMillis != right.timestampMillis) return Long.compare(right.timestampMillis, left.timestampMillis);
        if (!left.sessionId.equals(right.sessionId)) return left.sessionId.compareTo(right.sessionId);
        return left.sequence == right.sequence ? left.id.compareTo(right.id) : Long.compare(right.sequence, left.sequence);
    }
    static String path(String url) {
        try {
            URI uri = new URI(url); if (uri.getHost() == null) return url;
            String path = uri.getRawPath(); if (path == null || path.isEmpty()) path = "/";
            return path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (URISyntaxException invalid) { return url; }
    }

    /** Last retained lifecycle observation, never inferred from message success or failure. */
    static final class Connection {
        final WebSocketEvent first;
        final WebSocketEvent latest;
        final WebSocketEvent lifecycle;
        final int count;
        Connection(List<WebSocketEvent> ordered) {
            first = ordered.get(0); latest = ordered.get(ordered.size() - 1); count = ordered.size();
            WebSocketEvent observed = null;
            for (WebSocketEvent event : ordered) if (event.kind != WebSocketEvent.Kind.MESSAGE && event.kind != WebSocketEvent.Kind.MESSAGE_RESULT) observed = event;
            lifecycle = observed;
        }
        String state() { return lifecycle == null ? "unknown" : lifecycle.kind.name(); }
    }
    static List<Connection> connections(List<WebSocketEvent> events) {
        Map<String, List<WebSocketEvent>> groups = new LinkedHashMap<>();
        for (WebSocketEvent event : events) {
            List<WebSocketEvent> group = groups.get(event.connectionId);
            if (group == null) { group = new ArrayList<>(); groups.put(event.connectionId, group); }
            group.add(event);
        }
        List<Connection> result = new ArrayList<>();
        for (List<WebSocketEvent> group : groups.values()) {
            Collections.sort(group, (left, right) -> left.sequence == right.sequence
                ? left.id.compareTo(right.id) : Long.compare(left.sequence, right.sequence));
            result.add(new Connection(group));
        }
        Collections.sort(result, (left, right) -> left.first.timestampMillis == right.first.timestampMillis
            ? left.first.connectionId.compareTo(right.first.connectionId) : Long.compare(right.first.timestampMillis, left.first.timestampMillis));
        return result;
    }
}
