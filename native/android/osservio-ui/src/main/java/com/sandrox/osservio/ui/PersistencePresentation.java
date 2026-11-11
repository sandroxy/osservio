package com.sandrox.osservio.ui;

import android.content.Context;
import com.sandrox.osservio.core.PersistenceStatus;
import com.sandrox.osservio.core.RetainedRun;

final class PersistencePresentation {
    private PersistencePresentation() { }
    static String title(Context context, RetainedRun run) {
        return RuntimePresentation.date(context, run.recordingStartedAtMillis) + " · " + context.getString(run.isCurrent ? R.string.osservio_archive_current : R.string.osservio_archive_previous_run);
    }
    static String counts(Context context, RetainedRun run) { return run.evictedEvents > 0 ? context.getString(R.string.osservio_archive_counts, run.eventCount, run.evictedEvents) : context.getString(R.string.osservio_ui_record_count, run.eventCount); }
    static String run(Context context, RetainedRun run) {
        return title(context, run) + "\n" + counts(context, run) + "\n" + context.getString(R.string.osservio_archive_run_info,
            run.platform.name(), Long.toString(run.processId), RuntimePresentation.date(context, run.lastRecordedAtMillis), run.id);
    }
    static String status(Context context, PersistenceStatus status) {
        return status(context, status, R.string.osservio_archive_status);
    }
    static String status(Context context, PersistenceStatus status, int format) {
        if (status == null) return context.getString(R.string.osservio_busy);
        int key;
        switch (status.state) {
            case OPENING: key = R.string.osservio_archive_state_opening; break;
            case READY: key = R.string.osservio_archive_state_ready; break;
            case FAILED: key = R.string.osservio_archive_state_failed; break;
            case CLOSING: key = R.string.osservio_archive_state_closing; break;
            case CLOSED: key = R.string.osservio_archive_state_closed; break;
            default: throw new IllegalStateException("Unknown persistence state");
        }
        return context.getString(format, context.getString(key), status.queuedEvents, status.committedEvents, status.discardedEvents)
            + (status.lastError == null ? "" : "\n" + error(context, status.lastError));
    }
    static String error(Context context, PersistenceStatus.Error error) {
        int key;
        switch (error) {
            case BUSY: key = R.string.osservio_archive_error_busy; break;
            case CLOSED: key = R.string.osservio_archive_error_closed; break;
            case UNAVAILABLE: key = R.string.osservio_archive_error_unavailable; break;
            case UNSUPPORTED_SCHEMA: key = R.string.osservio_archive_error_unsupported_schema; break;
            case CORRUPT: key = R.string.osservio_archive_error_corrupt; break;
            case STORAGE_FULL: key = R.string.osservio_archive_error_storage_full; break;
            case INVALID_RECORD: key = R.string.osservio_archive_error_invalid_record; break;
            default: throw new IllegalStateException("Unknown persistence error");
        }
        return context.getString(key);
    }
}
