package com.sandrox.osservio.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import com.sandrox.osservio.core.IncidentExporter;
import com.sandrox.osservio.core.IncidentSnapshot;
import java.lang.ref.WeakReference;

/** Main-thread presentation state. The host retains the exporter independently of this view. */
final class IncidentSharePresentation implements Application.ActivityLifecycleCallbacks {
    interface Notice { void show(int message); }
    private final View owner;
    private final Button button;
    private final IncidentExporter exporter;
    private final IncidentSnapshot snapshot;
    private final Notice notice;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Activity activity;
    private AlertDialog progress;
    private IncidentExporter.Task task;
    private long generation;

    IncidentSharePresentation(View owner, Button button, IncidentExporter exporter, IncidentSnapshot snapshot, Notice notice) {
        this.owner = owner; this.button = button; this.exporter = exporter; this.snapshot = snapshot; this.notice = notice;
        button.setOnClickListener(view -> prepare());
    }
    private void prepare() {
        if (progress != null || !visible() || !owner.hasWindowFocus()) return;
        Activity selected = activity(owner.getContext());
        if (selected == null || selected.isFinishing() || selected.isDestroyed()) { notice.show(R.string.osservio_incident_export_unavailable); return; }
        activity = selected; activity.getApplication().registerActivityLifecycleCallbacks(this);
        long request = ++generation; button.setEnabled(false); notice.show(0);
        progress = new AlertDialog.Builder(owner.getContext()).setTitle(R.string.osservio_incident_share)
            .setMessage(R.string.osservio_incident_export_preparing)
            .setNegativeButton(R.string.osservio_archive_cancel, (dialog, which) -> cancel()).create();
        progress.setCanceledOnTouchOutside(false); progress.setOnCancelListener(dialog -> cancel()); progress.show();
        WeakReference<IncidentSharePresentation> reference = new WeakReference<>(this); Handler handler = main;
        task = exporter.prepare(snapshot, (file, error) -> handler.post(() -> {
            IncidentSharePresentation presentation = reference.get();
            if (presentation != null) presentation.finished(request, file, error);
        }));
    }
    private void finished(long request, IncidentExporter.PreparedFile file, IncidentExporter.Error error) {
        if (request != generation || progress == null) return;
        Activity selected = activity; task = null; release();
        if (!visible() || selected == null || selected.isFinishing() || selected.isDestroyed()) return;
        if (error != null) { if (error != IncidentExporter.Error.CANCELLED) notice.show(message(error)); return; }
        if (file == null) { notice.show(R.string.osservio_incident_export_unavailable); return; }
        Uri uri = Uri.parse(file.contentURI.toASCIIString());
        Intent send = new Intent(Intent.ACTION_SEND).setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri("Osservio", uri));
        try { selected.startActivity(Intent.createChooser(send, owner.getContext().getString(R.string.osservio_incident_share))); }
        catch (ActivityNotFoundException | SecurityException unavailable) { notice.show(R.string.osservio_incident_export_unavailable); }
    }
    void cancel() {
        generation++;
        if (task != null) { task.cancel(); task = null; }
        release();
    }
    private void release() {
        boolean preparing = progress != null;
        if (activity != null) { activity.getApplication().unregisterActivityLifecycleCallbacks(this); activity = null; }
        if (progress != null) { AlertDialog dialog = progress; progress = null; dialog.dismiss(); }
        if (preparing) button.setEnabled(true);
    }
    private boolean visible() { return owner.isAttachedToWindow() && owner.isShown() && owner.getWindowVisibility() == View.VISIBLE; }
    private static Activity activity(Context context) {
        for (int depth = 0; depth < 16; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            Context base = ((ContextWrapper) context).getBaseContext(); if (base == context) return null; context = base;
        }
        return null;
    }
    private static int message(IncidentExporter.Error error) {
        switch (error) {
            case BUSY: return R.string.osservio_incident_export_busy;
            case CLOSED: return R.string.osservio_incident_export_closed;
            case TOO_LARGE: return R.string.osservio_incident_export_too_large;
            case STORAGE_FULL: return R.string.osservio_incident_export_storage_full;
            case INVALID_RECORD: return R.string.osservio_incident_export_invalid;
            default: return R.string.osservio_incident_export_unavailable;
        }
    }
    @Override public void onActivityPaused(Activity value) { if (value == activity) cancel(); }
    @Override public void onActivityDestroyed(Activity value) { if (value == activity) cancel(); }
    @Override public void onActivityCreated(Activity value, Bundle state) { }
    @Override public void onActivityStarted(Activity value) { }
    @Override public void onActivityResumed(Activity value) { }
    @Override public void onActivityStopped(Activity value) { }
    @Override public void onActivitySaveInstanceState(Activity value, Bundle state) { }
}
