package com.sandrox.osservio.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** One bounded inspection of OS-owned history. Empty success does not prove that no failure occurred. */
public final class SystemDiagnosticBatch {
    public final SystemDiagnostic.Source source;
    public final long observedAtMillis;
    public final List<SystemDiagnostic> diagnostics;
    public final int rejectedCount, excludedCount;
    /** A source or traversal budget was reached; the number of unavailable older records is unknown. */
    public final boolean sourceLimitReached;
    public SystemDiagnosticBatch(SystemDiagnostic.Source source, long observedAtMillis, List<SystemDiagnostic> diagnostics,
                                 int rejectedCount, int excludedCount, boolean sourceLimitReached) {
        this.source = Objects.requireNonNull(source, "source"); Objects.requireNonNull(diagnostics, "diagnostics");
        if (diagnostics.size() > 32 || rejectedCount < 0 || rejectedCount > 32 || excludedCount < 0 || excludedCount > 32 ||
            diagnostics.size() + rejectedCount + excludedCount > 32) throw new IllegalArgumentException("Invalid diagnostic batch bounds");
        List<SystemDiagnostic> copy = new ArrayList<>(diagnostics.size());
        for (SystemDiagnostic diagnostic : diagnostics) {
            if (Objects.requireNonNull(diagnostic, "diagnostic").source != source) throw new IllegalArgumentException("Mixed diagnostic sources");
            copy.add(diagnostic);
        }
        this.observedAtMillis = observedAtMillis; this.diagnostics = Collections.unmodifiableList(copy);
        this.rejectedCount = rejectedCount; this.excludedCount = excludedCount; this.sourceLimitReached = sourceLimitReached;
    }
}
