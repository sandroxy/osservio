package com.sandrox.osservio.ui;

import android.content.Context;
import com.sandrox.osservio.core.LogEvent;

/** An immutable log reader can display live or saved evidence without a history dependency. */
final class LogDetailDialog {
    private LogDetailDialog() { }
    static InspectorScreen create(Context context, LogEvent event) {
        InspectorDetailPage page = new InspectorDetailPage(context);
        page.section(R.string.osservio_ui_overview);
        page.field(R.string.osservio_ui_level, event.level.name()); page.field(R.string.osservio_ui_category, event.category);
        page.field(R.string.osservio_http_time, () -> RuntimePresentation.date(context, event.timestampMillis));
        page.field(R.string.osservio_http_session, event.sessionId + " · #" + event.sequence);
        if (event.isPrivate) page.note(R.string.osservio_crumb_private);
        if (event.isTruncated) page.note(R.string.osservio_truncated);
        page.section(R.string.osservio_logs);
        page.field(R.string.osservio_ui_read_content, event.message);
        page.action(R.string.osservio_ui_open_reader, () -> page.read(event.category, new InspectorReadContent(event.message, () -> event.isTruncated ? context.getString(R.string.osservio_truncated) : "", !event.isTruncated && !event.isPrivate)));
        return InspectorDialogs.screen(context, event.category, page);
    }
}
