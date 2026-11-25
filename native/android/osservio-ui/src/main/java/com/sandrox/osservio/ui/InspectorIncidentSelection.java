package com.sandrox.osservio.ui;

import com.sandrox.osservio.core.*;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A single explicit observation, not a query for the latest version or nearby activity. */
final class InspectorIncidentSelection {
    private InspectorIncidentSelection() { }
    static final class TooLarge extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
    }
    private static Map<String, IncidentPayload> payloads(String... ids) {
        Map<String, IncidentPayload> result = new LinkedHashMap<>();
        for (String id : ids) if (id != null) result.put(id, IncidentPayload.restore(id, IncidentPayload.State.NOT_SELECTED, null));
        return result;
    }
    static IncidentSnapshot mark(LogEvent event, BreadcrumbSnapshot breadcrumbs) {
        checkSize(event.textByteCount);
        IncidentLogs logs = IncidentLogs.restore(Collections.singletonList(event), System.currentTimeMillis(), 1, 0,
            payloads(event.details == null ? null : event.details.blobId), 0);
        return capture(breadcrumbs, logs, null, null, IncidentFocus.log(event));
    }
    static IncidentSnapshot mark(HTTPEvent event, BreadcrumbSnapshot breadcrumbs) {
        checkSize(event.textByteCount);
        IncidentHTTP http = IncidentHTTP.restore(Collections.singletonList(event), payloads(event.requestBody.blobId, event.responseBody.blobId),
            System.currentTimeMillis(), 1, 0, 0);
        return capture(breadcrumbs, null, http, null, IncidentFocus.http(event));
    }
    static IncidentSnapshot mark(WebSocketEvent event, BreadcrumbSnapshot breadcrumbs) {
        checkSize(event.textByteCount);
        IncidentWebSockets sockets = IncidentWebSockets.restore(Collections.singletonList(event), payloads(event.payload == null ? null : event.payload.blobId),
            System.currentTimeMillis(), 1, 0, 0);
        return capture(breadcrumbs, null, null, sockets, IncidentFocus.webSocket(event));
    }
    private static void checkSize(int bytes) {
        // Leaves room for optional context, note and a single bounded payload copy.
        if (bytes > 65536) throw new TooLarge();
    }
    private static IncidentSnapshot capture(BreadcrumbSnapshot breadcrumbs, IncidentLogs logs, IncidentHTTP http, IncidentWebSockets sockets, IncidentFocus focus) {
        IncidentSnapshot mark = IncidentSnapshot.capture(null, null, breadcrumbs, 200, 32768, LogClock.SYSTEM);
        return IncidentSnapshot.restore(mark.id, mark.timestampMillis, null, null, mark.breadcrumbs, logs, http, sockets).withFocus(focus);
    }
}
