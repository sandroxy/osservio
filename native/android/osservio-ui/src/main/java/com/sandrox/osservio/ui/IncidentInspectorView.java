package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.sandrox.osservio.core.BreadcrumbHistory;
import com.sandrox.osservio.core.EnvironmentProvider;
import com.sandrox.osservio.core.IncidentArchive;
import com.sandrox.osservio.core.IncidentSnapshot;
import com.sandrox.osservio.core.IncidentSummary;
import com.sandrox.osservio.core.PersistenceStatus;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Optional manual problem workflow. The host owns persistence, live histories, and presentation. */
@SuppressLint("ViewConstructor")
public final class IncidentInspectorView extends FrameLayout {
    private final IncidentArchive archive;
    private final EnvironmentProvider environment;
    private final BreadcrumbHistory breadcrumbs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout listing;
    private final TextView notice;
    private final TextView health;
    private final TextView empty;
    private final Button mark;
    private final Button refresh;
    private final Button delete;
    private final ListView list;
    private final Rows rows = new Rows();
    private List<IncidentSummary> snapshots = Collections.emptyList();
    private IncidentSnapshotView selected;
    private IncidentComposer composer;
    private AlertDialog confirmation;
    private boolean reading;
    private boolean attempted;
    private boolean justSaved;
    private int generation;
    private Long updatedAt;

    public IncidentInspectorView(Context context, IncidentArchive archive, EnvironmentProvider environment, BreadcrumbHistory breadcrumbs) {
        super(context); this.archive = Objects.requireNonNull(archive, "archive");
        this.environment = Objects.requireNonNull(environment, "environment"); this.breadcrumbs = Objects.requireNonNull(breadcrumbs, "breadcrumbs");
        setPadding(dp(16), dp(8), dp(16), 0);
        listing = new LinearLayout(context); listing.setOrientation(LinearLayout.VERTICAL); addView(listing, new LayoutParams(-1, -1));
        LinearLayout controls = new LinearLayout(context);
        mark = button(R.string.osservio_incident_mark); mark.setOnClickListener(view -> mark());
        refresh = button(R.string.osservio_incident_refresh); refresh.setOnClickListener(view -> read());
        controls.addView(mark, new LinearLayout.LayoutParams(0, -2, 1)); controls.addView(refresh, new LinearLayout.LayoutParams(0, -2, 1));
        listing.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        delete = button(R.string.osservio_incident_delete_all); delete.setOnClickListener(view -> confirmDeletion(null));
        listing.addView(delete, new LinearLayout.LayoutParams(-1, -2));
        notice = caption(); notice.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE); listing.addView(notice, new LinearLayout.LayoutParams(-1, -2));
        health = caption(); listing.addView(health, new LinearLayout.LayoutParams(-1, -2));
        empty = caption(); empty.setPadding(0, dp(24), 0, dp(24)); listing.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        list = new ListView(context);
        TextView hint = caption(); hint.setText(R.string.osservio_incident_snapshot_hint); hint.setPadding(0, dp(16), 0, dp(16));
        list.addFooterView(hint, null, false); list.setAdapter(rows);
        list.setOnItemClickListener((parent, view, position, id) -> { if (!reading && position < snapshots.size()) open(snapshots.get(position).id); });
        listing.addView(list, new LinearLayout.LayoutParams(-1, 0, 1)); render();
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); visibility(); }
    @Override protected void onDetachedFromWindow() { invalidateQueries(); dismissDialogs(); super.onDetachedFromWindow(); }
    @Override protected void onVisibilityChanged(View changed, int visibility) { super.onVisibilityChanged(changed, visibility); visibility(); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); visibility(); }
    private void visibility() {
        if (listing == null) return;
        if (isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE) {
            if (!attempted && selected == null) read();
        } else {
            invalidateQueries();
            // Keep a draft through app backgrounding, but do not leave dialogs above a hidden or detached SDK view.
            if (!isShown() || !isAttachedToWindow()) dismissDialogs();
        }
    }
    private void invalidateQueries() { generation++; reading = false; attempted = false; }
    private void dismissDialogs() {
        if (composer != null) composer.dismiss();
        if (confirmation != null) confirmation.dismiss();
        if (selected != null) selected.dismissDialogs();
    }
    private int begin() {
        reading = true; attempted = true; generation++;
        notice.setText(R.string.osservio_archive_reading); render(); return generation;
    }
    private void read() {
        if (reading) return;
        int request = begin(); WeakReference<IncidentInspectorView> reference = new WeakReference<>(this); Handler handler = main;
        archive.readIncidents((value, error) -> handler.post(() -> {
            IncidentInspectorView view = reference.get(); if (view == null || view.generation != request) return;
            view.reading = false;
            if (error != null || value == null) view.failed(error == null ? PersistenceStatus.Error.UNAVAILABLE : error);
            else {
                view.snapshots = value; view.updatedAt = System.currentTimeMillis();
                String message = view.getContext().getString(R.string.osservio_archive_loaded) + " · " + RuntimePresentation.date(view.updatedAt);
                view.notice.setText(view.justSaved ? view.getContext().getString(R.string.osservio_incident_saved_read_at, RuntimePresentation.date(view.updatedAt)) : message);
                view.justSaved = false;
            }
            view.render();
        }));
    }
    private void open(String id) {
        int request = begin(); WeakReference<IncidentInspectorView> reference = new WeakReference<>(this); Handler handler = main;
        archive.readIncident(id, (snapshot, error) -> handler.post(() -> {
            IncidentInspectorView view = reference.get(); if (view == null || view.generation != request) return;
            view.reading = false;
            if (error != null) view.failed(error);
            else if (snapshot == null) view.notice.setText(R.string.osservio_incident_missing);
            else {
                view.selected = new IncidentSnapshotView(view.getContext(), snapshot, view::showList, () -> view.confirmDeletion(snapshot.id));
                view.listing.setVisibility(GONE); view.addView(view.selected, new LayoutParams(-1, -1));
            }
            view.render();
        }));
    }
    private void showList() {
        hideKeyboard();
        if (selected != null) { removeView(selected); selected = null; }
        listing.setVisibility(VISIBLE); read();
    }
    private void mark() {
        if (reading || composer != null) return;
        final IncidentSnapshot snapshot;
        try { snapshot = IncidentSnapshot.capture(null, null, breadcrumbs.snapshot()); }
        catch (IllegalArgumentException failure) { notice.setText(R.string.osservio_incident_mark_failed); return; }
        composer = new IncidentComposer(getContext(), snapshot, archive, environment, () -> {
            justSaved = true;
            if (isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE) read();
            else attempted = false;
        }, () -> composer = null);
        composer.show();
    }
    private void confirmDeletion(String id) {
        if (reading || confirmation != null) return;
        confirmation = new AlertDialog.Builder(getContext())
            .setTitle(id == null ? R.string.osservio_incident_delete_all : R.string.osservio_incident_delete)
            .setMessage(R.string.osservio_incident_delete_confirmation).setNegativeButton(R.string.osservio_archive_cancel, null)
            .setPositiveButton(R.string.osservio_archive_delete_confirm, (dialog, which) -> delete(id)).create();
        confirmation.setOnDismissListener(ignored -> confirmation = null); confirmation.show();
    }
    private void delete(String id) {
        int request = begin(); notice.setText(R.string.osservio_archive_deleting);
        WeakReference<IncidentInspectorView> reference = new WeakReference<>(this); Handler handler = main;
        IncidentArchive.Completion completion = error -> handler.post(() -> {
            IncidentInspectorView view = reference.get(); if (view == null || view.generation != request) return;
            view.reading = false; view.render();
            if (error == null) view.showList(); else view.failed(error);
        });
        if (id == null) archive.deleteAllIncidents(completion); else archive.deleteIncident(id, completion);
    }
    private void failed(PersistenceStatus.Error error) {
        String message = PersistencePresentation.error(getContext(), error);
        if (updatedAt != null) message += "\n" + getContext().getString(R.string.osservio_runtime_previous) + " · " + RuntimePresentation.date(updatedAt);
        notice.setText(message); if (selected != null) selected.error(message);
    }
    private void render() {
        mark.setEnabled(!reading); refresh.setEnabled(!reading); delete.setEnabled(!reading && !snapshots.isEmpty());
        list.setEnabled(!reading); health.setText(PersistencePresentation.status(getContext(), archive.persistenceStatus(), R.string.osservio_incident_status));
        empty.setVisibility(snapshots.isEmpty() ? VISIBLE : GONE);
        empty.setText(reading ? R.string.osservio_archive_reading : R.string.osservio_incident_empty);
        if (selected != null) selected.busy(reading); rows.notifyDataSetChanged();
    }
    private void hideKeyboard() {
        View focused = findFocus(); if (focused != null) focused.clearFocus();
        InputMethodManager keyboard = getContext().getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
    }
    private Button button(int label) { Button button = new Button(getContext()); button.setText(label); button.setAllCaps(false); button.setMinHeight(dp(48)); return button; }
    private TextView caption() { TextView text = new TextView(getContext()); text.setTextAppearance(android.R.style.TextAppearance_Material_Caption); return text; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return snapshots.size(); }
        @Override public Object getItem(int position) { return snapshots.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row;
            if (recycled instanceof LinearLayout) row = (LinearLayout) recycled;
            else {
                row = new LinearLayout(getContext()); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(dp(4), dp(12), dp(4), dp(12));
                TextView title = new TextView(getContext()); title.setTextAppearance(android.R.style.TextAppearance_Material_Body1); title.setMaxLines(2);
                TextView subtitle = caption(); subtitle.setMaxLines(3);
                row.addView(title, new LinearLayout.LayoutParams(-1, -2)); row.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));
            }
            IncidentSummary snapshot = snapshots.get(position);
            ((TextView) row.getChildAt(0)).setText(RuntimePresentation.date(snapshot.timestampMillis));
            ((TextView) row.getChildAt(1)).setText(getContext().getString(R.string.osservio_incident_row_detail,
                IncidentPresentation.note(getContext(), snapshot.note), IncidentPresentation.attachments(getContext(), snapshot)));
            return row;
        }
    }
}
