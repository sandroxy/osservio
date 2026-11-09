package com.sandrox.osservio.ui;

import android.content.Context;
import com.sandrox.osservio.core.IncidentPayload;

/** The sanitized source stays separate from UI explanations and completeness. */
final class InspectorReadContent {
    final String text, notice;
    final boolean allowsJson;
    InspectorReadContent(String text, String notice, boolean allowsJson) { this.text = text; this.notice = notice; this.allowsJson = allowsJson; }
    static InspectorReadContent unavailable(String notice) { return new InspectorReadContent("", notice, false); }
    static InspectorReadContent saved(Context context, IncidentPayload payload, String notice, boolean complete) {
        if (payload == null || payload.state != IncidentPayload.State.AVAILABLE) return unavailable(notice + "\n" + IncidentPayloadPresentation.content(context, payload));
        return new InspectorReadContent(payload.text, notice + (payload.text.isEmpty() ? "\n" + context.getString(R.string.osservio_http_body_empty) : ""), complete);
    }
}
