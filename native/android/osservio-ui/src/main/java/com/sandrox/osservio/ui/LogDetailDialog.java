package com.sandrox.osservio.ui;

import android.content.Context;
import com.sandrox.osservio.core.LogEvent;

/** An immutable log reader can display live or saved evidence without a history dependency. */
final class LogDetailDialog {
    private LogDetailDialog() { }
    static InspectorScreen create(Context context, LogEvent event) {
        InspectorDetailPage page = new InspectorDetailPage(context);
        page.section(R.string.osservio_ui_read_content);
        InspectorWidgets.ValueRow reader = page.value(() -> RuntimePresentation.message(context, event.message.length() > 600 ? event.message.substring(0, 600) + "…" : event.message, event.isPrivate),
            event.isPrivate ? null : () -> page.read(R.string.osservio_ui_log_content, new InspectorReadContent(event.message, () -> event.isTruncated ? context.getString(R.string.osservio_truncated) : "", !event.isTruncated && !event.isPrivate).withContext(() -> InspectorRecordPresentation.context(context, event))));
        Runnable label = () -> reader.setContentDescription(context.getString(R.string.osservio_ui_open_reader));
        if (!event.isPrivate) { InspectorAppearance.content(page, label); label.run(); }
        if (event.isPrivate) page.note(R.string.osservio_crumb_private);
        if (event.isTruncated) page.note(R.string.osservio_truncated);
        page.section(R.string.osservio_ui_overview);
        page.field(R.string.osservio_ui_level, event.level == com.sandrox.osservio.core.LogLevel.WARNING ? "WARN" : event.level.name());
        page.field(R.string.osservio_ui_category, event.category);
        page.field(R.string.osservio_ui_event_recorded, () -> InspectorValuePresentation.eventTime(event.timestampMillis, true));
        page.expandable(R.string.osservio_ui_technical_details, R.string.osservio_ui_show_details, R.string.osservio_ui_hide_details,
            () -> page.field(R.string.osservio_http_session, event.sessionId + " · #" + event.sequence));
        InspectorScreen screen = InspectorDialogs.screen(context, R.string.osservio_ui_log_details, page);
        screen.iconAction(R.drawable.osservio_copy, R.string.osservio_copy, () -> {
            android.content.ClipboardManager clipboard = context.getSystemService(android.content.ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Osservio", event.message));
        }); return screen;
    }
}
