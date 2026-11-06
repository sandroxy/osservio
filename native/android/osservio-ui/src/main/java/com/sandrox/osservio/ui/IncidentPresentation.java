package com.sandrox.osservio.ui;

import android.content.Context;
import com.sandrox.osservio.core.IncidentSnapshot;
import com.sandrox.osservio.core.IncidentSummary;

final class IncidentPresentation {
    private IncidentPresentation() { }
    static String note(Context context, IncidentSnapshot.Note note) {
        if (note == null) return context.getString(R.string.osservio_incident_no_note);
        return note.text + (note.isPrivate ? "\n" + context.getString(R.string.osservio_crumb_private) : "")
            + (note.isTruncated ? "\n" + context.getString(R.string.osservio_truncated) : "");
    }
    static String counts(Context context, IncidentSnapshot.Breadcrumbs breadcrumbs) {
        return breadcrumbs == null ? context.getString(R.string.osservio_incident_not_attached)
            : context.getString(R.string.osservio_incident_counts, breadcrumbs.events.size(), breadcrumbs.omittedEventCount, breadcrumbs.sourceEvictedCount);
    }
    static String attachments(Context context, IncidentSummary summary) {
        return context.getString(R.string.osservio_runtime_environment) + ": "
            + context.getString(summary.hasEnvironment ? R.string.osservio_runtime_yes : R.string.osservio_incident_not_attached)
            + " · " + context.getString(R.string.osservio_runtime_breadcrumbs) + ": "
            + count(context, summary.breadcrumbCount) + "\n" + context.getString(R.string.osservio_logs) + ": " + count(context, summary.logCount)
            + " · HTTP: " + count(context, summary.httpCount) + " · " + context.getString(R.string.osservio_ws_title) + ": " + count(context, summary.webSocketCount);
    }
    static String count(Context context, Integer count) { return count == null ? context.getString(R.string.osservio_incident_not_attached) : count.toString(); }
    static String window(Context context, long time, int count, int omitted, long evicted, Long payloadEvicted) {
        return context.getString(R.string.osservio_incident_evidence_time, RuntimePresentation.date(time)) + "\n"
            + context.getString(R.string.osservio_incident_evidence_counts, count, omitted, evicted)
            + (payloadEvicted == null ? "" : "\n" + context.getString(R.string.osservio_incident_evidence_payload_evicted, payloadEvicted));
    }
    static String summary(Context context, IncidentSnapshot snapshot) {
        StringBuilder value = new StringBuilder(context.getString(R.string.osservio_incident_manual));
        value.append("\n\n").append(context.getString(R.string.osservio_incident_frozen_time)).append(": ").append(RuntimePresentation.date(snapshot.timestampMillis));
        value.append("\n\nID: ").append(snapshot.id).append("\n\n").append(note(context, snapshot.note));
        value.append("\n\n").append(context.getString(R.string.osservio_runtime_breadcrumbs)).append(": ").append(counts(context, snapshot.breadcrumbs));
        value.append("\n\n").append(snapshot.environment == null
            ? context.getString(R.string.osservio_runtime_environment) + ": " + context.getString(R.string.osservio_incident_not_attached)
            : RuntimePresentation.environment(context, snapshot.environment));
        value.append("\n\n").append(context.getString(R.string.osservio_logs)).append(": ").append(snapshot.logs == null ? count(context, null)
            : window(context, snapshot.logs.observedAtMillis, snapshot.logs.events.size(), snapshot.logs.omittedEventCount, snapshot.logs.sourceEvictedCount, null));
        value.append("\n\nHTTP: ").append(snapshot.http == null ? count(context, null)
            : window(context, snapshot.http.observedAtMillis, snapshot.http.events.size(), snapshot.http.omittedEventCount, snapshot.http.sourceEvictedEvents, snapshot.http.sourceEvictedBodies));
        value.append("\n\n").append(context.getString(R.string.osservio_ws_title)).append(": ").append(snapshot.webSockets == null ? count(context, null)
            : window(context, snapshot.webSockets.observedAtMillis, snapshot.webSockets.events.size(), snapshot.webSockets.omittedEventCount, snapshot.webSockets.sourceEvictedEvents, snapshot.webSockets.sourceEvictedPayloads));
        return value.append("\n\n").append(context.getString(R.string.osservio_incident_snapshot_hint)).toString();
    }
}
