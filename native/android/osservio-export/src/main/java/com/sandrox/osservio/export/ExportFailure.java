package com.sandrox.osservio.export;

import android.system.ErrnoException;
import android.system.OsConstants;
import com.sandrox.osservio.core.IncidentExporter;
import java.io.IOException;

/** Public failure categories never include paths, payload text, or raw exception messages. */
final class ExportFailure extends IOException {
    private static final long serialVersionUID = 1L;
    final IncidentExporter.Error error;
    ExportFailure(IncidentExporter.Error error) { super(error.name()); this.error = error; }
    static IncidentExporter.Error category(Exception failure) {
        if (failure instanceof ExportFailure) return ((ExportFailure) failure).error;
        if (failure instanceof IncidentDocuments.LimitExceeded) return IncidentExporter.Error.TOO_LARGE;
        if (failure instanceof IllegalArgumentException) return IncidentExporter.Error.INVALID_RECORD;
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            if (cause instanceof ErrnoException) {
                int errno = ((ErrnoException) cause).errno;
                if (errno == OsConstants.ENOSPC || errno == OsConstants.EDQUOT) return IncidentExporter.Error.STORAGE_FULL;
            }
        }
        return IncidentExporter.Error.UNAVAILABLE;
    }
}
