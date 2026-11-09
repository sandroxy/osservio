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
    private boolean dirty = true;
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
            state.query.text = value; dirty = true; refresh();
        }, () -> setPaused(!state.paused), this::filters, this::actions);
        list = new ListView(context); list.setDivider(new android.graphics.drawable.ColorDrawable(
            InspectorTheme.color(context, R.color.osservio_separator))); list.setDividerHeight(InspectorTheme.dp(context, 1));
        list.addHeaderView(header, null, false); list.setItemsCanFocus(true); list.setAdapter(rows);
        list.setOnItemClickListener((parent, view, position, id) -> {
            int index = position - list.getHeaderViewsCount();
            if (index < 0 || index >= events.size()) return;
            InspectorWidgets.hideKeyboard(this);
            dialog = LogDetailDialog.create(context, events.get(index)); watchDialog(); dialog.show();
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == SCROLL_STATE_TOUCH_SCROLL) setPaused(true);
            }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) { }
        });
        addView(list, new LayoutParams(-1, -1));
        header.setPaused(state.paused);
        if (state.snapshot != null) render();
        list.setSelectionFromTop(state.scrollPosition, state.scrollOffset);
    }
    private List<LogEvent> retained() { return state.snapshot == null ? Collections.emptyList() : state.snapshot.events; }
    private void refresh() {
        if (list == null) return;
        if (!state.paused) {
            LogSnapshot snapshot = history.snapshot();
            if (snapshot != null) {
                dirty |= state.snapshot == null || state.snapshot.revision != snapshot.revision || state.query.recentMinutes > 0;
                state.snapshot = snapshot; state.timeAnchor = System.currentTimeMillis();
            }
        }
        if (dirty) render();
        LogRecorder.Status status = recorder.status();
        header.status.setText(state.paused ? R.string.osservio_ui_paused : status == null ? R.string.osservio_busy
            : status.isRecording ? R.string.osservio_ui_capturing : R.string.osservio_ui_not_capturing);
        header.setPaused(state.paused);
    }
    private void render() {
        events.clear();
        List<LogEvent> retained = retained();
        for (int i = retained.size() - 1; i >= 0; i--) if (state.query.matches(retained.get(i), state.timeAnchor)) events.add(retained.get(i));
        header.setQuery(state.query, InspectorFilterOptions.logs(getContext(), retained), events.size(), retained.size());
        LogRecorder.Status status = recorder.status();
        header.empty.setText(!retained.isEmpty() ? R.string.osservio_ui_filtered_empty : status == null ? R.string.osservio_busy
            : status.isRecording ? R.string.osservio_ui_waiting : R.string.osservio_ui_inactive_empty);
        rows.notifyDataSetChanged(); dirty = false;
    }
    private void filters() {
        dialog = InspectorFilterSheet.show(getContext(), state.query, InspectorFilterOptions.logs(getContext(), retained()), query -> {
            state.query = query; dirty = true; refresh();
        });
        watchDialog();
    }
    private void actions() {
        LogRecorder.Status status = recorder.status();
        if (status == null) { busy(); return; }
        String[] items = {getContext().getString(status.isRecording ? R.string.osservio_stop : R.string.osservio_start),
            getContext().getString(R.string.osservio_ui_capture_details), getContext().getString(R.string.osservio_clear)};
        dialog = InspectorDialogs.menu(getContext(), R.string.osservio_ui_actions, items, (ignored, which) -> {
            if (which == 0) toggleCapture();
            else if (which == 1) capacity();
            else confirmClear();
        });
        watchDialog();
    }
    private void capacity() {
        LogRecorder.Status status = recorder.status();
        if (status == null) { busy(); return; }
        String content = getContext().getString(R.string.osservio_summary,
            getContext().getString(status.isRecording ? R.string.osservio_capturing : R.string.osservio_stopped),
            retained().size(), state.snapshot == null ? 0L : state.snapshot.evictedCount, status.rateLimitedCount);
        dialog = InspectorDialogs.message(getContext(), R.string.osservio_ui_capture_details, content);
        watchDialog();
    }
    private void confirmClear() {
        dialog = InspectorDialogs.confirm(getContext(), R.string.osservio_clear, getContext().getString(R.string.osservio_ui_clear_hint), R.string.osservio_clear, (ignored, which) -> {
                history.clear(); state.snapshot = history.snapshot(); dirty = true; refresh();
            });
        watchDialog();
    }
    private void toggleCapture() {
        LogRecorder.Status status = recorder.status();
        if (status == null) { busy(); return; }
        if (status.isRecording) { if (!recorder.stop()) busy(); }
        else { try { recorder.start(); } catch (IllegalStateException failure) { busy(); } }
        refresh();
    }
    private void setPaused(boolean paused) {
        if (state.paused == paused) return;
        state.paused = paused; refresh();
        if (!paused) list.setSelection(0);
    }
    private void busy() { Toast.makeText(getContext(), R.string.osservio_busy, Toast.LENGTH_SHORT).show(); }
    private void watchDialog() {
        handler.removeCallbacks(tick); InspectorScreen watched = dialog;
        watched.setOnDismissListener(ignored -> { if (dialog == watched) { dialog = null; schedule(); } });
    }
    void saveReadingPosition() {
        state.scrollPosition = list.getFirstVisiblePosition();
        View first = list.getChildAt(0); state.scrollOffset = first == null ? 0 : first.getTop();
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attached = true; schedule(); }
    @Override protected void onDetachedFromWindow() {
        saveReadingPosition(); attached = false; handler.removeCallbacks(tick);
        if (!InspectorNavigation.owns(getContext()) && dialog != null) { dialog.dismiss(); dialog = null; } super.onDetachedFromWindow();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); schedule(); }
    @Override protected void onVisibilityChanged(View changedView, int visibility) { super.onVisibilityChanged(changedView, visibility); schedule(); }
    private void schedule() {
        if (handler == null || tick == null) return;
        handler.removeCallbacks(tick);
        if (attached && isShown() && getWindowVisibility() == VISIBLE && dialog == null) handler.post(tick);
        else if (!InspectorNavigation.owns(getContext()) && (!attached || !isShown() || getWindowVisibility() != VISIBLE) && dialog != null) { dialog.dismiss(); dialog = null; }
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
            row.bind(event.level == LogLevel.WARNING ? "WARN" : event.level.name(), event.message, event.category + marker, event.timestampMillis, tone);
            return row;
        }
    }
}
