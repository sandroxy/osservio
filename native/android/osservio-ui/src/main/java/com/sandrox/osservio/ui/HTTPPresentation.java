package com.sandrox.osservio.ui;

import android.content.Context;
import com.sandrox.osservio.core.HTTPEvent;
import com.sandrox.osservio.core.HTTPHeader;
import com.sandrox.osservio.core.HTTPHistory;
import com.sandrox.osservio.core.IncidentPayload;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Text is built only from admitted metadata or an explicit body lookup. */
final class HTTPPresentation {
    private HTTPPresentation() { }
    static boolean isActive(HTTPEvent event) {
        return event.state == HTTPEvent.State.PENDING || event.state == HTTPEvent.State.RECEIVING;
    }
    static boolean hasIssue(HTTPEvent event) {
        return (event.statusCode != null && event.statusCode >= 400)
            || (!isActive(event) && event.state != HTTPEvent.State.COMPLETED);
    }
    static String state(Context context, HTTPEvent.State state) {
        int key;
        switch (state) {
            case PENDING: key = R.string.osservio_http_pending; break;
            case RECEIVING: key = R.string.osservio_http_receiving; break;
            case COMPLETED: key = R.string.osservio_http_completed; break;
            case FAILED: key = R.string.osservio_http_failed; break;
            case CANCELLED: key = R.string.osservio_http_cancelled; break;
            case CLOSED_EARLY: key = R.string.osservio_http_closed_early; break;
            case CAPTURE_ENDED: key = R.string.osservio_http_capture_ended; break;
            default: throw new IllegalArgumentException("Unknown HTTP state");
        }
        return context.getString(key);
    }
    static String summary(Context context, HTTPEvent event) {
        return state(context, event.state) + (event.statusCode == null ? "" : " · " + event.statusCode)
            + (event.durationMillis == null ? "" : " · " + event.durationMillis + " ms");
    }
    static String overview(Context context, HTTPEvent event) {
        StringBuilder text = new StringBuilder(event.method).append(' ').append(event.url).append("\n\n")
            .append(state(context, event.state));
        field(text, context, R.string.osservio_http_final_url, event.responseURL);
        field(text, context, R.string.osservio_http_status, event.statusCode);
        field(text, context, R.string.osservio_http_duration, event.durationMillis == null ? null : event.durationMillis + " ms");
        field(text, context, R.string.osservio_http_time, DateFormat.getDateTimeInstance().format(new Date(event.timestampMillis)));
        String source;
        int note;
        switch (event.source) {
            case OKHTTP: source = "OkHttp"; note = R.string.osservio_http_okhttp_note; break;
            case URL_SESSION: source = "URLSession"; note = R.string.osservio_http_urlsession_note; break;
            case HOST: source = "Host"; note = R.string.osservio_http_host_note; break;
            default: throw new IllegalArgumentException("Unknown HTTP source");
        }
        field(text, context, R.string.osservio_http_source, source);
        field(text, context, R.string.osservio_http_failure, event.failure == null ? null : failure(context, event.failure));
        field(text, context, R.string.osservio_http_id, event.id);
        field(text, context, R.string.osservio_http_session, event.sessionId + " · #" + event.sequence);
        headers(text, context, R.string.osservio_http_request_headers, event.requestHeaders);
        headers(text, context, R.string.osservio_http_response_headers, event.responseHeaders);
        if (event.headersLimited) text.append("\n\n").append(context.getString(R.string.osservio_http_headers_limited));
        return text.append("\n\n").append(context.getString(note)).append("\n\n")
            .append(context.getString(R.string.osservio_http_result_note)).toString();
    }
    private static String failure(Context context, HTTPEvent.Failure failure) {
        int key;
        switch (failure) {
            case TRANSPORT: key = R.string.osservio_http_transport; break;
            case TIMEOUT: key = R.string.osservio_http_timeout; break;
            case TLS: key = R.string.osservio_http_tls; break;
            case CONNECTION: key = R.string.osservio_http_connection; break;
            case UNKNOWN: key = R.string.osservio_http_unknown; break;
            default: throw new IllegalArgumentException("Unknown HTTP failure");
        }
        return context.getString(key);
    }
    private static void field(StringBuilder text, Context context, int key, Object value) {
        text.append("\n\n").append(context.getString(key)).append(": ")
            .append(value == null ? context.getString(R.string.osservio_http_none) : value);
    }
    private static void headers(StringBuilder text, Context context, int key, List<HTTPHeader> headers) {
        text.append("\n\n").append(context.getString(key)).append('\n');
        if (headers.isEmpty()) text.append(context.getString(R.string.osservio_http_none));
        for (HTTPHeader header : headers) text.append(header.name).append(": ").append(header.value).append('\n');
    }
    private static String bodyDescription(Context context, HTTPEvent.BodyReference reference) {
        int key;
        switch (reference.state) {
            case PENDING: key = R.string.osservio_http_body_pending; break;
            case OMITTED: key = R.string.osservio_http_body_omitted; break;
            case UNSUPPORTED: key = R.string.osservio_http_body_unsupported; break;
            case INCOMPLETE: key = R.string.osservio_http_body_incomplete; break;
            case INVALID_TEXT: key = R.string.osservio_http_body_invalid_text; break;
            case AVAILABLE: key = R.string.osservio_http_body_available; break;
            case TRUNCATED: key = R.string.osservio_http_body_truncated; break;
            default: throw new IllegalArgumentException("Unknown body state");
        }
        return context.getString(key) + "\n" + context.getString(R.string.osservio_http_observed_bytes)
            + ": " + reference.observedBytes;
    }
    static String body(Context context, HTTPEvent.BodyReference reference, Map<String, IncidentPayload> savedPayloads) {
        return bodyDescription(context, reference) + (reference.blobId == null ? "" : "\n\n" + IncidentPayloadPresentation.content(context, savedPayloads.get(reference.blobId)));
    }
    static String body(Context context, HTTPEvent.BodyReference reference, HTTPHistory history) {
        String result = bodyDescription(context, reference);
        if (reference.blobId == null) return result;
        HTTPHistory.BodyLookup lookup = history.body(reference.blobId);
        switch (lookup.state) {
            case AVAILABLE:
                return result + "\n\n" + (lookup.body.text.isEmpty() ? context.getString(R.string.osservio_http_body_empty) : lookup.body.text);
            case BUSY: return result + "\n\n" + context.getString(R.string.osservio_http_body_busy);
            case UNAVAILABLE: return result + "\n\n" + context.getString(R.string.osservio_http_body_unavailable);
            default: throw new IllegalArgumentException("Unknown body lookup state");
        }
    }
}
