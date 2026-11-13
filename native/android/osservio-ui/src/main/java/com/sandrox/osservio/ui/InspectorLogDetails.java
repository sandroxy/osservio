package com.sandrox.osservio.ui;

import android.content.Context;
import com.sandrox.osservio.core.IncidentPayload;
import com.sandrox.osservio.core.LogDetailsHistory;
import com.sandrox.osservio.core.LogDetailsReference;
import com.sandrox.osservio.core.LogEvent;
import java.util.Map;

final class InspectorLogDetails {
    private InspectorLogDetails() { }
    static int label(LogDetailsReference.State state) {
        switch (state) {
            case AVAILABLE: return R.string.osservio_ui_log_context_open;
            case OMITTED: return R.string.osservio_ui_log_context_omitted;
            case LIMITED: return R.string.osservio_ui_log_context_limited;
            case INVALID: return R.string.osservio_ui_log_context_invalid;
            case UNSUPPORTED: return R.string.osservio_ui_log_context_unsupported;
            default: throw new IllegalArgumentException("Unknown log details state");
        }
    }
    static InspectorReadContent content(Context context, LogEvent event, LogDetailsHistory history, Map<String, IncidentPayload> saved) {
        if (event.details == null || event.details.blobId == null) return InspectorReadContent.unavailable("");
        String id = event.details.blobId; InspectorReadContent result;
        if (saved != null) result = InspectorReadContent.saved(context, saved.get(id), "", true);
        else if (history != null) {
            LogDetailsHistory.Lookup lookup = history.details(id);
            result = lookup.content != null ? new InspectorReadContent(lookup.content.text, "", true)
                : InspectorReadContent.unavailable(() -> context.getString(lookup.state == LogDetailsHistory.Lookup.State.BUSY
                    ? R.string.osservio_incident_payload_busy : R.string.osservio_incident_payload_unavailable));
        } else result = InspectorReadContent.unavailable(() -> context.getString(R.string.osservio_incident_payload_unavailable));
        return result.withContext(() -> InspectorRecordPresentation.context(context, event));
    }
}
