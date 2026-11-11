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
        int inset = InspectorTheme.dp(context, 16); tabs.setMargins(inset, InspectorTheme.dp(context, 8), inset, 0);
        if (event.payload != null) content.addView(InspectorWidgets.segments(context, new int[] {R.string.osservio_ui_overview, R.string.osservio_ws_payload}, 0, index -> { section = index; render(); }), tabs);
        body = new FrameLayout(context); content.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        dialog = InspectorDialogs.screen(context, "WebSocket", content);
        dialog.title(event.kind == WebSocketEvent.Kind.MESSAGE || event.kind == WebSocketEvent.Kind.MESSAGE_RESULT ? R.string.osservio_ui_message_details : R.string.osservio_ui_connection_event);
        dialog.iconAction(R.drawable.osservio_copy, R.string.osservio_ui_copy_metadata, () -> {
            android.content.ClipboardManager clipboard = context.getSystemService(android.content.ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Osservio", WebSocketPresentation.overview(context, event)));
        }); render();
    }
    private void render() {
        body.removeAllViews(); InspectorDetailPage page = new InspectorDetailPage(context);
        if (section == 0) {
            if (event.kind == WebSocketEvent.Kind.MESSAGE) payloadSection(page);
            page.section(R.string.osservio_ui_overview);
            page.value(() -> WebSocketPresentation.summary(context, event));
            page.disclosure(R.string.osservio_ui_request_url, () -> InspectorRecordPresentation.address(context, event.url), () -> page.read(R.string.osservio_ui_request_url,
                new InspectorReadContent(event.url, "", false).withContext(() -> InspectorRecordPresentation.context(context, event))));
            page.field(R.string.osservio_ws_time, () -> InspectorValuePresentation.eventTime(event.timestampMillis, true));
            if (connection != null) page.disclosure(R.string.osservio_ui_connection_context, () -> event.connectionId, connection);
            else page.field(R.string.osservio_ws_connection, event.connectionId);
            if (event.statusCode != null) page.field(R.string.osservio_http_status, event.statusCode);
            if (event.closeCode != null) page.field(R.string.osservio_ws_close_code, event.closeCode);
            if (event.failure != null) page.field(R.string.osservio_http_failure, () -> WebSocketPresentation.failure(context, event.failure));
            if (event.relatedMessageId != null) page.field(R.string.osservio_ws_related, event.relatedMessageId);
            page.note(() -> savedPayloads == null ? "" : context.getString(R.string.osservio_incident_frozen_detail));
            page.expandable(R.string.osservio_ui_technical_details, R.string.osservio_ui_show_details, R.string.osservio_ui_hide_details, () -> {
                page.field(R.string.osservio_http_source, () -> InspectorFilterOptions.source(context, event.source.name()));
                page.field(R.string.osservio_ws_event, event.id); page.field(R.string.osservio_http_session, event.sessionId + " · #" + event.sequence);
            });
            page.note(R.string.osservio_ws_evidence_note);
            if (!event.headers.isEmpty() || event.headersLimited) {
                page.section(R.string.osservio_ws_headers);
                for (com.sandrox.osservio.core.HTTPHeader header : event.headers) page.header(header);
                if (event.headersLimited) page.note(R.string.osservio_http_headers_limited);
            }
        } else payloadSection(page);
        body.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }
    private InspectorReadContent payloadContent() {
        InspectorReadContent text = savedPayloads != null ? WebSocketPresentation.payload(context, event.payload, savedPayloads) : WebSocketPresentation.payload(context, event.payload, history);
        return text.withNoticePrefix(() -> savedPayloads == null ? "" : context.getString(R.string.osservio_incident_frozen_detail))
            .withContext(() -> InspectorRecordPresentation.context(context, event));
    }
    private void payloadSection(InspectorDetailPage page) {
        InspectorReadContent content = payloadContent();
        page.section(R.string.osservio_ws_payload);
        page.value(() -> content.available ? content.text.isEmpty() ? context.getString(R.string.osservio_ui_content_empty) : InspectorRecordPresentation.compact(content.text, 600) : content.notice(),
            content.available ? () -> page.read(R.string.osservio_ws_payload, payloadContent()) : null, content.available ? R.string.osservio_ui_open_reader : 0);
        if (content.available) page.note(content::notice);
    }

}
