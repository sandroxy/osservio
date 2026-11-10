package com.sandrox.osservio.ui;

import android.content.Context;
import com.sandrox.osservio.core.IncidentPayload;

/** The sanitized source stays separate from UI explanations and completeness. */
final class InspectorReadContent {
    final String text;
    private final InspectorAppearance.Value<String> notice;
    final boolean allowsJson;
    InspectorReadContent(String text, String notice, boolean allowsJson) { this(text, () -> notice, allowsJson); }
    InspectorReadContent(String text, InspectorAppearance.Value<String> notice, boolean allowsJson) {
        this.text = text; this.notice = notice; this.allowsJson = allowsJson;
    }
    String notice() { return notice.get(); }
    InspectorReadContent withNoticePrefix(InspectorAppearance.Value<String> prefix) {
        return new InspectorReadContent(text, () -> {
            String heading = prefix.get(); return (heading.isEmpty() ? "" : heading + "\n") + notice();
        }, allowsJson);
    }
    static InspectorReadContent unavailable(String notice) { return new InspectorReadContent("", notice, false); }
    static InspectorReadContent unavailable(InspectorAppearance.Value<String> notice) { return new InspectorReadContent("", notice, false); }
    static InspectorReadContent saved(Context context, IncidentPayload payload, String notice, boolean complete) {
        return saved(context, payload, () -> notice, complete);
    }
    static InspectorReadContent saved(Context context, IncidentPayload payload, InspectorAppearance.Value<String> notice, boolean complete) {
        if (payload == null || payload.state != IncidentPayload.State.AVAILABLE) return unavailable(() -> notice.get() + "\n" + IncidentPayloadPresentation.content(context, payload));
        return new InspectorReadContent(payload.text, () -> notice.get() + (payload.text.isEmpty() ? "\n" + context.getString(R.string.osservio_http_body_empty) : ""), complete);
    }
}
