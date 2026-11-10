package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toast;
import com.sandrox.osservio.core.LogEvent;
import com.sandrox.osservio.core.LogHistory;
import com.sandrox.osservio.core.LogLevel;
import com.sandrox.osservio.core.LogRecorder;
import com.sandrox.osservio.core.LogSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Native live logs. Visibility, reading pause, and filters never change capture. */
// Histories are explicit dependencies; XML inflation is not supported.
@SuppressLint("ViewConstructor")
public final class LogInspectorView extends LinearLayout {
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        InspectorKeyboardDismissal.outside(this, event); return super.dispatchTouchEvent(event);
    }

    static final class State {
        InspectorQuery query = new InspectorQuery();
        LogSnapshot snapshot;
        boolean paused;
        long timeAnchor;
        int scrollPosition;
        int scrollOffset;
    }
    private final LogRecorder recorder;
    private final LogHistory history;
    private final State state;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<LogEvent> events = new ArrayList<>();
    private final ListView list;
    private final InspectorFeedHeader header;
    private final Rows rows = new Rows();
    private boolean attached;
    private boolean dirty = true, stale;
    private InspectorRecordSearchTask task;
    private InspectorRecordSearch.Result result;
    private long generation, nextSearchAt, timeCheck;
    private InspectorScreen dialog;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (attached && isShown() && getWindowVisibility() == VISIBLE && dialog == null) { refresh(); handler.postDelayed(this, 500); }
        }
    };

    public LogInspectorView(Context context, LogRecorder recorder, LogHistory history) {
        this(context, recorder, history, new State());
    }
    LogInspectorView(Context host, LogRecorder recorder, LogHistory history, State state) {
        super(InspectorTheme.context(host));
        Context context = getContext();
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.history = Objects.requireNonNull(history, "history"); this.state = state;
        setOrientation(VERTICAL); setBackgroundColor(InspectorTheme.canvas(context));
        header = new InspectorFeedHeader(context, R.string.osservio_search, state.query.text, value -> {
            InspectorQuery query = state.query.copy(); query.text = value; apply(query);
        }, this::followLatest, this::filters, this::confirmClear, true);
        header.onQueryChange = this::apply;
        list = new ListView(context); list.setDivider(null); list.setDividerHeight(0);
        list.addHeaderView(header, null, false); list.setItemsCanFocus(true); list.setAdapter(rows);
        list.setOnItemClickListener((parent, view, position, id) -> {
            int index = position - list.getHeaderViewsCount();
            if (index < 0 || index >= events.size()) return;
            InspectorWidgets.hideKeyboard(this);
            dialog = LogDetailDialog.create(context, events.get(index)); watchDialog(); dialog.show();
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == SCROLL_STATE_TOUCH_SCROLL) { state.paused = true; cancelSearch(); dirty = false; status(); }
                else if (scrollState == SCROLL_STATE_IDLE && list.getFirstVisiblePosition() == 0 && list.getChildAt(0) != null && list.getChildAt(0).getTop() >= 0) { state.paused = false; refresh(); }
            }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) { }
        });
        addView(list, new LayoutParams(-1, -1));
        header.setPaused(state.paused);
        InspectorAppearance.content(this, this::display);
        if (state.snapshot != null && !state.query.searchesPayload()) search(); else display();
        list.setSelectionFromTop(state.scrollPosition, state.scrollOffset);
    }
    private List<LogEvent> retained() { return state.snapshot == null ? Collections.emptyList() : state.snapshot.events; }
    private void cancelSearch() { generation++; if (task != null) task.cancel(); task = null; dirty = true; }
    private void apply(InspectorQuery query) {
        cancelSearch(); state.query = query; result = null; events.clear(); nextSearchAt = android.os.SystemClock.uptimeMillis() + 200;
        if (list != null) { display(); schedule(); }
    }
    private void refresh() {
        if (list == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (!state.paused && task == null) {
            LogSnapshot snapshot = history.snapshot(); stale = snapshot == null;
            if (snapshot != null) { dirty |= state.snapshot == null || state.snapshot.revision != snapshot.revision; state.snapshot = snapshot; state.timeAnchor = System.currentTimeMillis(); }
            if (state.query.recentMinutes > 0 && now - timeCheck >= 5000) { dirty = true; timeCheck = now; }
        }
        if (dirty && task == null && now >= nextSearchAt) search(); status();
    }
    private void status() {
        List<String> notes = new ArrayList<>(); Context context = getContext();
        if (task != null || dirty && state.query.searchesPayload()) notes.add(context.getString(R.string.osservio_ui_searching));
        if (result != null && (result.limited || result.unavailable > 0 || result.truncated > 0)) notes.add(context.getString(R.string.osservio_ui_search_partial));
        if (stale) notes.add(context.getString(R.string.osservio_ui_previous_window));
        LogRecorder.Status status = recorder.status();
        if ((state.snapshot != null && state.snapshot.evictedCount > 0) || status != null && status.rateLimitedCount > 0) notes.add(context.getString(R.string.osservio_ui_retention_loss));
        header.status.setText(android.text.TextUtils.join(" · ", notes)); header.setPaused(state.paused);
    }
    private void search() {
        List<InspectorRecordSearch.Record> records = new ArrayList<>();
        List<LogEvent> retained = retained();
        for (int i = retained.size() - 1; i >= 0; i--) records.add(new InspectorRecordSearch.Record(retained.get(i)));
        InspectorQuery query = state.query.copy(); long anchor = state.timeAnchor;
        if (!query.searchesPayload()) {
            events.clear(); for (InspectorRecordSearch.Record record : records) if (record.matchesFacets(query, anchor)) events.add(record.log);
            result = null; dirty = false; display(); return;
        }
        long current = generation; java.lang.ref.WeakReference<LogInspectorView> owner = new java.lang.ref.WeakReference<>(this);
        task = InspectorRecordSearchTask.submit(cancelled -> InspectorRecordSearch.run(records, query, anchor, null, null, InspectorRecordSearch.MAX_UNITS, cancelled), found -> {
            LogInspectorView view = owner.get(); if (view == null || view.generation != current) return;
            view.task = null; view.result = found; view.events.clear();
            for (InspectorRecordSearch.Record record : records) if (found.keys.contains(record.key())) view.events.add(record.log);
            view.dirty = false; view.nextSearchAt = android.os.SystemClock.uptimeMillis() + 1000; view.display();
        });
        if (task == null) nextSearchAt = android.os.SystemClock.uptimeMillis() + 500;
    }
    private void display() {
        header.setQuery(state.query, InspectorFilterOptions.logs(getContext(), retained()), events.size(), retained().size());
        LogRecorder.Status status = recorder.status();
        header.empty.setText(dirty && state.query.searchesPayload() ? R.string.osservio_ui_searching : !retained().isEmpty() ? R.string.osservio_ui_filtered_empty
            : stale || status == null ? R.string.osservio_busy : status.isRecording ? R.string.osservio_ui_waiting : R.string.osservio_ui_inactive_empty);
        rows.notifyDataSetChanged(); status();
    }
    private void filters() { dialog = InspectorFilterSheet.show(getContext(), state.query, InspectorFilterOptions.logs(getContext(), retained()), true, this::apply); watchDialog(); }
    private void confirmClear() {
        dialog = InspectorDialogs.confirm(getContext(), R.string.osservio_clear, getContext().getString(R.string.osservio_ui_clear_hint), R.string.osservio_clear, (ignored, which) -> {
                cancelSearch(); history.clear(); state.snapshot = history.snapshot(); result = null; events.clear(); nextSearchAt = 0; search();
            });
        watchDialog();
    }
    private void followLatest() { state.paused = false; dirty = true; list.setSelection(0); refresh(); }
    private void watchDialog() {
        handler.removeCallbacks(tick); cancelSearch(); InspectorScreen watched = dialog;
        watched.setOnDismissListener(ignored -> { if (dialog == watched) { dialog = null; schedule(); } });
    }
    void saveReadingPosition() {
        state.scrollPosition = list.getFirstVisiblePosition();
        View first = list.getChildAt(0); state.scrollOffset = first == null ? 0 : first.getTop();
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attached = true; schedule(); }
    @Override protected void onDetachedFromWindow() {
        saveReadingPosition(); attached = false; handler.removeCallbacks(tick); cancelSearch();
        if (!InspectorNavigation.owns(getContext()) && dialog != null) { dialog.dismiss(); dialog = null; } super.onDetachedFromWindow();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); schedule(); }
    @Override protected void onVisibilityChanged(View changedView, int visibility) { super.onVisibilityChanged(changedView, visibility); schedule(); }
    private void schedule() {
        if (handler == null || tick == null) return;
        handler.removeCallbacks(tick);
        if (attached && isShown() && getWindowVisibility() == VISIBLE && dialog == null) handler.post(tick);
        else { cancelSearch(); if (!InspectorNavigation.owns(getContext()) && (!attached || !isShown() || getWindowVisibility() != VISIBLE) && dialog != null) { dialog.dismiss(); dialog = null; } }
    }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return events.size(); }
        @Override public LogEvent getItem(int position) { return events.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            InspectorWidgets.EventRow row = recycled instanceof InspectorWidgets.EventRow
                ? (InspectorWidgets.EventRow) recycled : new InspectorWidgets.EventRow(getContext());
            LogEvent event = events.get(position);
            int tone = event.level == LogLevel.ERROR ? InspectorTheme.color(getContext(), R.color.osservio_error)
                : event.level == LogLevel.WARNING ? InspectorTheme.color(getContext(), R.color.osservio_warning) : InspectorTheme.secondary(getContext());
            String marker = event.isTruncated ? " · " + getContext().getString(R.string.osservio_truncated) : "";
            String preview = result == null ? null : result.previews.get(new InspectorRecordSearch.Record(event).key());
            row.bind(event.level == LogLevel.WARNING ? "WARN" : event.level.name(), preview == null ? event.message.substring(0, Math.min(600, event.message.length())) : preview, event.category + marker, event.timestampMillis, tone);
            return row;
        }
    }
}
