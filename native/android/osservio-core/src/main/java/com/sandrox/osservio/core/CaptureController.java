package com.sandrox.osservio.core;

import java.util.Objects;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Shared admission and lifecycle for native recorders. Installs no collectors. */
public final class CaptureController {
    public enum Mode { OFF, BASELINE, DETAILED }

    public static final class Status {
        public final Mode mode;
        public final boolean baselineEnabled;
        public final String sessionId;
        public final long remainingMillis;
        public final boolean debugEnabled;
        public final boolean debugHasTimeLimit;
        public final boolean debugExpired;
        public final boolean isClosed;
        private Status(Mode mode, boolean baselineEnabled, String sessionId, long remainingMillis,
                       boolean debugHasTimeLimit, boolean debugExpired, boolean isClosed) {
            this.mode = mode;
            this.baselineEnabled = baselineEnabled;
            this.sessionId = sessionId;
            this.remainingMillis = remainingMillis;
            this.debugEnabled = mode == Mode.DETAILED;
            this.debugHasTimeLimit = debugHasTimeLimit;
            this.debugExpired = debugExpired;
            this.isClosed = isClosed;
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
    interface SessionObserver { void captureSessionDidEnd(); }
    static final class Access<T> {
        final Admission admission;
        final T value;
        Access(Admission admission, T value) { this.admission = admission; this.value = value; }
    }

    private final ReentrantLock lock = new ReentrantLock();
    final LogClock clock;
    private boolean closed;
    private boolean baselineEnabled;
    private boolean detailed;
    private boolean timed;
    private boolean debugExpired;
    private final Long debugAutoOffAfterMillis;
    private long deadline;
    private String sessionId = UUID.randomUUID().toString();
    private long sequence;
    private long sessionStartMillis;
    private final List<WeakReference<SessionObserver>> observers = new ArrayList<>();

    public CaptureController() { this(false, LogClock.SYSTEM); }
    public CaptureController(boolean baselineEnabled, LogClock clock) {
        this(baselineEnabled, false, null, clock);
    }
    public CaptureController(boolean baselineEnabled, boolean debugEnabled, LogClock clock) {
        this(baselineEnabled, debugEnabled, null, clock);
    }

    /** The optional limit applies to each off-to-on transition; null enables process-lifetime debugging. */
    public CaptureController(boolean baselineEnabled, boolean debugEnabled, Long debugAutoOffAfterMillis, LogClock clock) {
        if (debugAutoOffAfterMillis != null && (debugAutoOffAfterMillis < 1 || debugAutoOffAfterMillis > 86_400_000L)) {
            throw new IllegalArgumentException("Invalid debug duration");
        }
        this.baselineEnabled = baselineEnabled;
        this.debugAutoOffAfterMillis = debugAutoOffAfterMillis;
        this.clock = Objects.requireNonNull(clock, "clock");
        sessionStartMillis = clock.monotonicMillis();
        if (debugEnabled) setDebugEnabled(true);
    }

    /** Idempotent, process-local control. Does not change baseline policy, visibility or retained history. */
    public void setDebugEnabled(boolean enabled) {
        if (!enter()) throw new IllegalStateException("Capture is busy");
        try {
            if (closed) throw new IllegalStateException("Capture is closed");
            long now = clock.monotonicMillis();
            expire(now);
            if (detailed == enabled) return;
            long nextDeadline = 0;
            if (enabled && debugAutoOffAfterMillis != null) {
                if (now > Long.MAX_VALUE - debugAutoOffAfterMillis) throw new IllegalArgumentException("Invalid clock deadline");
                nextDeadline = now + debugAutoOffAfterMillis;
            }
            detailed = enabled;
            timed = enabled && debugAutoOffAfterMillis != null;
            deadline = nextDeadline;
            debugExpired = false;
            replaceSession(now);
        } finally { lock.unlock(); }
    }

    public void startDetailed() { startDetailed(900_000L); }

    /** Expiry returns to the configured baseline or off state. */
    public void startDetailed(long durationMillis) {
        if (durationMillis < 1 || durationMillis > 86_400_000L) {
            throw new IllegalArgumentException("Invalid capture duration");
        }
        if (!enter()) throw new IllegalStateException("Capture is busy");
        try {
            if (closed) throw new IllegalStateException("Capture is closed");
            long now = clock.monotonicMillis();
            if (now > Long.MAX_VALUE - durationMillis) throw new IllegalArgumentException("Invalid clock deadline");
            deadline = now + durationMillis;
            detailed = true;
            timed = true;
            debugExpired = false;
            replaceSession(now);
        } finally { lock.unlock(); }
    }

    public boolean stopDetailed() {
        if (!enter()) return false;
        try {
            if (detailed) { detailed = false; timed = false; debugExpired = false; replaceSession(clock.monotonicMillis()); }
            return true;
        } finally { lock.unlock(); }
    }

    /** Changes fallback capture without interrupting an active detailed session. */
    public boolean setBaselineEnabled(boolean enabled) {
        if (!enter()) return false;
        try {
            if (closed) return !enabled;
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
                timed = false;
                debugExpired = false;
                replaceSession(clock.monotonicMillis());
            }
            return true;
        } finally { lock.unlock(); }
    }

    /** Permanently retires an account/inspection owner; no later enable can reopen it.
     * False means contention: retry before completing the account transition. Dispose the old UI and
     * close its exporters/archives separately. This method never deletes retained evidence. */
    public boolean close() {
        if (!enter()) return false;
        try {
            if (closed) return true;
            closed = true; baselineEnabled = false; detailed = false; timed = false; debugExpired = false;
            replaceSession(clock.monotonicMillis());
            return true;
        } finally { lock.unlock(); }
    }

    /** Null means contention, never a stopped session. */
    public Status status() { return inspect(value -> value); }

    void register(SessionObserver observer) {
        if (!enter()) throw new IllegalStateException("Capture is busy");
        try {
            if (closed) throw new IllegalStateException("Capture is closed");
            pruneObservers();
            if (observers.size() >= 16) throw new IllegalArgumentException("Too many capture observers");
            observers.add(new WeakReference<>(observer));
        } finally { lock.unlock(); }
    }

    private void pruneObservers() {
        Iterator<WeakReference<SessionObserver>> iterator = observers.iterator();
        while (iterator.hasNext()) if (iterator.next().get() == null) iterator.remove();
    }

    <T> T inspect(Operation<Status, T> body) {
        if (!enter()) return null;
        try {
            long now = clock.monotonicMillis();
            expire(now);
            Mode mode = currentMode();
            return body.apply(new Status(mode, baselineEnabled, mode == Mode.OFF ? null : sessionId,
                    detailed && timed ? deadline - now : 0, detailed && timed, debugExpired, closed));
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
        if (detailed && timed && now >= deadline) {
            detailed = false; timed = false; debugExpired = true; replaceSession(now);
        }
    }
    private void replaceSession(long now) {
        pruneObservers();
        for (WeakReference<SessionObserver> reference : observers) {
            SessionObserver observer = reference.get();
            if (observer != null) observer.captureSessionDidEnd();
        }
        sessionId = UUID.randomUUID().toString();
        sequence = 0;
        sessionStartMillis = now;
    }
}
