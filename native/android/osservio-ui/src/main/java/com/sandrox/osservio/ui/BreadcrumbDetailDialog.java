package com.sandrox.osservio.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import com.sandrox.osservio.core.BreadcrumbEvent;

/** One frozen event presentation for live history, retained runs and incident evidence. */
final class BreadcrumbDetailDialog {
    private BreadcrumbDetailDialog() { }
    static InspectorScreen create(Context context, BreadcrumbEvent event) {
        InspectorDetailPage page = new InspectorDetailPage(context);
        page.section(R.string.osservio_ui_overview);
        page.field(R.string.osservio_ui_event_type, () -> RuntimePresentation.kind(context, event.kind));
        page.field(R.string.osservio_http_time, () -> RuntimePresentation.date(context, event.timestampMillis));
        page.field(R.string.osservio_crumb_label, event.label);
        page.field(R.string.osservio_crumb_elapsed, () -> event.sessionElapsedMillis == null ? context.getString(R.string.osservio_runtime_unavailable) : event.sessionElapsedMillis + " ms");
        if (event.value != null) page.field(R.string.osservio_crumb_value, event.value);
        page.note(R.string.osservio_crumb_detail_hint);
        if (event.message != null) {
            page.section(R.string.osservio_ui_event_message);
            String preview = event.message.length() > 320 ? event.message.substring(0, 320) + "…" : event.message;
            page.disclosure(R.string.osservio_ui_open_reader, () -> preview, () -> page.read(R.string.osservio_ui_event_message,
                new InspectorReadContent(event.message, () -> notice(context, event), !event.isPrivate && !event.isTruncated)));
            page.note(() -> notice(context, event));
        }
        page.section(R.string.osservio_crumb_retention);
        page.field(R.string.osservio_crumb_retention, () -> context.getString(event.retention == BreadcrumbEvent.Retention.RESTART_RELEVANT
            ? R.string.osservio_crumb_restart_relevant : R.string.osservio_crumb_memory_only));
        page.expandable(R.string.osservio_ui_technical_details, R.string.osservio_ui_show_identifiers, R.string.osservio_ui_hide_identifiers, () -> {
            page.field("ID", event.id); page.field(R.string.osservio_crumb_session, event.sessionId); page.field(R.string.osservio_crumb_sequence, event.sequence);
        });
        InspectorScreen screen = InspectorDialogs.screen(context, RuntimePresentation.kind(context, event.kind), page);
        screen.title(() -> RuntimePresentation.kind(context, event.kind));
        screen.iconAction(R.drawable.osservio_copy, R.string.osservio_ui_copy_all, () -> {
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Osservio", RuntimePresentation.breadcrumb(context, event)));
        });
        return screen;
    }
    private static String notice(Context context, BreadcrumbEvent event) {
        String result = event.isPrivate ? context.getString(R.string.osservio_crumb_private) : "";
        if (event.isTruncated) result += (result.isEmpty() ? "" : " · ") + context.getString(R.string.osservio_truncated);
        return result;
    }
}
