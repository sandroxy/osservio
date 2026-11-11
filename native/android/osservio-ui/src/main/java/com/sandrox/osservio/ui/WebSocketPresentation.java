package com.sandrox.osservio.ui;

import android.content.Context;
import com.sandrox.osservio.core.WebSocketEvent;
import com.sandrox.osservio.core.WebSocketHistory;
import com.sandrox.osservio.core.HTTPHeader;
import com.sandrox.osservio.core.IncidentPayload;
import java.util.Map;

/** Metadata rows never load payloads. Content lookup is explicit in the detail view. */
final class WebSocketPresentation {
    private WebSocketPresentation() { }
    static boolean hasIssue(WebSocketEvent event) {
        return event.kind == WebSocketEvent.Kind.FAILED || event.kind == WebSocketEvent.Kind.CANCELLED
            || event.kind == WebSocketEvent.Kind.OBSERVATION_LIMITED || event.outcome == WebSocketEvent.Outcome.FAILED
            || event.outcome == WebSocketEvent.Outcome.REJECTED || event.outcome == WebSocketEvent.Outcome.UNOBSERVED
            || (event.closeCode != null && event.closeCode != 1000 && event.closeCode != 1001);
    }
    static boolean isMessage(WebSocketEvent event) {
        return event.kind == WebSocketEvent.Kind.MESSAGE || event.kind == WebSocketEvent.Kind.MESSAGE_RESULT;
    }
    static String kind(Context context, WebSocketEvent.Kind kind) {
        int key;
        switch (kind) {
            case CONNECTING: key = R.string.osservio_ws_kind_connecting; break;
            case ATTACHED: key = R.string.osservio_ws_kind_attached; break;
            case OPENED: key = R.string.osservio_ws_kind_opened; break;
            case MESSAGE: key = R.string.osservio_ws_kind_message; break;
            case MESSAGE_RESULT: key = R.string.osservio_ws_kind_message_result; break;
            case CLOSE_REQUESTED: key = R.string.osservio_ws_kind_close_requested; break;
            case CLOSING: key = R.string.osservio_ws_kind_closing; break;
            case CLOSED: key = R.string.osservio_ws_kind_closed; break;
            case FAILED: key = R.string.osservio_ws_kind_failed; break;
            case CANCELLED: key = R.string.osservio_ws_kind_cancelled; break;
            case CANCEL_REQUESTED: key = R.string.osservio_ws_kind_cancel_requested; break;
            case OBSERVATION_ENDED: key = R.string.osservio_ws_kind_observation_ended; break;
            case OBSERVATION_LIMITED: key = R.string.osservio_ws_kind_observation_limited; break;
            default: throw new IllegalArgumentException("Unknown WebSocket event kind");
        }
        return context.getString(key);
    }
    static String outcome(Context context, WebSocketEvent.Outcome outcome) {
        int key;
        switch (outcome) {
            case RECEIVED: key = R.string.osservio_ws_outcome_received; break;
            case SUBMITTED: key = R.string.osservio_ws_outcome_submitted; break;
            case QUEUED: key = R.string.osservio_ws_outcome_queued; break;
            case SENT: key = R.string.osservio_ws_outcome_sent; break;
            case REJECTED: key = R.string.osservio_ws_outcome_rejected; break;
            case FAILED: key = R.string.osservio_ws_outcome_failed; break;
            case UNOBSERVED: key = R.string.osservio_ws_outcome_unobserved; break;
            default: throw new IllegalArgumentException("Unknown WebSocket outcome");
        }
        return context.getString(key);
    }
    static String summary(Context context, WebSocketEvent event) {
        return kind(context, event.kind) + (event.direction == null ? "" : " · " + context.getString(
            event.direction == WebSocketEvent.Direction.INCOMING ? R.string.osservio_ws_incoming : R.string.osservio_ws_outgoing))
            + (event.outcome == null ? "" : " · " + outcome(context, event.outcome))
            + (event.closeCode == null ? "" : " · " + event.closeCode);
    }
    static String overview(Context context, WebSocketEvent event) {
        StringBuilder text = new StringBuilder(event.url).append("\n\n").append(summary(context, event));
        field(text, context, R.string.osservio_ws_time, InspectorValuePresentation.eventTime(event.timestampMillis, true));
        field(text, context, R.string.osservio_http_source, event.source == WebSocketEvent.Source.OKHTTP ? "OkHttp"
            : event.source == WebSocketEvent.Source.URL_SESSION ? "URLSession" : context.getString(R.string.osservio_ws_host));
        field(text, context, R.string.osservio_ws_connection, event.connectionId);
        field(text, context, R.string.osservio_ws_event, event.id);
        field(text, context, R.string.osservio_ws_related, event.relatedMessageId);
        field(text, context, R.string.osservio_http_session, event.sessionId + " · #" + event.sequence);
        field(text, context, R.string.osservio_http_status, event.statusCode);
        field(text, context, R.string.osservio_ws_close_code, event.closeCode);
        field(text, context, R.string.osservio_http_failure, event.failure == null ? null : failure(context, event.failure));
        text.append("\n\n").append(context.getString(R.string.osservio_ws_headers)).append('\n');
        if (event.headers.isEmpty()) text.append(context.getString(R.string.osservio_http_none));
        for (HTTPHeader header : event.headers) text.append(header.name).append(": ").append(header.value).append('\n');
        if (event.headersLimited) text.append("\n\n").append(context.getString(R.string.osservio_http_headers_limited));
        return text.append("\n\n").append(context.getString(R.string.osservio_ws_evidence_note)).toString();
    }
    static String failure(Context context, WebSocketEvent.Failure failure) {
        int key;
        switch (failure) {
            case TRANSPORT: key = R.string.osservio_http_transport; break;
            case TIMEOUT: key = R.string.osservio_http_timeout; break;
            case TLS: key = R.string.osservio_http_tls; break;
            case CONNECTION: key = R.string.osservio_http_connection; break;
            case UNKNOWN: key = R.string.osservio_http_unknown; break;
            default: throw new IllegalArgumentException("Unknown WebSocket failure");
        }
        return context.getString(key);
    }
    private static void field(StringBuilder text, Context context, int key, Object value) {
        text.append("\n\n").append(context.getString(key)).append(": ")
            .append(value == null ? context.getString(R.string.osservio_http_none) : value);
    }
    static String payloadDescription(Context context, WebSocketEvent.PayloadReference reference) {
        if (reference == null) return context.getString(R.string.osservio_ws_no_payload);
        int key;
        switch (reference.state) {
            case OMITTED: key = R.string.osservio_ws_payload_omitted; break;
            case BINARY: key = R.string.osservio_ws_payload_binary; break;
            case AVAILABLE: key = R.string.osservio_ws_payload_available; break;
            case TRUNCATED: key = R.string.osservio_ws_payload_truncated; break;
            case INVALID_TEXT: key = R.string.osservio_ws_payload_invalid_text; break;
            default: throw new IllegalArgumentException("Unknown WebSocket payload state");
        }
        return (reference.isProcessed ? context.getString(R.string.osservio_ui_content_processed) + "\n" : "") + context.getString(key) + (reference.byteCount == null ? "" : " · " + InspectorValuePresentation.bytes(reference.byteCount));
    }
    static InspectorReadContent payload(Context context, WebSocketEvent.PayloadReference reference, Map<String, IncidentPayload> savedPayloads) {
        InspectorAppearance.Value<String> notice = () -> payloadDescription(context, reference);
        if (reference == null || reference.blobId == null) return InspectorReadContent.unavailable(notice);
        return InspectorReadContent.saved(context, savedPayloads.get(reference.blobId), notice, reference.state == WebSocketEvent.PayloadState.AVAILABLE);
    }
    static InspectorReadContent payload(Context context, WebSocketEvent.PayloadReference reference, WebSocketHistory history) {
        InspectorAppearance.Value<String> notice = () -> payloadDescription(context, reference);
        if (reference == null || reference.blobId == null) return InspectorReadContent.unavailable(notice);
        WebSocketHistory.PayloadLookup lookup = history.payload(reference.blobId);
        switch (lookup.state) {
            case AVAILABLE: return new InspectorReadContent(lookup.payload.text, () -> notice.get() + (lookup.payload.text.isEmpty() ? "\n" + context.getString(R.string.osservio_http_body_empty) : ""), reference.state == WebSocketEvent.PayloadState.AVAILABLE);
            case BUSY: return InspectorReadContent.unavailable(() -> context.getString(R.string.osservio_http_body_busy));
            case UNAVAILABLE: return InspectorReadContent.unavailable(() -> context.getString(R.string.osservio_http_body_unavailable));
            default: throw new IllegalArgumentException("Unknown WebSocket payload lookup state");
        }
    }
}
