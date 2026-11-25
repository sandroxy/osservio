package com.sandrox.osservio.ui;

import android.content.Context;
import android.text.TextUtils;
import java.util.ArrayList;
import java.util.List;
import com.sandrox.osservio.core.IncidentSnapshot;
import com.sandrox.osservio.core.IncidentSummary;
import com.sandrox.osservio.core.SystemDiagnostic;

final class IncidentPresentation {
    private IncidentPresentation() { }
    static String title(Context context, SystemDiagnostic.Kind kind) {
        return kind == null ? context.getString(R.string.osservio_incident_manual) : SystemDiagnosticPresentation.kind(context, kind);
    }
    static String listTitle(Context context, IncidentSummary summary) {
        return summary.note != null && !summary.note.isPrivate && !summary.note.text.isEmpty()
            ? summary.note.text : title(context, summary.diagnosticKind);
    }
    static String listSubtitle(Context context, IncidentSummary summary) {
        List<String> evidence = new ArrayList<>(), incomplete = new ArrayList<>(), empty = new ArrayList<>(), zeroRetained = new ArrayList<>();
        if (summary.hasEnvironment) evidence.add(context.getString(R.string.osservio_runtime_environment));
        int[] labels = {R.string.osservio_runtime_breadcrumbs, R.string.osservio_logs, R.string.osservio_ui_http, R.string.osservio_ws_title};
        Integer[] counts = {summary.breadcrumbCount, summary.logCount, summary.httpCount, summary.webSocketCount};
        Integer[] omissions = {summary.omitted.breadcrumbs, summary.omitted.logs, summary.omitted.http, summary.omitted.webSockets};
        for (int i = 0; i < labels.length; i++) {
            Integer retained = counts[i], omitted = omissions[i];
            if (retained == null) continue;
            String name = context.getString(labels[i]);
            if (omitted != null && omitted > 0) incomplete.add(context.getString(R.string.osservio_incident_summary_omitted, name, retained, omitted));
            else if (retained > 0) evidence.add(name + ": " + retained);
            else if (omitted != null) empty.add(name);
            else zeroRetained.add(name);
        }
        if (!empty.isEmpty()) evidence.add(context.getString(R.string.osservio_incident_summary_empty, TextUtils.join(" / ", empty)));
        if (!zeroRetained.isEmpty()) evidence.add(context.getString(R.string.osservio_incident_summary_zero_retained, TextUtils.join(" / ", zeroRetained)));
        evidence.addAll(0, incomplete);
        return (summary.note != null && summary.note.isPrivate ? context.getString(R.string.osservio_incident_private_note) + "\n" : "")
            + (evidence.isEmpty() ? context.getString(R.string.osservio_incident_no_attachments) : TextUtils.join(" · ", evidence));
    }
    static String listTime(Context context, IncidentSummary summary) {
        return (summary.diagnosticKind == null ? "" : timeLabel(context, summary.diagnosticKind) + "\n")
            + InspectorValuePresentation.eventTime(summary.timestampMillis, true).substring(0, 19);
    }
    static String timeLabel(Context context, SystemDiagnostic.Kind kind) {
        return context.getString(kind == null ? R.string.osservio_incident_frozen_time : R.string.osservio_diagnostic_recovered_at);
    }
    static String hint(Context context, IncidentSnapshot snapshot) {
        return context.getString(snapshot.systemDiagnostic == null ? R.string.osservio_incident_snapshot_hint : R.string.osservio_diagnostic_snapshot_hint);
    }
    static String listDetail(Context context, IncidentSummary summary) {
        if (summary.diagnosticKind == null) return context.getString(R.string.osservio_incident_row_detail, note(context, summary.note), attachments(context, summary));
        return timeLabel(context, summary.diagnosticKind) + ": " + RuntimePresentation.date(context, summary.timestampMillis)
            + (summary.note == null ? "" : "\n" + note(context, summary.note));
    }
    static String note(Context context, IncidentSnapshot.Note note) {
        if (note == null) return context.getString(R.string.osservio_incident_no_note);
        return (note.isPrivate ? context.getString(R.string.osservio_incident_private_note) : note.text)
            + (note.isTruncated ? "\n" + context.getString(R.string.osservio_truncated) : "");
    }
    static String counts(Context context, IncidentSnapshot.Breadcrumbs breadcrumbs) {
        return breadcrumbs == null ? context.getString(R.string.osservio_incident_not_attached)
            : context.getString(R.string.osservio_incident_counts, breadcrumbs.events.size(), breadcrumbs.omittedEventCount, breadcrumbs.sourceEvictedCount);
    }
    static String breadcrumbSummary(Context context, IncidentSnapshot.Breadcrumbs breadcrumbs) {
        if (breadcrumbs == null) return context.getString(R.string.osservio_incident_not_attached);
        return breadcrumbs.omittedEventCount > 0 || breadcrumbs.sourceEvictedCount > 0
            ? context.getString(R.string.osservio_incident_evidence_counts, breadcrumbs.events.size(), breadcrumbs.omittedEventCount, breadcrumbs.sourceEvictedCount)
            : context.getString(R.string.osservio_ui_record_count, breadcrumbs.events.size());
    }
    static String attachments(Context context, IncidentSummary summary) {
        return context.getString(R.string.osservio_runtime_environment) + ": "
            + context.getString(summary.hasEnvironment ? R.string.osservio_runtime_yes : R.string.osservio_incident_not_attached)
            + " · " + context.getString(R.string.osservio_runtime_breadcrumbs) + ": "
            + count(context, summary.breadcrumbCount) + "\n" + context.getString(R.string.osservio_logs) + ": " + count(context, summary.logCount)
            + " · HTTP: " + count(context, summary.httpCount) + " · " + context.getString(R.string.osservio_ws_title) + ": " + count(context, summary.webSocketCount);
    }
    static String count(Context context, Integer count) { return count == null ? context.getString(R.string.osservio_incident_not_attached) : count.toString(); }
    static String selectionWindow(Context context, long time, int count, int omitted, long evicted, Long payloadEvicted) {
        return InspectorValuePresentation.eventTime(time, false) + " · " + context.getString(R.string.osservio_ui_record_count, count)
            + (omitted > 0 || evicted > 0 || payloadEvicted != null && payloadEvicted > 0 ? "\n" + context.getString(R.string.osservio_ui_retention_loss) : "");
    }
    static String window(Context context, long time, int count, int omitted, long evicted, Long payloadEvicted) {
        return context.getString(R.string.osservio_incident_evidence_time, RuntimePresentation.date(context, time)) + "\n"
            + context.getString(R.string.osservio_incident_evidence_counts, count, omitted, evicted)
            + (payloadEvicted == null ? "" : "\n" + context.getString(R.string.osservio_incident_evidence_payload_evicted, payloadEvicted));
    }
    static InspectorRecordSearch.Record focusRecord(IncidentSnapshot snapshot) {
        if (snapshot.focus == null) return null;
        if (snapshot.logs != null) for (com.sandrox.osservio.core.LogEvent event : snapshot.logs.events) if (snapshot.focus.matches(event)) return new InspectorRecordSearch.Record(event);
        if (snapshot.http != null) for (com.sandrox.osservio.core.HTTPEvent event : snapshot.http.events) if (snapshot.focus.matches(event)) return new InspectorRecordSearch.Record(event);
        if (snapshot.webSockets != null) for (com.sandrox.osservio.core.WebSocketEvent event : snapshot.webSockets.events) if (snapshot.focus.matches(event)) return new InspectorRecordSearch.Record(event);
        return null;
    }
    static String focus(Context context, IncidentSnapshot snapshot) {
        InspectorRecordSearch.Record record = focusRecord(snapshot);
        if (record == null) return "";
        InspectorRecordPresentation.Row row = record.log != null ? InspectorRecordPresentation.log(context, record.log, null)
            : record.http != null ? InspectorRecordPresentation.http(context, record.http, null) : InspectorRecordPresentation.socket(context, record.socket, null, false);
        return row.badge + " · " + InspectorValuePresentation.eventTime(row.time, true) + "\n" + row.title;
    }
    static String summary(Context context, IncidentSnapshot snapshot) {
        SystemDiagnostic.Kind kind = snapshot.systemDiagnostic == null ? null : snapshot.systemDiagnostic.kind;
        StringBuilder value = new StringBuilder(title(context, kind));
        value.append("\n\n").append(timeLabel(context, kind)).append(": ").append(RuntimePresentation.date(context, snapshot.timestampMillis));
        value.append("\n\nID: ").append(snapshot.id).append("\n\n").append(note(context, snapshot.note));
        value.append("\n\n").append(context.getString(R.string.osservio_runtime_breadcrumbs)).append(": ").append(counts(context, snapshot.breadcrumbs));
        if (snapshot.focus != null) value.append("\n\n").append(context.getString(R.string.osservio_ui_selected_record)).append(": ")
            .append(focus(context, snapshot)).append("\n").append(snapshot.focus.sessionId).append(" · #").append(snapshot.focus.sequence)
            .append(snapshot.focus.eventId == null ? "" : " · " + snapshot.focus.eventId);
        if (snapshot.systemDiagnostic != null) value.append("\n\n").append(SystemDiagnosticPresentation.summary(context, snapshot.systemDiagnostic));
        value.append("\n\n").append(snapshot.environment == null
            ? context.getString(R.string.osservio_runtime_environment) + ": " + context.getString(R.string.osservio_incident_not_attached)
            : RuntimePresentation.environment(context, snapshot.environment));
        value.append("\n\n").append(context.getString(R.string.osservio_logs)).append(": ").append(snapshot.logs == null ? count(context, null)
            : window(context, snapshot.logs.observedAtMillis, snapshot.logs.events.size(), snapshot.logs.omittedEventCount, snapshot.logs.sourceEvictedCount, null));
        value.append("\n\nHTTP: ").append(snapshot.http == null ? count(context, null)
            : window(context, snapshot.http.observedAtMillis, snapshot.http.events.size(), snapshot.http.omittedEventCount, snapshot.http.sourceEvictedEvents, snapshot.http.sourceEvictedBodies));
        value.append("\n\n").append(context.getString(R.string.osservio_ws_title)).append(": ").append(snapshot.webSockets == null ? count(context, null)
            : window(context, snapshot.webSockets.observedAtMillis, snapshot.webSockets.events.size(), snapshot.webSockets.omittedEventCount, snapshot.webSockets.sourceEvictedEvents, snapshot.webSockets.sourceEvictedPayloads));
        return value.append("\n\n").append(hint(context, snapshot)).toString();
    }
}
