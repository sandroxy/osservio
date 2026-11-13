package com.sandrox.osservio.ui;

import android.content.Context;

/** Search progress describes the frozen input window, independently of matching rows. */
final class InspectorSearchFeedback {
    private InspectorSearchFeedback() { }
    static String activity(Context context, InspectorRecordSearch.Result result, boolean running, boolean paused) {
        if (result != null && !result.complete && (running || paused)) {
            return context.getString(paused && !running ? R.string.osservio_ui_search_paused : R.string.osservio_ui_search_progress, result.scanned, result.total);
        }
        return running ? context.getString(R.string.osservio_ui_searching) : null;
    }
}
