package com.sandrox.osservio.ui;

import android.content.Context;
import com.sandrox.osservio.core.IncidentPayload;

/** Saved inclusion states describe the frozen copy independently of the transport observation. */
final class IncidentPayloadPresentation {
    private IncidentPayloadPresentation() { }
    static String content(Context context, IncidentPayload payload) {
        if (payload == null) return context.getString(R.string.osservio_incident_payload_missing);
        switch (payload.state) {
            case AVAILABLE: return payload.text.isEmpty() ? context.getString(R.string.osservio_http_body_empty) : payload.text;
            case NOT_SELECTED: return context.getString(R.string.osservio_incident_payload_not_selected);
            case UNAVAILABLE: return context.getString(R.string.osservio_incident_payload_unavailable);
            case BUSY: return context.getString(R.string.osservio_incident_payload_busy);
            case BUDGET_EXCEEDED: return context.getString(R.string.osservio_incident_payload_budget);
            default: throw new IllegalArgumentException("Unknown saved content state");
        }
    }
}
