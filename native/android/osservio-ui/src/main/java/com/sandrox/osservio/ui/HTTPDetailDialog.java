package com.sandrox.osservio.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import com.sandrox.osservio.core.HTTPEvent;
import com.sandrox.osservio.core.HTTPHeader;
import com.sandrox.osservio.core.HTTPHistory;
import com.sandrox.osservio.core.IncidentPayload;
import java.util.List;
import java.util.Map;

/** Entry-time metadata snapshot; body access is explicit and never refreshes metadata. */
final class HTTPDetailDialog {
    private final Context context;
    private final HTTPHistory history;
    private final Map<String, IncidentPayload> savedPayloads;
    private final HTTPEvent event;
    private int section;
    private final FrameLayout body;
    final InspectorScreen dialog;

    HTTPDetailDialog(Context context, HTTPEvent event, HTTPHistory history) {
        this(context, event, history, null);
    }
    HTTPDetailDialog(Context context, HTTPEvent event, Map<String, IncidentPayload> savedPayloads) {
        this(context, event, null, savedPayloads);
    }
    private HTTPDetailDialog(Context context, HTTPEvent event, HTTPHistory history, Map<String, IncidentPayload> savedPayloads) {
        this.context = context; this.event = event; this.history = history; this.savedPayloads = savedPayloads;
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tabs = new LinearLayout.LayoutParams(-1, -2);
        int inset = InspectorTheme.dp(context, 16); tabs.setMargins(inset, InspectorTheme.dp(context, 8), inset, 0);
        content.addView(InspectorWidgets.segments(context, new int[] {R.string.osservio_ui_overview, R.string.osservio_ui_request, R.string.osservio_ui_response}, 0,
            index -> { section = index; render(); }), tabs);
        body = new FrameLayout(context); content.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        dialog = InspectorDialogs.screen(context, R.string.osservio_ui_request_details, content);
        dialog.iconAction(R.drawable.osservio_copy, R.string.osservio_ui_copy_metadata, () -> {
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Osservio", HTTPPresentation.overview(context, event)));
        }); render();
    }
    private void render() {
        body.removeAllViews();
        InspectorDetailPage page = new InspectorDetailPage(context);
        if (section == 0) {
            page.section(R.string.osservio_ui_overview);
            page.disclosure(R.string.osservio_ui_request_url, () -> InspectorRecordPresentation.address(context, event.url), () -> page.read(R.string.osservio_ui_request_url,
                new InspectorReadContent(event.url, "", false).withContext(() -> InspectorRecordPresentation.context(context, event))));
            page.field(R.string.osservio_ui_http_method, event.method);
            page.field(R.string.osservio_ui_state, () -> HTTPPresentation.state(context, event.state));
            page.field(R.string.osservio_http_time, () -> InspectorValuePresentation.eventTime(event.timestampMillis, true));
            if (event.statusCode != null) page.field(R.string.osservio_http_status, event.statusCode);
            if (event.durationMillis != null) page.field(R.string.osservio_http_duration, event.durationMillis + " ms");
            if (event.failure != null) page.field(R.string.osservio_http_failure, () -> HTTPPresentation.failure(context, event.failure));
            if (event.responseURL != null && !event.responseURL.equals(event.url)) page.disclosure(R.string.osservio_http_final_url,
                () -> InspectorRecordPresentation.address(context, event.responseURL), () -> page.read(R.string.osservio_http_final_url,
                    new InspectorReadContent(event.responseURL, "", false).withContext(() -> InspectorRecordPresentation.context(context, event))));
            page.note(() -> savedPayloads == null ? "" : context.getString(R.string.osservio_incident_frozen_detail));
            page.expandable(R.string.osservio_ui_technical_details, R.string.osservio_ui_show_details, R.string.osservio_ui_hide_details, () -> {
                page.field(R.string.osservio_http_source, () -> InspectorFilterOptions.source(context, event.source.name()));
                page.field(R.string.osservio_http_id, event.id); page.field(R.string.osservio_http_session, event.sessionId + " · #" + event.sequence);
            });
            page.note(R.string.osservio_http_result_note);
        } else {
            InspectorReadContent content = bodyContent();
            page.section(R.string.osservio_ui_body);
            page.value(() -> content.available ? content.text.isEmpty() ? context.getString(R.string.osservio_ui_content_empty) : InspectorRecordPresentation.compact(content.text, 600) : content.notice(),
                content.available ? () -> page.read(section == 1 ? R.string.osservio_http_request_body : R.string.osservio_http_response_body, bodyContent()) : null, content.available ? R.string.osservio_ui_open_reader : 0);
            if (content.available) page.note(content::notice);
            page.section(section == 1 ? R.string.osservio_http_request_headers : R.string.osservio_http_response_headers);
            List<HTTPHeader> headers = section == 1 ? event.requestHeaders : event.responseHeaders;
            if (headers.isEmpty()) page.value(() -> context.getString(R.string.osservio_ui_headers_empty));
            for (HTTPHeader header : headers) page.field(header.name, header.value);
            if (event.headersLimited) page.note(R.string.osservio_http_headers_limited);
        }
        body.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }
    private InspectorReadContent bodyContent() {
        HTTPEvent.BodyReference reference = section == 1 ? event.requestBody : event.responseBody;
        InspectorReadContent content = savedPayloads != null ? HTTPPresentation.body(context, reference, savedPayloads) : HTTPPresentation.body(context, reference, history);
        return content.withNoticePrefix(() -> savedPayloads == null ? "" : context.getString(R.string.osservio_incident_frozen_detail))
            .withContext(() -> InspectorRecordPresentation.context(context, event));
    }

}
