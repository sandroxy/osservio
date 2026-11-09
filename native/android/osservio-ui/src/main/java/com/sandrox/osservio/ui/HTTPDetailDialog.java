package com.sandrox.osservio.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import com.sandrox.osservio.core.HTTPEvent;
import com.sandrox.osservio.core.HTTPHeader;
import com.sandrox.osservio.core.HTTPHistory;
import com.sandrox.osservio.core.HTTPRecorder;
import com.sandrox.osservio.core.HTTPSnapshot;
import com.sandrox.osservio.core.IncidentPayload;
import java.util.List;
import java.util.Map;

/** Stable metadata snapshot; body access and live refresh are always explicit. */
final class HTTPDetailDialog {
    private final Context context;
    private final HTTPHistory history;
    private final HTTPRecorder recorder;
    private final Map<String, IncidentPayload> savedPayloads;
    private HTTPEvent event;
    private String notice = "";
    private int section;
    private final FrameLayout body;
    final AlertDialog dialog;

    HTTPDetailDialog(Context context, HTTPEvent event, HTTPHistory history, HTTPRecorder recorder) {
        this(context, event, history, recorder, null);
    }
    HTTPDetailDialog(Context context, HTTPEvent event, Map<String, IncidentPayload> savedPayloads) {
        this(context, event, null, null, savedPayloads);
    }
    private HTTPDetailDialog(Context context, HTTPEvent event, HTTPHistory history, HTTPRecorder recorder, Map<String, IncidentPayload> savedPayloads) {
        this.context = context; this.event = event; this.history = history; this.recorder = recorder; this.savedPayloads = savedPayloads;
        if (savedPayloads != null) notice = context.getString(R.string.osservio_incident_frozen_detail);
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        content.addView(InspectorWidgets.segments(context, new String[] {context.getString(R.string.osservio_ui_overview),
            context.getString(R.string.osservio_ui_request), context.getString(R.string.osservio_ui_response)}, 0,
            index -> { section = index; render(); }), new LinearLayout.LayoutParams(-1, -2));
        body = new FrameLayout(context); content.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        dialog = InspectorDialogs.screen(context, event.method + " · HTTP", content); render();
    }
    private void refresh() {
        if (history == null) return;
        recorder.maintenance(); HTTPSnapshot snapshot = history.snapshot();
        notice = context.getString(snapshot == null ? R.string.osservio_http_stale : R.string.osservio_http_missing);
        if (snapshot != null) for (HTTPEvent current : snapshot.events) if (current.id.equals(event.id)) { event = current; notice = ""; break; }
        render();
    }
    private void render() {
        body.removeAllViews();
        InspectorDetailPage page = new InspectorDetailPage(context); page.note(notice);
        if (section == 0) {
            page.section(R.string.osservio_ui_overview);
            page.field(R.string.osservio_ui_method, event.method);
            page.field("URL", event.url);
            if (event.responseURL != null) page.field(R.string.osservio_http_final_url, event.responseURL);
            page.field(R.string.osservio_ui_state, HTTPPresentation.state(context, event.state));
            page.field(R.string.osservio_http_status, event.statusCode);
            if (event.failure != null) page.field(R.string.osservio_http_failure, HTTPPresentation.failure(context, event.failure));
            page.field(R.string.osservio_http_duration, event.durationMillis == null ? null : event.durationMillis + " ms");
            page.section(R.string.osservio_ui_metadata);
            page.field(R.string.osservio_http_time, RuntimePresentation.date(context, event.timestampMillis));
            page.field(R.string.osservio_http_source, InspectorFilterOptions.source(context, event.source.name()));
            page.field(R.string.osservio_http_id, event.id); page.field(R.string.osservio_http_session, event.sessionId + " · #" + event.sequence);
            page.note(context.getString(event.source == HTTPEvent.Source.OKHTTP ? R.string.osservio_http_okhttp_note
                : event.source == HTTPEvent.Source.URL_SESSION ? R.string.osservio_http_urlsession_note : R.string.osservio_http_host_note));
            page.note(context.getString(R.string.osservio_http_result_note));
            page.action(R.string.osservio_ui_read_content, () -> page.read(context.getString(R.string.osservio_ui_metadata), HTTPPresentation.overview(context, event), false));
        } else {
            page.section(section == 1 ? R.string.osservio_http_request_headers : R.string.osservio_http_response_headers);
            List<HTTPHeader> headers = section == 1 ? event.requestHeaders : event.responseHeaders;
            if (headers.isEmpty()) page.note(context.getString(R.string.osservio_http_none));
            for (HTTPHeader header : headers) page.field(header.name, header.value);
            if (event.headersLimited) page.note(context.getString(R.string.osservio_http_headers_limited));
            HTTPEvent.BodyReference reference = section == 1 ? event.requestBody : event.responseBody;
            page.section(R.string.osservio_ui_body); page.note(HTTPPresentation.bodyDescription(context, reference));
            page.action(R.string.osservio_ui_open_reader, () -> {
                String text = savedPayloads != null ? HTTPPresentation.body(context, reference, savedPayloads) : HTTPPresentation.body(context, reference, history);
                page.read(context.getString(section == 1 ? R.string.osservio_http_request_body : R.string.osservio_http_response_body), text, true);
            });
        }
        if (history != null) page.action(R.string.osservio_http_refresh, this::refresh);
        page.action(R.string.osservio_ui_copy_metadata, () -> {
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Osservio", HTTPPresentation.overview(context, event)));
        });
        body.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }
}
