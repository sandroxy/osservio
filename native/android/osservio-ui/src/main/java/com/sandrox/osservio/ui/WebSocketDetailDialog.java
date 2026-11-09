package com.sandrox.osservio.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import com.sandrox.osservio.core.WebSocketEvent;
import com.sandrox.osservio.core.WebSocketHistory;
import com.sandrox.osservio.core.WebSocketSnapshot;
import com.sandrox.osservio.core.IncidentPayload;
import java.util.Map;

/** Stable event evidence. Opening content never re-reads or replays a host socket. */
final class WebSocketDetailDialog {
    private final Context context;
    private final WebSocketHistory history;
    private final Map<String, IncidentPayload> savedPayloads;
    private WebSocketEvent event;
    private String notice = "";
    private int section;
    private final FrameLayout body;
    final AlertDialog dialog;
    WebSocketDetailDialog(Context context, WebSocketEvent event, WebSocketHistory history) { this(context, event, history, null); }
    WebSocketDetailDialog(Context context, WebSocketEvent event, Map<String, IncidentPayload> payloads) { this(context, event, null, payloads); }
    private WebSocketDetailDialog(Context context, WebSocketEvent event, WebSocketHistory history, Map<String, IncidentPayload> payloads) {
        this.context = context; this.event = event; this.history = history; this.savedPayloads = payloads;
        if (payloads != null) notice = context.getString(R.string.osservio_incident_frozen_detail);
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        content.addView(InspectorWidgets.segments(context, new String[] {context.getString(R.string.osservio_ui_overview),
            context.getString(R.string.osservio_ws_payload)}, 0, index -> { section = index; render(); }), new LinearLayout.LayoutParams(-1, -2));
        body = new FrameLayout(context); content.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        dialog = InspectorDialogs.screen(context, "WebSocket", content); render();
    }
    private void refresh() {
        if (history == null) return;
        WebSocketSnapshot snapshot = history.snapshot();
        notice = context.getString(snapshot == null ? R.string.osservio_http_stale : R.string.osservio_ws_missing);
        if (snapshot != null) for (WebSocketEvent current : snapshot.events) if (current.id.equals(event.id)) { event = current; notice = ""; break; }
        render();
    }
    private void render() {
        body.removeAllViews(); InspectorDetailPage page = new InspectorDetailPage(context); page.note(notice);
        if (section == 0) {
            page.section(R.string.osservio_ui_overview);
            page.field("URL", event.url); page.field(R.string.osservio_ui_kind, WebSocketPresentation.summary(context, event));
            page.field(R.string.osservio_ws_time, RuntimePresentation.date(context, event.timestampMillis));
            page.field(R.string.osservio_http_source, InspectorFilterOptions.source(context, event.source.name()));
            page.section(R.string.osservio_ui_metadata);
            page.field(R.string.osservio_ws_connection, event.connectionId); page.field(R.string.osservio_ws_event, event.id);
            page.field(R.string.osservio_ws_related, event.relatedMessageId); page.field(R.string.osservio_ws_close_code, event.closeCode);
            page.field(R.string.osservio_http_status, event.statusCode); page.field(R.string.osservio_http_session, event.sessionId + " · #" + event.sequence);
            if (event.failure != null) page.field(R.string.osservio_http_failure, WebSocketPresentation.failure(context, event.failure));
            page.section(R.string.osservio_ws_headers);
            for (com.sandrox.osservio.core.HTTPHeader header : event.headers) page.field(header.name, header.value);
            if (event.headers.isEmpty()) page.note(context.getString(R.string.osservio_http_none));
            if (event.headersLimited) page.note(context.getString(R.string.osservio_http_headers_limited));
            page.note(context.getString(R.string.osservio_ws_evidence_note));
            page.action(R.string.osservio_ui_read_content, () -> page.read(context.getString(R.string.osservio_ui_metadata), WebSocketPresentation.overview(context, event), false));
        } else {
            page.section(R.string.osservio_ws_payload); page.note(WebSocketPresentation.payloadDescription(context, event.payload));
            page.action(R.string.osservio_ui_open_reader, () -> {
                String text = savedPayloads != null ? WebSocketPresentation.payload(context, event.payload, savedPayloads)
                    : WebSocketPresentation.payload(context, event.payload, history);
                page.read(context.getString(R.string.osservio_ws_payload), text, true);
            });
        }
        if (history != null) page.action(R.string.osservio_http_refresh, this::refresh);
        body.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }
}
