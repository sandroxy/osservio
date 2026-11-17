package com.sandrox.osservio.runtime;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.Looper;
import com.sandrox.osservio.core.BreadcrumbEvent;
import com.sandrox.osservio.core.BreadcrumbRecorder;
import com.sandrox.osservio.core.CaptureController;
import com.sandrox.osservio.core.LogMessage;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/** Optional default-network observations. No probes, addresses, identifiers, or implicit permissions. */
public final class NetworkPathSignals {
    public enum StartResult { STARTED, ALREADY_STARTED, UNSUPPORTED, PERMISSION_DENIED, UNAVAILABLE }
    interface Registration { void recordInitial(); void close(); }
    private final Context context;
    private final BreadcrumbRecorder recorder;
    private Registration registration;
    private Lease lease;
    public NetworkPathSignals(Context context, BreadcrumbRecorder recorder) {
        this.context = Objects.requireNonNull(Objects.requireNonNull(context, "context").getApplicationContext(), "applicationContext");
        this.recorder = Objects.requireNonNull(recorder, "recorder");
    }
    /** Main-thread ownership. API 23 deliberately reports unsupported default-network observation. */
    // The optional library intentionally declares no permission. The isolated registration
    // checks the host's normal permission before any query, and denial remains an explicit result.
    @SuppressLint("MissingPermission")
    public StartResult start() {
        requireMain();
        if (registration != null) return lease != null && lease.active ? StartResult.ALREADY_STARTED : StartResult.UNAVAILABLE;
        if (Build.VERSION.SDK_INT < 24) return StartResult.UNSUPPORTED;
        Lease created = new Lease(recorder);
        try {
            registration = Api24NetworkPathRegistration.open(context, created); lease = created;
            // A host sink may stop or restart observation from the initial delivery.
            registration.recordInitial();
            return StartResult.STARTED;
        } catch (SecurityException failure) { created.invalidate(); return StartResult.PERMISSION_DENIED; }
        catch (RuntimeException failure) { created.invalidate(); return StartResult.UNAVAILABLE; }
    }
    /** Returns false if unregistering failed; the inactive registration is retained for a later retry. */
    public boolean stop() {
        requireMain();
        if (lease != null) lease.invalidate();
        if (registration == null) return true;
        try { registration.close(); registration = null; lease = null; return true; }
        catch (RuntimeException failure) { return false; }
    }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Network path controls require the main thread");
    }
    /** Retains only the last successfully admitted bounded description, not an OS network object. */
    static final class Lease {
        private final BreadcrumbRecorder recorder;
        private final ReentrantLock lock = new ReentrantLock();
        volatile boolean active = true;
        private String session, last;
        private boolean callbackObserved;
        Lease(BreadcrumbRecorder recorder) { this.recorder = recorder; }
        void invalidate() { active = false; }
        void record(String description, boolean initial) {
            if (!active || lock.isHeldByCurrentThread() || !lock.tryLock()) return;
            try {
                if (!active || initial && callbackObserved) return;
                if (!initial) callbackObserved = true;
                CaptureController.Status status = recorder.captureController.status();
                if (status == null) return;
                if (status.mode == CaptureController.Mode.OFF) { session = null; last = null; return; }
                if (Objects.equals(session, status.sessionId) && Objects.equals(last, description)) return;
                if (recorder.record(BreadcrumbEvent.Kind.NETWORK_PATH, "android.network.path", LogMessage.publicText(description), null,
                    BreadcrumbEvent.Retention.RESTART_RELEVANT) == BreadcrumbRecorder.Result.RECORDED) {
                    session = status.sessionId; last = description;
                }
            } catch (RuntimeException diagnosticFailure) {
                // A custom diagnostic clock must not escape an OS callback. Failed
                // observations are not deduplicated; a later callback may retry.
            } finally { lock.unlock(); }
        }
    }
}
