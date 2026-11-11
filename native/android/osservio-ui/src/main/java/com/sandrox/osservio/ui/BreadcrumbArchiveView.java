package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.sandrox.osservio.core.BreadcrumbArchive;
import com.sandrox.osservio.core.BreadcrumbEvent;
import com.sandrox.osservio.core.PersistenceStatus;
import com.sandrox.osservio.core.RetainedBreadcrumbs;
import com.sandrox.osservio.core.RetainedRun;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Explicit archive inspection and deletion. Opening the view does not flush, enable capture, or infer crashes. */
@SuppressLint("ViewConstructor")
final class BreadcrumbArchiveView extends LinearLayout implements InspectorScreen.Content {
    private final BreadcrumbArchive archive;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView notice;
    private final TextView summary;
    private final TextView empty;
    private final Button refresh;
    private final android.widget.ImageButton back;
    private final android.widget.ImageButton copy;
    private final Button delete;
    private final EditText search;
    private final ListView list;
    private final LinearLayout controls;
    private final android.widget.ImageButton actions;
    private final TextView footer;
    private InspectorScreen screen;
    private final Rows rows = new Rows();
    private List<RetainedRun> runs = Collections.emptyList();
    private final List<BreadcrumbEvent> events = new ArrayList<>();
    private RetainedBreadcrumbs selected;
    private boolean reading;
    private boolean attempted;
    private int generation;
    private Long updatedAt;
    private InspectorScreen detail;

    BreadcrumbArchiveView(Context context, BreadcrumbArchive archive) {
        super(context); this.archive = archive; setOrientation(VERTICAL);
        LinearLayout header = new LinearLayout(context); header.setOrientation(VERTICAL); header.setPadding(0, dp(20), 0, dp(16));
        controls = new LinearLayout(context); controls.setGravity(android.view.Gravity.END);
        refresh = button(R.string.osservio_archive_refresh); refresh.setOnClickListener(view -> { selected = null; read(); });
        back = InspectorWidgets.icon(context, R.drawable.osservio_back, R.string.osservio_archive_back, () -> { }); back.setOnClickListener(view -> {
            View focused = findFocus(); if (focused != null) focused.clearFocus();
            InputMethodManager keyboard = getContext().getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
            selected = null; read();
        });
        copy = InspectorWidgets.icon(context, R.drawable.osservio_copy, R.string.osservio_archive_copy_summary, () -> { }); copy.setOnClickListener(view -> {
            if (selected != null) copy(PersistencePresentation.run(getContext(), selected.run));
        });
        controls.addView(back); controls.addView(copy);
        header.addView(controls, new LayoutParams(-1, -2));
        refresh.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        refresh.setPadding(dp(16), dp(12), dp(16), dp(12));
        InspectorAppearance.style(refresh, () -> { refresh.setTextColor(InspectorTheme.accent(context)); InspectorTheme.surface(refresh, true, 24); });
        header.addView(refresh, new LayoutParams(-1, -2));
        delete = button(R.string.osservio_archive_delete_all); delete.setOnClickListener(view -> confirmDeletion());
        actions = InspectorWidgets.icon(context, R.drawable.osservio_more, R.string.osservio_ui_actions, () -> {
            detail = InspectorDialogs.menu(context, R.string.osservio_ui_actions, new String[] {context.getString(R.string.osservio_ui_capture_details), delete.getText().toString()}, (dialog, which) -> {
                    dialog.dismiss(); detail = null;
                    if (which == 1) confirmDeletion();
                    else {
                        detail = InspectorDialogs.message(context, R.string.osservio_ui_capture_details, PersistencePresentation.status(context, archive.persistenceStatus()));
                        detail.setOnDismissListener(ignored -> detail = null);
                    }
                });
            InspectorScreen shown = detail; shown.setOnDismissListener(ignored -> { if (detail == shown) detail = null; });
        });
        controls.addView(actions, new LayoutParams(dp(48), -2));
        search = InspectorWidgets.search(context, R.string.osservio_crumb_search, "", ignored -> filter());
        header.addView(search, new LayoutParams(-1, -2));
        notice = caption(); notice.setPadding(dp(16), dp(8), dp(16), dp(8)); header.addView(notice, new LayoutParams(-1, -2));
        summary = caption(); summary.setPadding(dp(16), dp(8), dp(16), dp(8)); summary.setTextIsSelectable(true); header.addView(summary, new LayoutParams(-1, -2));
        empty = caption(); empty.setPadding(0, dp(24), 0, dp(24)); header.addView(empty, new LayoutParams(-1, -2));
        list = new ListView(context); list.setPadding(dp(16), 0, dp(16), dp(16)); list.setClipToPadding(false);
        list.addHeaderView(header, null, false); list.setItemsCanFocus(true);
        footer = caption(); footer.setPadding(dp(16), dp(10), dp(16), dp(16)); list.addFooterView(footer, null, false); list.setAdapter(rows);
        list.setDivider(null); list.setDividerHeight(0);
        list.setOnItemClickListener((parent, view, position, id) -> {
            int index = position - list.getHeaderViewsCount();
            if (reading || index < 0 || index >= rows.getCount()) return;
            if (selected == null) open(runs.get(index)); else showDetail(events.get(index));
        });
        addView(list, new LayoutParams(-1, 0, 1)); InspectorAppearance.content(this, this::render); render();
    }
    @Override public void bindScreen(InspectorScreen value) {
        screen = value; screen.addAction(copy); screen.addAction(actions); controls.setVisibility(GONE);
        screen.setBackInterceptor(() -> {
            if (selected == null) return false;
            InspectorWidgets.hideKeyboard(this); selected = null; search.setText(""); read(); return true;
        }); render();
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
            if (!attempted && selected == null) read();
        } else {
            generation++; reading = false; attempted = false;
            if (!InspectorNavigation.owns(getContext()) && detail != null) detail.dismiss();
        }
    }
    private int begin() {
        reading = true; attempted = true; generation++;
        notice.setText(R.string.osservio_archive_reading); render(); return generation;
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
            else { view.selected = value; view.search.setText(""); view.notice.setText(""); view.list.setSelection(0); }
            view.render();
        }));
    }
    private void failed(PersistenceStatus.Error error) {
        String message = PersistencePresentation.error(getContext(), error);
        if (updatedAt != null) message += "\n" + getContext().getString(R.string.osservio_runtime_previous) + " · " + RuntimePresentation.date(getContext(), updatedAt);
        notice.setText(message);
    }
    private void render() {
        refresh.setEnabled(!reading); list.setEnabled(!reading); back.setEnabled(!reading);
        delete.setEnabled(!reading); delete.setText(selected == null ? R.string.osservio_archive_delete_all : R.string.osservio_archive_delete_run);
        back.setVisibility(selected == null ? GONE : VISIBLE); copy.setVisibility(selected == null ? GONE : VISIBLE);
        refresh.setVisibility(selected == null ? VISIBLE : GONE); search.setVisibility(selected == null ? GONE : VISIBLE);
        if (screen != null) screen.title(selected == null ? R.string.osservio_ui_saved_runs : R.string.osservio_archive_title);
        notice.setVisibility(notice.length() == 0 ? GONE : VISIBLE);
        summary.setText(selected == null ? PersistencePresentation.status(getContext(), archive.persistenceStatus()) : PersistencePresentation.run(getContext(), selected.run));
        footer.setText(selected == null ? R.string.osservio_archive_hint : R.string.osservio_archive_snapshot);
        filter();
    }
    private void confirmDeletion() {
        if (reading || detail != null) return;
        String id = selected == null ? null : selected.run.id;
        InspectorScreen dialog = InspectorDialogs.confirm(getContext(),
            id == null ? R.string.osservio_archive_delete_all : R.string.osservio_archive_delete_run,
            getContext().getString(R.string.osservio_archive_delete_confirmation),
            R.string.osservio_archive_delete_confirm, (ignored, button) -> delete(id));
        dialog.setOnDismissListener(ignored -> detail = null); detail = dialog; dialog.show();
    }
    private void delete(String id) {
        int request = begin(); notice.setText(R.string.osservio_archive_deleting); Handler handler = main;
        WeakReference<BreadcrumbArchiveView> reference = new WeakReference<>(this);
        BreadcrumbArchive.Completion completed = error -> handler.post(() -> {
            BreadcrumbArchiveView view = reference.get(); if (view == null || view.generation != request) return;
            view.reading = false;
            if (error == null) { view.selected = null; view.read(); }
            else { view.failed(error); view.render(); }
        });
        if (id == null) archive.deleteAllRuns(completed); else archive.deleteRun(id, completed);
    }
    private void filter() {
        if (list == null) return;
        events.clear();
        if (selected != null) {
            String query = search.getText().toString().toLowerCase(Locale.ROOT);
            for (int i = selected.events.size() - 1; i >= 0; i--) {
                BreadcrumbEvent event = selected.events.get(i);
                if (query.isEmpty() || event.label.toLowerCase(Locale.ROOT).contains(query)
                    || RuntimePresentation.kind(getContext(), event.kind).toLowerCase(Locale.ROOT).contains(query)
                    || (event.message != null && event.message.toLowerCase(Locale.ROOT).contains(query))) events.add(event);
            }
        }
        empty.setText(selected == null ? reading ? R.string.osservio_archive_reading : updatedAt == null
            ? R.string.osservio_archive_hint : R.string.osservio_archive_empty : R.string.osservio_ws_no_matches);
        empty.setVisibility(rows.getCount() == 0 ? VISIBLE : GONE);
        rows.notifyDataSetChanged();
    }
    private void showDetail(BreadcrumbEvent event) {
        if (detail != null) return;
        InspectorScreen dialog = BreadcrumbDetailDialog.create(getContext(), event);
        dialog.setOnDismissListener(ignored -> detail = null); detail = dialog; dialog.show();
    }
    private void copy(String value) {
        ClipboardManager clipboard = getContext().getSystemService(ClipboardManager.class);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Osservio", value));
    }
    private Button button(int label) { return InspectorWidgets.button(getContext(), label, () -> { }); }
    private TextView caption() { return InspectorWidgets.caption(getContext()); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return selected == null ? runs.size() : events.size(); }
        @Override public Object getItem(int position) { return selected == null ? runs.get(position) : events.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            InspectorWidgets.ValueRow row = recycled instanceof InspectorWidgets.ValueRow ? (InspectorWidgets.ValueRow) recycled : new InspectorWidgets.ValueRow(getContext());
            if (selected == null) {
                RetainedRun run = runs.get(position);
                row.bind(PersistencePresentation.title(getContext(), run), PersistencePresentation.counts(getContext(), run), true);
            } else {
                BreadcrumbEvent event = events.get(position);
                row.bind(RuntimePresentation.kind(getContext(), event.kind) + " · " + event.label,
                    RuntimePresentation.date(getContext(), event.timestampMillis) + " · #" + event.sequence + (event.message == null ? "" : "\n" + event.message), true);
            }
            row.title.setMaxLines(2); row.title.setEllipsize(android.text.TextUtils.TruncateAt.END); row.value.setMaxLines(3);
            row.value.setEllipsize(android.text.TextUtils.TruncateAt.END); InspectorWidgets.groupedRow(row, position == 0, position == getCount() - 1);
            return row;
        }
    }
}
