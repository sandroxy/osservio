package com.sandrox.osservio.core;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Shared admission and lifecycle for native recorders. Installs no collectors. */
public final class CaptureController {
    public enum Mode { OFF, BASELINE, DETAILED }

    public static final class Status {
        public final Mode mode;
        public final String sessionId;
        public final long remainingMillis;
        private Status(Mode mode, String sessionId, long remainingMillis) {
            this.mode = mode;
            this.sessionId = sessionId;
            this.remainingMillis = remainingMillis;
        }
    }

    static final class Context {
        final String sessionId;
        final long sequence;
        final long monotonicMillis;
        final long sessionStartMillis;
        final Mode mode;
        Context(String sessionId, long sequence, long now, long sessionStartMillis, Mode mode) {
            this.sessionId = sessionId;
            this.sequence = sequence;
            monotonicMillis = now;
            this.sessionStartMillis = sessionStartMillis;
            this.mode = mode;
        }
    }

    enum Admission { VALUE, INACTIVE, BUSY, SEQUENCE_EXHAUSTED }
    interface Operation<Input, Output> { Output apply(Input input); }
    static final class Access<T> {
        final Admission admission;
        final T value;
        Access(Admission admission, T value) { this.admission = admission; this.value = value; }
    }

    private final ReentrantLock lock = new ReentrantLock();
    final LogClock clock;
    private boolean baselineEnabled;
    private boolean detailed;
    private long deadline;
    private String sessionId = UUID.randomUUID().toString();
    private long sequence;
    private long sessionStartMillis;

    public CaptureController() { this(false, LogClock.SYSTEM); }
    public CaptureController(boolean baselineEnabled, LogClock clock) {
        this.baselineEnabled = baselineEnabled;
        this.clock = Objects.requireNonNull(clock, "clock");
        sessionStartMillis = clock.monotonicMillis();
    }

    public void startDetailed() { startDetailed(900_000L); }

    /** Expiry returns to the configured baseline or off state. */
    public void startDetailed(long durationMillis) {
        if (durationMillis < 1 || durationMillis > 86_400_000L) {
            throw new IllegalArgumentException("Invalid capture duration");
        }
        if (!enter()) throw new IllegalStateException("Capture is busy");
        try {
            long now = clock.monotonicMillis();
            if (now > Long.MAX_VALUE - durationMillis) throw new IllegalArgumentException("Invalid clock deadline");
            deadline = now + durationMillis;
            detailed = true;
            replaceSession(now);
        } finally { lock.unlock(); }
    }

    public boolean stopDetailed() {
        if (!enter()) return false;
        try {
            if (detailed) { detailed = false; replaceSession(clock.monotonicMillis()); }
            return true;
        } finally { lock.unlock(); }
    }

    /** Changes fallback capture without interrupting an active detailed session. */
    public boolean setBaselineEnabled(boolean enabled) {
        if (!enter()) return false;
        try {
            long now = clock.monotonicMillis();
            expire(now);
            if (baselineEnabled != enabled) {
                baselineEnabled = enabled;
                if (!detailed) replaceSession(now);
            }
            return true;
        } finally { lock.unlock(); }
    }

    /** Stops new observations without changing any retained history. */
    public boolean stopAll() {
        if (!enter()) return false;
        try {
            if (baselineEnabled || detailed) {
                baselineEnabled = false;
                detailed = false;
                replaceSession(clock.monotonicMillis());
            }
            return true;
        } finally { lock.unlock(); }
    }

    /** Null means contention, never a stopped session. */
    public Status status() { return inspect(value -> value); }

    <T> T inspect(Operation<Status, T> body) {
        if (!enter()) return null;
        try {
            long now = clock.monotonicMillis();
            expire(now);
            Mode mode = currentMode();
            return body.apply(new Status(mode, mode == Mode.OFF ? null : sessionId, detailed ? deadline - now : 0));
        } finally { lock.unlock(); }
    }

    // Bodies own recorder state under this lock. No waiting, I/O, or reentrant capture.
    <T> Access<T> record(boolean requiresDetailed, Operation<Context, T> body) {
        if (!enter()) return new Access<>(Admission.BUSY, null);
        try {
            long now = clock.monotonicMillis();
            expire(now);
            Mode mode = currentMode();
            if (mode == Mode.OFF || (requiresDetailed && mode != Mode.DETAILED)) return new Access<>(Admission.INACTIVE, null);
            if (sequence == Long.MAX_VALUE) return new Access<>(Admission.SEQUENCE_EXHAUSTED, null);
            sequence++;
            return new Access<>(Admission.VALUE, body.apply(new Context(sessionId, sequence, now, sessionStartMillis, mode)));
        } finally { lock.unlock(); }
    }

    private boolean enter() { return !lock.isHeldByCurrentThread() && lock.tryLock(); }
    private Mode currentMode() { return detailed ? Mode.DETAILED : baselineEnabled ? Mode.BASELINE : Mode.OFF; }
    private void expire(long now) {
        if (detailed && now >= deadline) { detailed = false; replaceSession(now); }
    }
    private void replaceSession(long now) {
        sessionId = UUID.randomUUID().toString();
        sequence = 0;
        sessionStartMillis = now;
    }
}
