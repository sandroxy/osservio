package com.sandrox.osservio.ui;

import android.content.Context;
import android.widget.Toast;
import com.sandrox.osservio.core.*;

/** The Inspector owns recording; detail pages never discover host services globally. */
final class InspectorIncidentRecording {
    private final IncidentArchive archive;
    private final EnvironmentProvider environment;
    private final BreadcrumbHistory breadcrumbs;
    private final LogDetailsHistory logs;
    private final HTTPHistory http;
    private final WebSocketHistory sockets;
    private IncidentComposer composer;
    private interface Mark { IncidentSnapshot get(); }
    InspectorIncidentRecording(IncidentArchive archive, EnvironmentProvider environment, BreadcrumbHistory breadcrumbs,
                               LogDetailsHistory logs, HTTPHistory http, WebSocketHistory sockets) {
        this.archive = archive; this.environment = environment; this.breadcrumbs = breadcrumbs;
        this.logs = logs; this.http = http; this.sockets = sockets;
    }
    static InspectorIncidentRecording from(Context context) {
        InspectorNavigation navigation = InspectorNavigation.from(context);
        return navigation == null ? null : navigation.incidentRecording;
    }
    void bind(Context context, InspectorScreen screen, LogEvent event) { bind(context, screen, () -> InspectorIncidentSelection.mark(event, breadcrumbs.snapshot())); }
    void bind(Context context, InspectorScreen screen, HTTPEvent event) { bind(context, screen, () -> InspectorIncidentSelection.mark(event, breadcrumbs.snapshot())); }
    void bind(Context context, InspectorScreen screen, WebSocketEvent event) { bind(context, screen, () -> InspectorIncidentSelection.mark(event, breadcrumbs.snapshot())); }
    private void bind(Context context, InspectorScreen screen, Mark mark) {
        screen.iconAction(R.drawable.osservio_problems, R.string.osservio_incident_from_record, () -> {
            if (composer != null || !screen.view.isShown()) return;
            final IncidentSnapshot snapshot;
            try { snapshot = mark.get(); }
            catch (InspectorIncidentSelection.TooLarge failure) {
                Toast.makeText(context, R.string.osservio_incident_selection_failed, Toast.LENGTH_LONG).show(); return;
            } catch (IllegalArgumentException failure) {
                Toast.makeText(context, R.string.osservio_incident_mark_failed, Toast.LENGTH_LONG).show(); return;
            }
            composer = new IncidentComposer(context, snapshot, archive, environment, http, sockets, logs,
                () -> { if (screen.view.isShown()) Toast.makeText(context, R.string.osservio_incident_saved, Toast.LENGTH_SHORT).show(); }, () -> composer = null);
            composer.show();
        });
    }
}
