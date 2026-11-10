package com.sandrox.osservio.ui;

import android.content.Context;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import com.sandrox.osservio.core.WebSocketEvent;
import com.sandrox.osservio.core.WebSocketHistory;
import com.sandrox.osservio.core.IncidentPayload;
import java.util.Map;

/** Stable event evidence. Opening content never re-reads or replays a host socket. */
final class WebSocketDetailDialog {
    private final Context context;
    private final WebSocketHistory history;
    private final Map<String, IncidentPayload> savedPayloads;
    private final WebSocketEvent event;
    private Runnable connection;
    void showConnection(Runnable action) { connection = action; render(); }
    private int section;
    private final FrameLayout body;
    final InspectorScreen dialog;
    WebSocketDetailDialog(Context context, WebSocketEvent event, WebSocketHistory history) { this(context, event, history, null); }
    WebSocketDetailDialog(Context context, WebSocketEvent event, Map<String, IncidentPayload> payloads) { this(context, event, null, payloads); }
    private WebSocketDetailDialog(Context context, WebSocketEvent event, WebSocketHistory history, Map<String, IncidentPayload> payloads) {
        this.context = context; this.event = event; this.history = history; this.savedPayloads = payloads;
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tabs = new LinearLayout.LayoutParams(-1, -2);
        int inset = InspectorTheme.dp(context, 16); tabs.setMargins(inset, InspectorTheme.dp(context, 4), inset, 0);
        content.addView(InspectorWidgets.segments(context, new int[] {R.string.osservio_ui_overview, R.string.osservio_ws_payload}, 0, index -> { section = index; render(); }), tabs);
        body = new FrameLayout(context); content.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        dialog = InspectorDialogs.screen(context, "WebSocket", content);
        dialog.title(() -> WebSocketPresentation.kind(context, event.kind));
        dialog.iconAction(R.drawable.osservio_copy, R.string.osservio_ui_copy_metadata, () -> {
            android.content.ClipboardManager clipboard = context.getSystemService(android.content.ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Osservio", WebSocketPresentation.overview(context, event)));
        }); render();
    }
    private void render() {
        body.removeAllViews(); InspectorDetailPage page = new InspectorDetailPage(context); page.note(() -> savedPayloads == null ? "" : context.getString(R.string.osservio_incident_frozen_detail));
        if (section == 0) {
            page.section(R.string.osservio_ui_overview);
            page.value(() -> WebSocketPresentation.summary(context, event));
            page.disclosure(R.string.osservio_ui_request_url, () -> event.url, () -> page.read("WebSocket", event.url, true));
            page.field(R.string.osservio_ws_time, () -> RuntimePresentation.date(context, event.timestampMillis));
            page.field(R.string.osservio_http_status, event.statusCode); page.field(R.string.osservio_ws_close_code, event.closeCode);
            page.field(R.string.osservio_http_failure, () -> event.failure == null ? null : WebSocketPresentation.failure(context, event.failure));
            page.section(R.string.osservio_ui_metadata);
            page.field(R.string.osservio_http_source, () -> InspectorFilterOptions.source(context, event.source.name()));
            if (connection != null) page.disclosure(R.string.osservio_ui_connection_context, () -> event.connectionId, connection);
            else page.field(R.string.osservio_ws_connection, event.connectionId);
            page.field(R.string.osservio_ws_event, event.id); page.field(R.string.osservio_ws_related, event.relatedMessageId);
            page.field(R.string.osservio_http_session, event.sessionId + " · #" + event.sequence); page.note(R.string.osservio_ws_evidence_note);
            page.section(R.string.osservio_ws_headers);
            for (com.sandrox.osservio.core.HTTPHeader header : event.headers) page.field(header.name, header.value);
            if (event.headers.isEmpty()) page.value(() -> context.getString(R.string.osservio_http_none));
            if (event.headersLimited) page.note(R.string.osservio_http_headers_limited);
        } else {
            page.section(R.string.osservio_ws_payload); page.value(() -> WebSocketPresentation.payloadDescription(context, event.payload));
            page.action(R.string.osservio_ui_open_reader, () -> {
                InspectorReadContent text = savedPayloads != null ? WebSocketPresentation.payload(context, event.payload, savedPayloads)
                    : WebSocketPresentation.payload(context, event.payload, history);
                page.read(R.string.osservio_ws_payload, text.withNoticePrefix(() -> savedPayloads == null ? "" : context.getString(R.string.osservio_incident_frozen_detail)));
            });
        }
        body.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }
}
