package com.sandrox.osservio.core;

import java.util.Objects;

/** Receipt for bounded source material. Matching material does not prove a unique real-world failure. */
public final class DiagnosticRecoveryReceipt {
    public final SystemDiagnostic.Source source;
    public final long observedAtMillis;
    public final int savedCount, alreadyProcessedCount, ignoredExitCount, outsideWindowCount, capacityExcludedCount;
    public final int sourceRejectedCount, sourceExcludedCount;
    public final boolean sourceLimitReached;

    public DiagnosticRecoveryReceipt(SystemDiagnosticBatch batch, int savedCount, int alreadyProcessedCount,
                                     int ignoredExitCount, int outsideWindowCount, int capacityExcludedCount) {
        Objects.requireNonNull(batch, "batch");
        int total = 0;
        for (int count : new int[] {savedCount, alreadyProcessedCount, ignoredExitCount, outsideWindowCount, capacityExcludedCount}) {
            if (count < 0 || count > 32) throw new IllegalArgumentException("Invalid recovery accounting");
            total += count;
        }
        if (total != batch.diagnostics.size()) throw new IllegalArgumentException("Incomplete recovery accounting");
        source = batch.source; observedAtMillis = batch.observedAtMillis;
        this.savedCount = savedCount; this.alreadyProcessedCount = alreadyProcessedCount;
        this.ignoredExitCount = ignoredExitCount; this.outsideWindowCount = outsideWindowCount; this.capacityExcludedCount = capacityExcludedCount;
        sourceRejectedCount = batch.rejectedCount; sourceExcludedCount = batch.excludedCount; sourceLimitReached = batch.sourceLimitReached;
    }
}
