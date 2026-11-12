package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.sandrox.osservio.core.BreadcrumbArchive;
import com.sandrox.osservio.core.PersistenceStatus;
import com.sandrox.osservio.core.RetainedRun;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;

/** Explicit archive inspection and deletion. Opening the view does not flush, enable capture, or infer crashes. */
@SuppressLint("ViewConstructor")
final class BreadcrumbArchiveView extends LinearLayout implements InspectorScreen.Content {
    private final BreadcrumbArchive archive;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView notice;
    private final TextView summary;
    private final TextView empty;
    private final Button refresh;
    private final android.widget.ProgressBar activity;
    private final ListView list;
    private final LinearLayout controls;
    private final android.widget.ImageButton actions;
    private final TextView footer;
    private final Rows rows = new Rows();
    private List<RetainedRun> runs = Collections.emptyList();
    private boolean reading;
    private boolean attempted;
    private int generation;
    private Long updatedAt;
    private InspectorScreen detail;

    BreadcrumbArchiveView(Context context, BreadcrumbArchive archive) {
        super(context); this.archive = archive; setOrientation(VERTICAL);
        LinearLayout header = new LinearLayout(context); header.setOrientation(VERTICAL); header.setPadding(0, dp(20), 0, dp(16));
        controls = new LinearLayout(context); controls.setGravity(android.view.Gravity.END);
        refresh = button(R.string.osservio_archive_refresh); refresh.setOnClickListener(view -> read());
        header.addView(controls, new LayoutParams(-1, -2));
        refresh.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        refresh.setPadding(dp(16), dp(12), dp(48), dp(12));
        InspectorAppearance.style(refresh, () -> { refresh.setTextColor(InspectorWidgets.actionColors(context, InspectorTheme.accent(context))); InspectorTheme.surface(refresh, true, 24); });
        android.widget.FrameLayout refreshRow = new android.widget.FrameLayout(context);
        refreshRow.addView(refresh, new android.widget.FrameLayout.LayoutParams(-1, -2));
        activity = new android.widget.ProgressBar(context); activity.setVisibility(INVISIBLE);
        activity.setContentDescription(context.getString(R.string.osservio_archive_reading));
        InspectorAppearance.style(activity, () -> activity.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(InspectorTheme.accent(context))));
        android.widget.FrameLayout.LayoutParams progress = new android.widget.FrameLayout.LayoutParams(dp(20), dp(20), android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        progress.rightMargin = dp(16); refreshRow.addView(activity, progress);
        header.addView(refreshRow, new LayoutParams(-1, -2));
        actions = InspectorWidgets.icon(context, R.drawable.osservio_trash, R.string.osservio_archive_delete_all, this::confirmDeletion);
        controls.addView(actions, new LayoutParams(dp(48), -2));
        notice = caption(); notice.setPadding(dp(16), dp(8), dp(16), dp(8)); header.addView(notice, new LayoutParams(-1, -2));
        summary = caption(); summary.setPadding(dp(16), dp(8), dp(16), dp(8)); summary.setTextIsSelectable(true); header.addView(summary, new LayoutParams(-1, -2));
        empty = caption(); empty.setPadding(0, dp(24), 0, dp(24)); header.addView(empty, new LayoutParams(-1, -2));
        list = new InspectorScroll.List(context); list.setPadding(dp(16), 0, dp(16), dp(16)); list.setClipToPadding(false);
        list.addHeaderView(header, null, false); list.setItemsCanFocus(true);
        footer = caption(); footer.setPadding(dp(16), dp(10), dp(16), dp(16)); list.addFooterView(footer, null, false); list.setAdapter(rows);
        list.setDivider(null); list.setDividerHeight(0);
        list.setOnItemClickListener((parent, view, position, id) -> {
            int index = position - list.getHeaderViewsCount();
            if (reading || index < 0 || index >= rows.getCount()) return;
            if (detail == null) open(runs.get(index));
        });
        addView(list, new LayoutParams(-1, 0, 1)); InspectorAppearance.content(this, this::render); render();
    }
    @Override public void bindScreen(InspectorScreen value) {
        value.title(R.string.osservio_ui_saved_runs); value.addAction(actions); controls.setVisibility(GONE);
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); visibility(); }
    @Override protected void onDetachedFromWindow() {
        generation++; reading = false; attempted = false;
        if (!InspectorNavigation.owns(getContext()) && detail != null) detail.dismiss(); super.onDetachedFromWindow();
    }
    @Override protected void onVisibilityChanged(View changed, int visibility) { super.onVisibilityChanged(changed, visibility); visibility(); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); visibility(); }
    private void visibility() {
        if (list == null) return;
        if (isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE) {
            if (!attempted) read();
        } else {
            generation++; reading = false; attempted = false;
            if (!InspectorNavigation.owns(getContext()) && detail != null) detail.dismiss();
        }
    }
    private int begin() {
        reading = true; attempted = true; generation++;
        render(); return generation;
    }
    private void read() {
        if (reading) return;
        int request = begin(); Handler handler = main;
        WeakReference<BreadcrumbArchiveView> reference = new WeakReference<>(this);
        archive.readRuns((value, error) -> handler.post(() -> {
            BreadcrumbArchiveView view = reference.get(); if (view == null || view.generation != request) return;
            view.reading = false;
            if (error != null || value == null) view.failed(error == null ? PersistenceStatus.Error.UNAVAILABLE : error);
            else {
                view.runs = value; view.updatedAt = System.currentTimeMillis();
                view.notice.setText(view.getContext().getString(R.string.osservio_runtime_pair,
                    view.getContext().getString(R.string.osservio_archive_loaded), RuntimePresentation.date(view.getContext(), view.updatedAt)));
            }
            view.render();
        }));
    }
    private void open(RetainedRun run) {
        int request = begin(); Handler handler = main;
        WeakReference<BreadcrumbArchiveView> reference = new WeakReference<>(this);
        archive.readRun(run.id, (value, error) -> handler.post(() -> {
            BreadcrumbArchiveView view = reference.get(); if (view == null || view.generation != request) return;
            view.reading = false;
            if (error != null) view.failed(error);
            else if (value == null) view.notice.setText(R.string.osservio_archive_missing);
            view.render();
            if (error == null && value != null) {
                InspectorScreen child = InspectorDialogs.screen(view.getContext(), R.string.osservio_ui_retained_run,
                    new RetainedBreadcrumbView(view.getContext(), value, view.archive));
                view.detail = child; child.setOnDismissListener(ignored -> { if (view.detail == child) view.detail = null; }); child.show();
            }
        }));
    }
    private void failed(PersistenceStatus.Error error) {
        String message = PersistencePresentation.error(getContext(), error);
        if (updatedAt != null) message += "\n" + getContext().getString(R.string.osservio_runtime_previous) + " · " + RuntimePresentation.date(getContext(), updatedAt);
        notice.setText(message);
    }
    private void render() {
        refresh.setEnabled(!reading); list.setEnabled(!reading);
        activity.setVisibility(reading ? VISIBLE : INVISIBLE);
        actions.setEnabled(!reading && !runs.isEmpty());
        notice.setVisibility(notice.length() == 0 ? GONE : VISIBLE);
        summary.setText(PersistencePresentation.status(getContext(), archive.persistenceStatus()));
        footer.setText(R.string.osservio_archive_hint);
        filter();
    }
    private void confirmDeletion() {
        if (reading || detail != null) return;
        InspectorScreen dialog = InspectorDialogs.confirm(getContext(),
            R.string.osservio_archive_delete_all,
            getContext().getString(R.string.osservio_archive_delete_confirmation),
            R.string.osservio_archive_delete_confirm, (ignored, button) -> deleteAll());
        dialog.setOnDismissListener(ignored -> detail = null); detail = dialog; dialog.show();
    }
    private void deleteAll() {
        int request = begin(); Handler handler = main;
        WeakReference<BreadcrumbArchiveView> reference = new WeakReference<>(this);
        BreadcrumbArchive.Completion completed = error -> handler.post(() -> {
            BreadcrumbArchiveView view = reference.get(); if (view == null || view.generation != request) return;
            view.reading = false;
            if (error == null) view.read();
            else { view.failed(error); view.render(); }
        });
        archive.deleteAllRuns(completed);
    }
    private void filter() {
        if (list == null) return;
        empty.setText(reading ? R.string.osservio_archive_reading : updatedAt == null
            ? R.string.osservio_archive_hint : R.string.osservio_archive_empty);
        empty.setVisibility(runs.isEmpty() ? VISIBLE : GONE);
        rows.notifyDataSetChanged();
    }
    private Button button(int label) { return InspectorWidgets.button(getContext(), label, () -> { }); }
    private TextView caption() { return InspectorWidgets.caption(getContext()); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return runs.size(); }
        @Override public Object getItem(int position) { return runs.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            InspectorWidgets.ValueRow row = recycled instanceof InspectorWidgets.ValueRow ? (InspectorWidgets.ValueRow) recycled : new InspectorWidgets.ValueRow(getContext());
            RetainedRun run = runs.get(position);
            row.bind(PersistencePresentation.title(getContext(), run), PersistencePresentation.counts(getContext(), run), true);
            row.title.setMaxLines(2); row.title.setEllipsize(android.text.TextUtils.TruncateAt.END); row.value.setMaxLines(3);
            row.value.setEllipsize(android.text.TextUtils.TruncateAt.END); InspectorWidgets.groupedRow(row, position == 0, position == getCount() - 1);
            return row;
        }
    }
}
