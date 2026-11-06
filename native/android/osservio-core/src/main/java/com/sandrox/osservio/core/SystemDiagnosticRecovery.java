package com.sandrox.osservio.core;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/** Capture-gated processing of one source read or delivered batch. Installs no source or timer. */
public final class SystemDiagnosticRecovery {
    public static final class Error {
        public enum Kind { INACTIVE, CAPTURE_CHANGED, BUSY, CLOSED, SOURCE, PERSISTENCE }
        public final Kind kind;
        public final SystemDiagnosticProvider.Error sourceError;
        public final PersistenceStatus.Error persistenceError;
        private Error(Kind kind, SystemDiagnosticProvider.Error sourceError, PersistenceStatus.Error persistenceError) {
            this.kind = kind; this.sourceError = sourceError; this.persistenceError = persistenceError;
        }
        private static Error state(Kind kind) { return new Error(kind, null, null); }
        private static Error source(SystemDiagnosticProvider.Error error) { return new Error(Kind.SOURCE, error, null); }
        private static Error persistence(PersistenceStatus.Error error) { return new Error(Kind.PERSISTENCE, null, error); }
    }
    public interface Completion { void complete(DiagnosticRecoveryReceipt receipt, Error error); }
    private enum Phase { READING, SAVING }
    private static final class Job {
        final String sessionId; final Completion completion;
        Phase phase = Phase.READING;
        Job(String sessionId, Completion completion) { this.sessionId = sessionId; this.completion = completion; }
    }
    private final CaptureController capture;
    private final DiagnosticRecoveryArchive archive;
    private final ReentrantLock lock = new ReentrantLock();
    private Job pending;
    private boolean closed;
    public SystemDiagnosticRecovery(CaptureController captureController, DiagnosticRecoveryArchive archive) {
        capture = Objects.requireNonNull(captureController, "captureController"); this.archive = Objects.requireNonNull(archive, "archive");
    }
    /** One history read at a time; a capture-session change before persistence rejects the old result. */
    public void recover(SystemDiagnosticProvider provider, Completion completion) {
        Objects.requireNonNull(provider, "provider"); Objects.requireNonNull(completion, "completion");
        Start start = begin(completion);
        if (start.error != null) { completion.complete(null, start.error); return; }
        provider.read((batch, error) -> {
            if (error != null) finish(start.job, Phase.READING, null, Error.source(error));
            else if (batch == null) finish(start.job, Phase.READING, null, Error.source(SystemDiagnosticProvider.Error.UNAVAILABLE));
            else persist(batch, start.job);
        });
    }
    /** Admits a sanitized source delivery only while baseline or detailed capture is active. */
    public void recover(SystemDiagnosticBatch batch, Completion completion) {
        Objects.requireNonNull(batch, "batch"); Objects.requireNonNull(completion, "completion");
        Start start = begin(completion);
        if (start.error != null) completion.complete(null, start.error);
        else persist(batch, start.job);
    }
    /** Invalidates a pending read. Admitted persistence finishes; the caller still owns source and archive. */
    public void close() {
        Job cancelled;
        lock.lock();
        try {
            closed = true; cancelled = pending != null && pending.phase == Phase.READING ? pending : null;
            if (cancelled != null) pending = null;
        } finally { lock.unlock(); }
        if (cancelled != null) cancelled.completion.complete(null, Error.state(Error.Kind.CLOSED));
    }
    private static final class Start {
        final Job job; final Error error;
        Start(Job job, Error error) { this.job = job; this.error = error; }
        static Start rejected(Error.Kind kind) { return new Start(null, Error.state(kind)); }
    }
    private Start begin(Completion completion) {
        if (!lock.tryLock()) return Start.rejected(Error.Kind.BUSY);
        try {
            if (closed) return Start.rejected(Error.Kind.CLOSED);
            if (pending != null) return Start.rejected(Error.Kind.BUSY);
            CaptureController.Status status = capture.status();
            if (status == null) return Start.rejected(Error.Kind.BUSY);
            if (status.sessionId == null) return Start.rejected(Error.Kind.INACTIVE);
            pending = new Job(status.sessionId, completion);
            return new Start(pending, null);
        } finally { lock.unlock(); }
    }
    private void persist(SystemDiagnosticBatch batch, Job job) {
        Error error;
        lock.lock();
        try {
            if (pending != job || job.phase != Phase.READING) return;
            CaptureController.Status status = capture.status();
            if (status == null) error = Error.state(Error.Kind.BUSY);
            else if (status.sessionId == null) error = Error.state(Error.Kind.INACTIVE);
            else error = status.sessionId.equals(job.sessionId) ? null : Error.state(Error.Kind.CAPTURE_CHANGED);
            if (error != null) pending = null;
            else job.phase = Phase.SAVING;
        } finally { lock.unlock(); }
        if (error != null) { job.completion.complete(null, error); return; }
        // Admission is linearized at the capture check. An accepted batch may finish after stop.
        archive.recoverDiagnostics(batch, (receipt, failure) -> {
            if (failure != null) finish(job, Phase.SAVING, null, Error.persistence(failure));
            else if (receipt == null) finish(job, Phase.SAVING, null, Error.persistence(PersistenceStatus.Error.INVALID_RECORD));
            else finish(job, Phase.SAVING, receipt, null);
        });
    }
    private void finish(Job job, Phase phase, DiagnosticRecoveryReceipt receipt, Error error) {
        lock.lock();
        try {
            if (pending != job || job.phase != phase) return;
            pending = null;
        } finally { lock.unlock(); }
        job.completion.complete(receipt, error);
    }
}
