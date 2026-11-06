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
            + (summary.breadcrumbCount == null ? context.getString(R.string.osservio_incident_not_attached) : summary.breadcrumbCount.toString());
    }
    static String summary(Context context, IncidentSnapshot snapshot) {
        StringBuilder value = new StringBuilder(context.getString(R.string.osservio_incident_manual));
        value.append("\n\n").append(context.getString(R.string.osservio_incident_frozen_time)).append(": ").append(RuntimePresentation.date(snapshot.timestampMillis));
        value.append("\n\nID: ").append(snapshot.id).append("\n\n").append(note(context, snapshot.note));
        value.append("\n\n").append(context.getString(R.string.osservio_runtime_breadcrumbs)).append(": ").append(counts(context, snapshot.breadcrumbs));
        value.append("\n\n").append(snapshot.environment == null
            ? context.getString(R.string.osservio_runtime_environment) + ": " + context.getString(R.string.osservio_incident_not_attached)
            : RuntimePresentation.environment(context, snapshot.environment));
        return value.append("\n\n").append(context.getString(R.string.osservio_incident_snapshot_hint)).toString();
    }
}
