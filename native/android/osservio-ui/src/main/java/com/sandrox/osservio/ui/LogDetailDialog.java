package com.sandrox.osservio.ui;

import android.content.Context;
import com.sandrox.osservio.core.LogEvent;
import com.sandrox.osservio.core.LogDetailsHistory;
import com.sandrox.osservio.core.LogDetailsReference;
import com.sandrox.osservio.core.IncidentPayload;
import java.util.Map;

/** Metadata is immutable; context content is read only on demand from live or saved evidence. */
final class LogDetailDialog {
    private LogDetailDialog() { }
    static InspectorScreen create(Context context, LogEvent event) {
        return create(context, event, null, null);
    }
    static InspectorScreen create(Context context, LogEvent event, LogDetailsHistory history, Map<String, IncidentPayload> saved) {
        return create(context, event, history, saved, null);
    }
    static InspectorScreen create(Context context, LogEvent event, LogDetailsHistory history, Map<String, IncidentPayload> saved, InspectorRecordSearch.Hit hit) {
        return create(context, event, history, saved, hit, null);
    }
    static InspectorScreen create(Context context, LogEvent event, LogDetailsHistory history, Map<String, IncidentPayload> saved,
                                  InspectorRecordSearch.Hit hit, InspectorLogContext neighbors) {
        InspectorDetailPage page = new InspectorDetailPage(context);
        page.section(R.string.osservio_ui_read_content);
        InspectorWidgets.ValueRow reader = page.value(() -> RuntimePresentation.message(context, InspectorRecordSearch.Hit.preview(hit, "log", event.message, 600), event.isPrivate),
            event.isPrivate ? null : () -> page.read(R.string.osservio_ui_log_content, new InspectorReadContent(event.message, () -> event.isTruncated ? context.getString(R.string.osservio_truncated) : "", !event.isTruncated && !event.isPrivate).withSearch(hit, "log").withContext(() -> InspectorRecordPresentation.context(context, event))));
        Runnable label = () -> reader.setContentDescription(context.getString(R.string.osservio_ui_open_reader));
        if (!event.isPrivate) { InspectorAppearance.content(page, label); label.run(); }
        if (event.isPrivate) page.note(R.string.osservio_crumb_private);
        if (event.isTruncated) page.note(R.string.osservio_truncated);
        if (event.details != null) {
            page.section(R.string.osservio_ui_log_context);
            page.value(() -> context.getString(InspectorLogDetails.label(event.details.state)),
                event.details.state == LogDetailsReference.State.AVAILABLE ? () -> page.read(R.string.osservio_ui_log_context,
                    InspectorLogDetails.content(context, event, history, saved).withSearch(hit, "logDetails")) : null);
        }
        page.section(R.string.osservio_ui_overview);
        page.field(R.string.osservio_ui_level, event.level == com.sandrox.osservio.core.LogLevel.WARNING ? "WARN" : event.level.name());
        page.field(R.string.osservio_ui_category, event.category);
        page.field(R.string.osservio_ui_event_recorded, () -> InspectorValuePresentation.eventTime(event.timestampMillis, true));
        if (neighbors != null) page.action(R.string.osservio_ui_log_neighbors, () ->
            InspectorDialogs.screen(context, R.string.osservio_ui_log_neighbors, new LogContextView(context, neighbors, history, saved)).show());
        page.expandable(R.string.osservio_ui_technical_details, R.string.osservio_ui_show_details, R.string.osservio_ui_hide_details,
            () -> page.field(R.string.osservio_http_session, event.sessionId + " · #" + event.sequence));
        InspectorScreen screen = InspectorDialogs.screen(context, R.string.osservio_ui_log_details, page);
        screen.iconAction(R.drawable.osservio_copy, R.string.osservio_copy, () -> {
            android.content.ClipboardManager clipboard = context.getSystemService(android.content.ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Osservio", event.message));
        }); return screen;
    }
}
