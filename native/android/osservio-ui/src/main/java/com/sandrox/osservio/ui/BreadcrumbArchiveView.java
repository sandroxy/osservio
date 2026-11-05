package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
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
final class BreadcrumbArchiveView extends LinearLayout {
    private final BreadcrumbArchive archive;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView notice;
    private final TextView summary;
    private final TextView empty;
    private final Button refresh;
    private final Button back;
    private final Button copy;
    private final Button delete;
    private final EditText search;
    private final ListView list;
    private final Rows rows = new Rows();
    private List<RetainedRun> runs = Collections.emptyList();
    private final List<BreadcrumbEvent> events = new ArrayList<>();
    private RetainedBreadcrumbs selected;
    private boolean reading;
    private boolean attempted;
    private int generation;
    private Long updatedAt;
    private AlertDialog detail;

    BreadcrumbArchiveView(Context context, BreadcrumbArchive archive) {
        super(context); this.archive = archive; setOrientation(VERTICAL); setPadding(dp(16), dp(8), dp(16), 0);
        LinearLayout controls = new LinearLayout(context);
        refresh = button(R.string.osservio_archive_refresh); refresh.setOnClickListener(view -> { selected = null; read(); });
        back = button(R.string.osservio_archive_back); back.setOnClickListener(view -> {
            View focused = findFocus(); if (focused != null) focused.clearFocus();
            InputMethodManager keyboard = getContext().getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
            selected = null; read();
        });
        copy = button(R.string.osservio_archive_copy_summary); copy.setOnClickListener(view -> {
            if (selected != null) copy(PersistencePresentation.run(getContext(), selected.run));
        });
        for (Button button : new Button[] {back, refresh, copy}) controls.addView(button, new LayoutParams(0, -2, 1));
        addView(controls, new LayoutParams(-1, -2));
        delete = button(R.string.osservio_archive_delete_all); delete.setOnClickListener(view -> confirmDeletion());
        addView(delete, new LayoutParams(-1, -2));
        notice = caption(); addView(notice, new LayoutParams(-1, -2));
        summary = caption(); summary.setTextIsSelectable(true); addView(summary, new LayoutParams(-1, -2));
        search = new EditText(context); search.setHint(R.string.osservio_crumb_search); search.setSingleLine(true); search.setMinHeight(dp(48));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { filter(); }
            @Override public void afterTextChanged(Editable text) { }
        });
        addView(search, new LayoutParams(-1, -2));
        empty = caption(); empty.setPadding(0, dp(24), 0, dp(24)); addView(empty, new LayoutParams(-1, -2));
        list = new ListView(context); list.setAdapter(rows); list.setEmptyView(empty);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (reading) return;
            if (selected == null) open(runs.get(position)); else showDetail(events.get(position));
        });
        addView(list, new LayoutParams(-1, 0, 1)); render();
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); visibility(); }
    @Override protected void onDetachedFromWindow() {
        generation++; reading = false; attempted = false;
        if (detail != null) detail.dismiss(); super.onDetachedFromWindow();
    }
    @Override protected void onVisibilityChanged(View changed, int visibility) { super.onVisibilityChanged(changed, visibility); visibility(); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); visibility(); }
    private void visibility() {
        if (list == null) return;
        if (isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE) {
            if (!attempted && selected == null) read();
        } else {
            generation++; reading = false; attempted = false;
            if (detail != null) detail.dismiss();
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
                    view.getContext().getString(R.string.osservio_archive_loaded), RuntimePresentation.date(view.updatedAt)));
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
            else { view.selected = value; view.search.setText(""); view.notice.setText(R.string.osservio_archive_snapshot); }
            view.render();
        }));
    }
    private void failed(PersistenceStatus.Error error) {
        String message = PersistencePresentation.error(getContext(), error);
        if (updatedAt != null) message += "\n" + getContext().getString(R.string.osservio_runtime_previous) + " · " + RuntimePresentation.date(updatedAt);
        notice.setText(message);
    }
    private void render() {
        refresh.setEnabled(!reading); list.setEnabled(!reading); back.setEnabled(!reading);
        delete.setEnabled(!reading); delete.setText(selected == null ? R.string.osservio_archive_delete_all : R.string.osservio_archive_delete_run);
        back.setVisibility(selected == null ? GONE : VISIBLE); copy.setVisibility(selected == null ? GONE : VISIBLE);
        refresh.setVisibility(selected == null ? VISIBLE : GONE); search.setVisibility(selected == null ? GONE : VISIBLE);
        summary.setText(selected == null ? PersistencePresentation.status(getContext(), archive.persistenceStatus())
            + "\n" + getContext().getString(R.string.osservio_archive_hint) : PersistencePresentation.run(getContext(), selected.run));
        filter();
    }
    private void confirmDeletion() {
        if (reading || detail != null) return;
        String id = selected == null ? null : selected.run.id;
        AlertDialog dialog = new AlertDialog.Builder(getContext())
            .setTitle(id == null ? R.string.osservio_archive_delete_all : R.string.osservio_archive_delete_run)
            .setMessage(R.string.osservio_archive_delete_confirmation)
            .setNegativeButton(R.string.osservio_archive_cancel, null)
            .setPositiveButton(R.string.osservio_archive_delete_confirm, (ignored, button) -> delete(id)).create();
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
        rows.notifyDataSetChanged();
    }
    private void showDetail(BreadcrumbEvent event) {
        if (detail != null) return;
        String content = RuntimePresentation.breadcrumb(getContext(), event);
        TextView text = new TextView(getContext()); text.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        text.setTextIsSelectable(true); text.setPadding(dp(16), dp(16), dp(16), dp(16)); text.setText(content);
        ScrollView scroll = new ScrollView(getContext()); scroll.addView(text);
        AlertDialog dialog = new AlertDialog.Builder(getContext()).setTitle(RuntimePresentation.kind(getContext(), event.kind))
            .setView(scroll).setNegativeButton(R.string.osservio_close, null).setPositiveButton(R.string.osservio_crumb_copy, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> copy(content)));
        dialog.setOnDismissListener(ignored -> detail = null); detail = dialog; dialog.show();
    }
    private void copy(String value) {
        ClipboardManager clipboard = getContext().getSystemService(ClipboardManager.class);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Osservio", value));
    }
    private Button button(int label) { Button button = new Button(getContext()); button.setText(label); button.setAllCaps(false); button.setMinHeight(dp(48)); return button; }
    private TextView caption() { TextView text = new TextView(getContext()); text.setTextAppearance(android.R.style.TextAppearance_Material_Caption); return text; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return selected == null ? runs.size() : events.size(); }
        @Override public Object getItem(int position) { return selected == null ? runs.get(position) : events.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row;
            if (recycled instanceof LinearLayout) row = (LinearLayout) recycled;
            else {
                row = new LinearLayout(getContext()); row.setOrientation(VERTICAL); row.setPadding(dp(4), dp(12), dp(4), dp(12));
                TextView title = new TextView(getContext()); title.setTextAppearance(android.R.style.TextAppearance_Material_Body1); title.setMaxLines(2);
                TextView subtitle = caption(); subtitle.setMaxLines(3);
                row.addView(title, new LayoutParams(-1, -2)); row.addView(subtitle, new LayoutParams(-1, -2));
            }
            String title, subtitle;
            if (selected == null) { RetainedRun run = runs.get(position); title = PersistencePresentation.title(getContext(), run); subtitle = PersistencePresentation.counts(getContext(), run); }
            else {
                BreadcrumbEvent event = events.get(position); title = RuntimePresentation.kind(getContext(), event.kind) + " · " + event.label;
                subtitle = RuntimePresentation.date(event.timestampMillis) + " · #" + event.sequence + (event.message == null ? "" : "\n" + event.message);
            }
            ((TextView) row.getChildAt(0)).setText(title); ((TextView) row.getChildAt(1)).setText(subtitle); return row;
        }
    }
}
