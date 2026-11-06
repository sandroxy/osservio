package com.sandrox.osservio.core;

/** Optional persistence of sanitized system history. Installs no collector or automatic trigger. */
public interface DiagnosticRecoveryArchive {
    interface RecoveryCompletion { void complete(DiagnosticRecoveryReceipt receipt, PersistenceStatus.Error error); }
    /** Selected records and bounded acknowledgements commit together; failure commits neither.
     * Completion may run on any thread, including immediate admission rejection. */
    void recoverDiagnostics(SystemDiagnosticBatch batch, RecoveryCompletion completion);
}
