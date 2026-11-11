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
import com.sandrox.osservio.core.*;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One query spans HTTP exchanges and Socket observations; content search is bounded and cancellable. */
@SuppressLint("ViewConstructor")
class NetworkInspectorView extends LinearLayout implements InspectorToolbarActions.Provider {
    @Override public View navigationAction() { return header.navigationAction(); }
    static final class State {
        InspectorQuery query = new InspectorQuery();
        HTTPSnapshot http; WebSocketSnapshot sockets;
        boolean paused; long timeAnchor; int position, offset;
    }
    private final HTTPRecorder httpRecorder;
    private final HTTPHistory httpHistory;
    private final WebSocketRecorder socketRecorder;
    private final WebSocketHistory socketHistory;
    private final String connectionID;
    private final State state;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<InspectorRecordSearch.Record> items = new ArrayList<>();
    private final Rows rows = new Rows();
    private final ListView list;
    private final InspectorFeedHeader header;
    private boolean attached, stale;
    private boolean dirty = true;
    private InspectorScreen dialog;
    private InspectorRecordSearchTask task;
    private InspectorRecordSearch.Result result;
    private long generation, nextSearchAt, timeCheck;
    private int available;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (attached && isShown() && getWindowVisibility() == VISIBLE && dialog == null) { refresh(); handler.postDelayed(this, 500); }
        }
    };
    NetworkInspectorView(Context host, HTTPRecorder httpRecorder, HTTPHistory httpHistory,
                         WebSocketRecorder socketRecorder, WebSocketHistory socketHistory, State state) {
        this(host, httpRecorder, httpHistory, socketRecorder, socketHistory, state, null);
    }
    private NetworkInspectorView(Context host, HTTPRecorder httpRecorder, HTTPHistory httpHistory,
                                 WebSocketRecorder socketRecorder, WebSocketHistory socketHistory, State state, String connectionID) {
        super(InspectorTheme.context(host));
        this.httpRecorder = httpRecorder; this.httpHistory = httpHistory; this.socketRecorder = socketRecorder; this.socketHistory = socketHistory;
        this.state = state; this.connectionID = connectionID;
        Context context = getContext(); setOrientation(VERTICAL); InspectorAppearance.style(this, () -> setBackgroundColor(InspectorTheme.canvas(context)));
        header = new InspectorFeedHeader(context, R.string.osservio_ui_network_search, state.query.text,
            value -> { InspectorQuery query = state.query.copy(); query.text = value; apply(query); }, this::followLatest, this::filters, this::confirmClear, true, R.string.osservio_ui_network_clear);
        header.onQueryChange = this::apply;
        if (connectionID != null) {
            android.widget.TextView hint = InspectorWidgets.caption(context); InspectorAppearance.text(hint, () -> context.getString(R.string.osservio_ui_connection_hint) + "\n" + connectionID);
            header.addView(hint, 0, new LayoutParams(-1, -2)); header.actions.setVisibility(GONE);
        }
        list = new ListView(context); list.setDivider(null); list.setDividerHeight(0);
        list.addHeaderView(header, null, false); list.setItemsCanFocus(true); list.setAdapter(rows);
        list.setOnItemClickListener((parent, view, position, id) -> {
            int index = position - list.getHeaderViewsCount(); if (index >= 0 && index < items.size()) open(items.get(index));
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == SCROLL_STATE_TOUCH_SCROLL) { state.paused = true; cancelSearch(); dirty = false; status(); }
                else if (scrollState == SCROLL_STATE_IDLE && list.getFirstVisiblePosition() == 0 && list.getChildAt(0) != null && list.getChildAt(0).getTop() >= 0) { state.paused = false; refresh(); }
            }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) { }
        });
        addView(header.viewport(list), new LayoutParams(-1, -1));
        InspectorAppearance.content(this, () -> { list.setDivider(null); display(); });
        if ((state.http != null || state.sockets != null) && !state.query.searchesPayload()) search(); else display();
        list.setSelectionFromTop(state.position, state.offset);
    }
    private List<HTTPEvent> http() { return state.http == null ? Collections.emptyList() : state.http.events; }
    private List<WebSocketEvent> sockets() { return state.sockets == null ? Collections.emptyList() : state.sockets.events; }
    private List<InspectorFilterSheet.Group> groups() {
        return InspectorFilterOptions.network(getContext(), http(), sockets(), state.query, httpHistory != null, socketHistory != null);
    }
    private void cancelSearch() { generation++; if (task != null) task.cancel(); task = null; dirty = true; }
    private void apply(InspectorQuery query) {
        cancelSearch(); state.query = query; result = null; items.clear(); nextSearchAt = android.os.SystemClock.uptimeMillis() + 200;
        if (list != null) { display(); schedule(); }
    }
    private void refresh() {
        if (list == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (!state.paused && task == null) {
            state.timeAnchor = System.currentTimeMillis(); stale = false;
            if (httpRecorder != null) httpRecorder.maintenance();
            if (httpHistory != null) {
                HTTPSnapshot snapshot = httpHistory.snapshot(); stale |= snapshot == null;
                if (snapshot != null) { dirty |= state.http == null || snapshot.revision != state.http.revision; state.http = snapshot; }
            }
            if (socketHistory != null) {
                WebSocketSnapshot snapshot = socketHistory.snapshot(); stale |= snapshot == null;
                if (snapshot != null) { dirty |= state.sockets == null || snapshot.revision != state.sockets.revision; state.sockets = snapshot; }
            }
            if (state.query.recentMinutes > 0 && now - timeCheck >= 5000) { dirty = true; timeCheck = now; }
        }
        if (dirty && task == null && now >= nextSearchAt) search(); status();
    }
    private void search() {
        List<InspectorRecordSearch.Record> records = new ArrayList<>();
        if (connectionID == null) for (HTTPEvent event : http()) records.add(new InspectorRecordSearch.Record(event));
        for (WebSocketEvent event : sockets()) if (connectionID == null || connectionID.equals(event.connectionId)) records.add(new InspectorRecordSearch.Record(event));
        Collections.sort(records, InspectorRecordSearch::newestFirst); available = records.size();
        InspectorQuery query = state.query.copy(); long anchor = state.timeAnchor;
        if (!query.searchesPayload()) {
            items.clear(); for (InspectorRecordSearch.Record record : records) if (record.matchesFacets(query, anchor)) items.add(record);
            result = null; dirty = false; display(); return;
        }
        HTTPHistory requests = httpHistory; WebSocketHistory socketEvents = socketHistory; long current = generation;
        WeakReference<NetworkInspectorView> owner = new WeakReference<>(this);
        task = InspectorRecordSearchTask.submit(cancelled -> InspectorRecordSearch.run(records, query, anchor, requests, socketEvents, InspectorRecordSearch.MAX_UNITS, cancelled), found -> {
            NetworkInspectorView view = owner.get(); if (view == null || view.generation != current) return;
            view.task = null; view.result = found; view.items.clear();
            for (InspectorRecordSearch.Record record : records) if (found.keys.contains(record.key())) view.items.add(record);
            view.dirty = false; view.nextSearchAt = android.os.SystemClock.uptimeMillis() + 1000; view.display();
        });
        if (task == null) nextSearchAt = android.os.SystemClock.uptimeMillis() + 500;
    }
    private void status() {
        List<String> notes = new ArrayList<>(); Context context = getContext();
        if (task != null || dirty && state.query.searchesPayload()) notes.add(context.getString(R.string.osservio_ui_searching));
        if (result != null && (result.limited || result.unavailable > 0 || result.truncated > 0)) notes.add(context.getString(R.string.osservio_ui_search_partial));
        if (stale) notes.add(context.getString(R.string.osservio_ui_previous_window));
        WebSocketRecorder.Status socket = socketRecorder == null ? null : socketRecorder.status();
        if ((state.http != null && (state.http.evictedEvents > 0 || state.http.evictedBodies > 0))
            || (state.sockets != null && (state.sockets.evictedEvents > 0 || state.sockets.evictedPayloads > 0))
            || (socket != null && socket.rateLimitedCount > 0)) notes.add(context.getString(R.string.osservio_ui_retention_loss));
        header.status.setText(android.text.TextUtils.join(" · ", notes)); header.setPaused(state.paused);
    }
    private void display() {
        header.setQuery(state.query, groups(), items.size(), available);
        header.empty.setText(dirty && state.query.searchesPayload() ? R.string.osservio_ui_searching : available > 0 ? R.string.osservio_ui_filtered_empty
            : stale ? R.string.osservio_busy : isDetailed() ? R.string.osservio_ui_waiting : R.string.osservio_ui_inactive_empty);
        rows.notifyDataSetChanged(); status();
    }
    private boolean isDetailed() {
        CaptureController.Status http = httpRecorder == null ? null : httpRecorder.captureController.status();
        CaptureController.Status socket = socketRecorder == null ? null : socketRecorder.captureController.status();
        return http != null && http.debugEnabled || socket != null && socket.debugEnabled;
    }
    private void filters() { dialog = InspectorFilterSheet.show(getContext(), state.query, groups(), true, this::apply); watchDialog(); }
    private void confirmClear() {
        dialog = InspectorDialogs.confirm(getContext(), R.string.osservio_ui_network_clear, getContext().getString(R.string.osservio_ui_clear_hint), R.string.osservio_ui_network_clear, (ignored, which) -> {
            cancelSearch(); if (httpHistory != null) { httpHistory.clear(); state.http = httpHistory.snapshot(); }
            if (socketHistory != null) { socketHistory.clear(); state.sockets = socketHistory.snapshot(); }
            result = null; items.clear(); nextSearchAt = 0; search();
        }); watchDialog();
    }
    private void open(InspectorRecordSearch.Record item) {
        if (dialog != null) return; InspectorWidgets.hideKeyboard(this);
        if (item.http != null) dialog = new HTTPDetailDialog(getContext(), item.http, httpHistory).dialog;
        else {
            WebSocketDetailDialog detail = new WebSocketDetailDialog(getContext(), item.socket, socketHistory);
            detail.showConnection(() -> {
                String id = item.socket.connectionId;
                NetworkInspectorView timeline = new NetworkInspectorView(getContext(), null, null, socketRecorder, socketHistory, new State(), id);
                InspectorDialogs.screen(getContext(), R.string.osservio_ui_connection_records, timeline).show();
            }); dialog = detail.dialog;
        }
        watchDialog(); dialog.show();
    }
    private void watchDialog() {
        handler.removeCallbacks(tick); cancelSearch(); InspectorScreen watched = dialog;
        watched.setOnDismissListener(ignored -> { if (dialog == watched) { dialog = null; schedule(); } });
    }
    private void followLatest() {
        cancelSearch(); nextSearchAt = 0; state.paused = false;
        refresh();
        // Set the anchor after notifying the adapter, which otherwise restores the old row.
        list.setSelectionFromTop(0, 0);
    }
    void saveReadingPosition() { state.position = list.getFirstVisiblePosition(); View first = list.getChildAt(0); state.offset = first == null ? 0 : first.getTop(); }
    private int dp(int value) { return InspectorTheme.dp(getContext(), value); }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) { InspectorKeyboardDismissal.outside(this, event); return super.dispatchTouchEvent(event); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attached = true; schedule(); }
    @Override protected void onDetachedFromWindow() {
        saveReadingPosition(); attached = false; handler.removeCallbacks(tick); cancelSearch();
        if (!InspectorNavigation.owns(getContext()) && dialog != null) { dialog.dismiss(); dialog = null; } super.onDetachedFromWindow();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); schedule(); }
    @Override protected void onVisibilityChanged(View view, int visibility) { super.onVisibilityChanged(view, visibility); schedule(); }
    private void schedule() {
        if (handler == null || tick == null) return; handler.removeCallbacks(tick);
        if (attached && isShown() && getWindowVisibility() == VISIBLE && dialog == null) handler.post(tick);
        else { cancelSearch(); if (!InspectorNavigation.owns(getContext()) && dialog != null && (!attached || !isShown() || getWindowVisibility() != VISIBLE)) { dialog.dismiss(); dialog = null; } }
    }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { InspectorRecordSearch.Record item = items.get(position); return item.http != null ? item.http : item.socket; }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            InspectorWidgets.EventRow row = recycled instanceof InspectorWidgets.EventRow ? (InspectorWidgets.EventRow) recycled : new InspectorWidgets.EventRow(getContext());
            InspectorRecordSearch.Record item = items.get(position); Context context = getContext();
            String preview = result == null ? null : result.previews.get(item.key()); preview = preview == null ? "" : "\n" + preview;
            if (item.http != null) {
                HTTPEvent event = item.http; boolean failed = event.state == HTTPEvent.State.FAILED || event.statusCode != null && event.statusCode >= 400;
                row.bind(event.method + (event.statusCode == null ? "" : " · " + event.statusCode), InspectorQuery.path(event.url),
                    InspectorQuery.host(event.url) + " · " + HTTPPresentation.summary(context, event) + preview, event.timestampMillis,
                    failed ? InspectorTheme.color(context, R.color.osservio_error) : InspectorTheme.accent(context));
            } else {
                WebSocketEvent event = item.socket;
                String direction = event.direction == null ? WebSocketPresentation.kind(context, event.kind) : context.getString(event.direction == WebSocketEvent.Direction.INCOMING ? R.string.osservio_ws_incoming : R.string.osservio_ws_outgoing);
                row.bind("Socket · " + direction, InspectorQuery.path(event.url), WebSocketPresentation.summary(context, event) + " · " + InspectorQuery.host(event.url)
                    + "\n" + event.connectionId.substring(0, 8) + " · #" + event.sequence + preview, event.timestampMillis,
                    event.kind == WebSocketEvent.Kind.FAILED || event.outcome == WebSocketEvent.Outcome.FAILED || event.outcome == WebSocketEvent.Outcome.REJECTED ? InspectorTheme.color(context, R.color.osservio_error) : InspectorTheme.accent(context));
            }
            row.grouped(position == 0, position == getCount() - 1); return row;
        }
    }
}
