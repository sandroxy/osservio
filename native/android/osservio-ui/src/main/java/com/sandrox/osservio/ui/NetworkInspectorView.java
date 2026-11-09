package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toast;
import com.sandrox.osservio.core.CaptureController;
import com.sandrox.osservio.core.HTTPEvent;
import com.sandrox.osservio.core.HTTPHistory;
import com.sandrox.osservio.core.HTTPRecorder;
import com.sandrox.osservio.core.HTTPSnapshot;
import com.sandrox.osservio.core.WebSocketEvent;
import com.sandrox.osservio.core.WebSocketHistory;
import com.sandrox.osservio.core.WebSocketRecorder;
import com.sandrox.osservio.core.WebSocketSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One row per exchange or connection; message timelines are a separate reading scope. */
// Histories and capture controllers must be supplied programmatically.
@SuppressLint("ViewConstructor")
class NetworkInspectorView extends LinearLayout {
    static final class State {
        final InspectorQuery[] queries = {new InspectorQuery(), new InspectorQuery(), new InspectorQuery()};
        final int[] positions = new int[3];
        final int[] offsets = new int[3];
        HTTPSnapshot http;
        WebSocketSnapshot sockets;
        int scope;
        boolean paused;
        long timeAnchor;
    }
    private final HTTPRecorder httpRecorder;
    private final HTTPHistory httpHistory;
    private final WebSocketRecorder socketRecorder;
    private final WebSocketHistory socketHistory;
    private final String connectionID;
    private final State state;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Item> items = new ArrayList<>();
    private List<InspectorQuery.Connection> connections = Collections.emptyList();
    private final Rows rows = new Rows();
    private final ListView list;
    private final InspectorFeedHeader header;
    private final List<Button> scopeButtons = new ArrayList<>();
    private boolean attached;
    private boolean dirty = true;
    private boolean changingScope;
    private InspectorScreen dialog;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (attached && isShown() && getWindowVisibility() == VISIBLE && dialog == null) {
                refresh(); handler.postDelayed(this, 500);
            }
        }
    };

    NetworkInspectorView(Context host, HTTPRecorder httpRecorder, HTTPHistory httpHistory,
                         WebSocketRecorder socketRecorder, WebSocketHistory socketHistory, State state) {
        this(host, httpRecorder, httpHistory, socketRecorder, socketHistory, state, null);
    }
    private NetworkInspectorView(Context host, HTTPRecorder httpRecorder, HTTPHistory httpHistory,
                                 WebSocketRecorder socketRecorder, WebSocketHistory socketHistory, State state, String connectionID) {
        super(InspectorTheme.context(host));
        this.httpRecorder = httpRecorder; this.httpHistory = httpHistory;
        this.socketRecorder = socketRecorder; this.socketHistory = socketHistory;
        this.state = state; this.connectionID = connectionID;
        if (httpHistory == null) state.scope = 2;
        else if (socketHistory == null) state.scope = 1;
        Context context = getContext(); setOrientation(VERTICAL); setBackgroundColor(InspectorTheme.canvas(context));
        header = new InspectorFeedHeader(context, connectionID == null ? R.string.osservio_ui_network_search : R.string.osservio_ws_search,
            query().text, value -> { if (!changingScope) { query().text = value; dirty = true; refresh(); } },
            () -> setPaused(!state.paused), this::filters, this::actions);
        if (connectionID == null && httpHistory != null && socketHistory != null) {
            LinearLayout scopes = new LinearLayout(context); scopes.setPadding(0, dp(8), 0, dp(8));
            String[] labels = {context.getString(R.string.osservio_ui_all), "HTTP", "WebSocket"};
            for (int i = 0; i < labels.length; i++) {
                final int scope = i;
                Button button = InspectorWidgets.button(context, R.string.osservio_ui_all, () -> selectScope(scope));
                button.setText(labels[i]); InspectorWidgets.chip(button, i == state.scope);
                LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -2, 1);
                if (i > 0) layout.leftMargin = dp(6);
                scopes.addView(button, layout); scopeButtons.add(button);
            }
            header.addView(scopes, 0, new LayoutParams(-1, -2));
        }
        if (connectionID != null) {
            android.widget.TextView explanation = InspectorWidgets.caption(context);
            explanation.setText(R.string.osservio_ui_connection_hint); header.addView(explanation, 0, new LayoutParams(-1, -2));
        }
        list = new ListView(context); list.setDivider(new android.graphics.drawable.ColorDrawable(
            InspectorTheme.color(context, R.color.osservio_separator))); list.setDividerHeight(dp(1));
        list.addHeaderView(header, null, false); list.setItemsCanFocus(true); list.setAdapter(rows);
        list.setOnItemClickListener((parent, view, position, id) -> {
            int index = position - list.getHeaderViewsCount();
            if (index >= 0 && index < items.size()) open(items.get(index));
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == SCROLL_STATE_TOUCH_SCROLL) setPaused(true);
            }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) { }
        });
        addView(list, new LayoutParams(-1, -1)); header.setPaused(state.paused);
        if (state.sockets != null) connections = InspectorQuery.connections(state.sockets.events);
        render(); list.setSelectionFromTop(state.positions[state.scope], state.offsets[state.scope]);
    }
    private InspectorQuery query() { return state.queries[state.scope]; }
    private List<HTTPEvent> http() { return state.http == null ? Collections.emptyList() : state.http.events; }
    private List<WebSocketEvent> sockets() { return state.sockets == null ? Collections.emptyList() : state.sockets.events; }
    private List<InspectorFilterSheet.Group> groups() {
        return connectionID == null ? InspectorFilterOptions.network(getContext(), http(), connections, state.scope, query())
            : InspectorFilterOptions.timeline(getContext());
    }
    private void selectScope(int scope) {
        if (scope == state.scope) return;
        saveReadingPosition(); state.scope = scope;
        changingScope = true; header.search.setText(query().text); changingScope = false;
        for (int i = 0; i < scopeButtons.size(); i++) InspectorWidgets.chip(scopeButtons.get(i), i == scope);
        dirty = true; refresh(); list.setSelectionFromTop(state.positions[scope], state.offsets[scope]);
    }
    private void refresh() {
        if (list == null) return;
        if (httpRecorder != null) httpRecorder.maintenance();
        if (!state.paused) {
            state.timeAnchor = System.currentTimeMillis();
            if (httpHistory != null) {
                HTTPSnapshot snapshot = httpHistory.snapshot();
                if (snapshot != null) { dirty |= state.http == null || snapshot.revision != state.http.revision; state.http = snapshot; }
            }
            if (socketHistory != null) {
                WebSocketSnapshot snapshot = socketHistory.snapshot();
                if (snapshot != null) {
                    boolean changed = state.sockets == null || snapshot.revision != state.sockets.revision;
                    state.sockets = snapshot;
                    if (changed) { connections = InspectorQuery.connections(snapshot.events); dirty = true; }
                }
            }
            dirty |= query().recentMinutes > 0;
        }
        if (dirty) render();
        header.status.setText(state.paused ? getContext().getString(R.string.osservio_ui_paused) : captureStatus());
        header.setPaused(state.paused);
    }
    private String captureStatus() {
        CaptureController http = httpRecorder == null || state.scope == 2 ? null : httpRecorder.captureController;
        CaptureController socket = socketRecorder == null || state.scope == 1 ? null : socketRecorder.captureController;
        if (http != null && socket != null && http != socket) return "HTTP · " + captureLabel(http) + "\nWebSocket · " + captureLabel(socket);
        return captureLabel(http == null ? socket : http);
    }
    private String captureLabel(CaptureController controller) {
        CaptureController.Status status = controller == null ? null : controller.status();
        return getContext().getString(status == null ? R.string.osservio_busy : status.mode == CaptureController.Mode.DETAILED
            ? R.string.osservio_ui_detailed : status.mode == CaptureController.Mode.BASELINE
            ? R.string.osservio_ui_baseline : R.string.osservio_ui_not_capturing);
    }
    private void render() {
        items.clear(); int total = 0;
        if (connectionID != null) {
            for (WebSocketEvent event : sockets()) if (connectionID.equals(event.connectionId)) {
                total++; if (query().matches(event, state.timeAnchor)) items.add(new Item(null, null, event));
            }
        } else {
            if (state.scope != 2) for (HTTPEvent event : http()) {
                total++; if (query().matches(event, state.timeAnchor)) items.add(new Item(event, null, null));
            }
            if (state.scope != 1) for (InspectorQuery.Connection connection : connections) {
                total++; if (query().matches(connection, state.timeAnchor)) items.add(new Item(null, connection, null));
            }
        }
        Collections.sort(items, (left, right) -> {
            if (left.event != null && right.event != null) return InspectorQuery.compareTimeline(left.event, right.event);
            return left.time() == right.time() ? left.id().compareTo(right.id()) : Long.compare(right.time(), left.time());
        });
        header.setQuery(query(), groups(), items.size(), total);
        header.empty.setText(total > 0 ? R.string.osservio_ui_filtered_empty : isDetailed()
            ? R.string.osservio_ui_waiting : R.string.osservio_ui_inactive_empty);
        rows.notifyDataSetChanged(); dirty = false;
    }
    private boolean isDetailed() {
        for (CaptureController controller : controllers()) {
            CaptureController.Status status = controller.status();
            if (status != null && status.mode == CaptureController.Mode.DETAILED) return true;
        }
        return false;
    }
    private List<CaptureController> controllers() {
        List<CaptureController> result = new ArrayList<>();
        if (httpRecorder != null && state.scope != 2) result.add(httpRecorder.captureController);
        if (socketRecorder != null && state.scope != 1 && !result.contains(socketRecorder.captureController)) result.add(socketRecorder.captureController);
        return result;
    }
    private void filters() {
        dialog = InspectorFilterSheet.show(getContext(), query(), groups(), value -> {
            state.queries[state.scope] = value; dirty = true; refresh();
        });
        watchDialog();
    }
    private void actions() {
        List<CaptureController> controllers = controllers();
        List<String> labels = new ArrayList<>();
        for (CaptureController controller : controllers) {
            CaptureController.Status status = controller.status();
            String prefix = controllers.size() == 1 ? "" : controller == httpRecorder.captureController ? "HTTP · " : "WebSocket · ";
            labels.add(prefix + getContext().getString(status != null && status.mode == CaptureController.Mode.DETAILED
                ? R.string.osservio_http_stop : R.string.osservio_http_start));
        }
        labels.add(getContext().getString(R.string.osservio_ui_capture_details));
        labels.add(getContext().getString(R.string.osservio_ui_network_clear));
        dialog = InspectorDialogs.menu(getContext(), R.string.osservio_ui_actions, labels.toArray(new String[0]), (ignored, which) -> {
                // Dismiss before replacing the tracked dialog so its callback cannot clear the new one.
                dismissDialog();
                if (which < controllers.size()) toggleCapture(controllers.get(which));
                else if (which == controllers.size()) capacity();
                else confirmClear();
            });
        watchDialog();
    }
    private void toggleCapture(CaptureController controller) {
        CaptureController.Status status = controller.status();
        if (status == null) { busy(); return; }
        if (status.mode == CaptureController.Mode.DETAILED) { if (!controller.stopDetailed()) busy(); }
        else { try { controller.startDetailed(); } catch (IllegalStateException failure) { busy(); } }
        dirty = true; refresh();
    }
    private void capacity() {
        List<String> lines = new ArrayList<>();
        if (httpRecorder != null && state.scope != 2) lines.add("HTTP\n" + getContext().getString(R.string.osservio_http_summary,
            captureLabel(httpRecorder.captureController), http().size(), state.http == null ? 0L : state.http.evictedEvents,
            state.http == null ? 0L : state.http.evictedBodies));
        if (socketRecorder != null && state.scope != 1) {
            WebSocketRecorder.Status status = socketRecorder.status();
            lines.add("WebSocket\n" + (status == null ? getContext().getString(R.string.osservio_busy)
                : getContext().getString(R.string.osservio_ws_summary, captureLabel(socketRecorder.captureController),
                    sockets().size(), state.sockets == null ? 0L : state.sockets.evictedEvents,
                    state.sockets == null ? 0L : state.sockets.evictedPayloads, status.rateLimitedCount)));
        }
        dialog = InspectorDialogs.message(getContext(), R.string.osservio_ui_capture_details, android.text.TextUtils.join("\n\n", lines)); watchDialog();
    }
    private void confirmClear() {
        dialog = InspectorDialogs.confirm(getContext(), R.string.osservio_ui_network_clear, getContext().getString(R.string.osservio_ui_clear_hint), R.string.osservio_ui_network_clear, (ignored, which) -> {
                if (httpHistory != null && state.scope != 2) { httpHistory.clear(); state.http = httpHistory.snapshot(); }
                if (socketHistory != null && state.scope != 1) { socketHistory.clear(); state.sockets = socketHistory.snapshot(); connections = InspectorQuery.connections(sockets()); }
                dirty = true; refresh();
            }); watchDialog();
    }
    private void open(Item item) {
        if (dialog != null) return;
        InspectorWidgets.hideKeyboard(this);
        if (item.http != null) dialog = new HTTPDetailDialog(getContext(), item.http, httpHistory, httpRecorder).dialog;
        else if (item.event != null) dialog = new WebSocketDetailDialog(getContext(), item.event, socketHistory).dialog;
        else {
            NetworkInspectorView timeline = new NetworkInspectorView(getContext(), null, null, socketRecorder, socketHistory,
                new State(), item.connection.latest.connectionId);
            String id = item.connection.latest.connectionId;
            dialog = InspectorDialogs.screen(getContext(), "WebSocket · " + id.substring(0, Math.min(8, id.length())), timeline);
        }
        watchDialog(); dialog.show();
    }
    private void watchDialog() {
        handler.removeCallbacks(tick);
        InspectorScreen watched = dialog;
        watched.setOnDismissListener(ignored -> { if (dialog == watched) { dialog = null; schedule(); } });
    }
    private void dismissDialog() {
        InspectorScreen previous = dialog; dialog = null; if (previous != null) previous.dismiss(); schedule();
    }
    private void setPaused(boolean paused) {
        if (state.paused == paused) return;
        state.paused = paused; refresh(); if (!paused) list.setSelection(0);
    }
    void saveReadingPosition() {
        state.positions[state.scope] = list.getFirstVisiblePosition();
        View first = list.getChildAt(0); state.offsets[state.scope] = first == null ? 0 : first.getTop();
    }
    private void busy() { Toast.makeText(getContext(), R.string.osservio_busy, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return InspectorTheme.dp(getContext(), value); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attached = true; schedule(); }
    @Override protected void onDetachedFromWindow() {
        saveReadingPosition(); attached = false; handler.removeCallbacks(tick); if (!InspectorNavigation.owns(getContext())) dismissDialog(); super.onDetachedFromWindow();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); schedule(); }
    @Override protected void onVisibilityChanged(View view, int visibility) { super.onVisibilityChanged(view, visibility); schedule(); }
    private void schedule() {
        if (handler == null || tick == null) return;
        handler.removeCallbacks(tick);
        if (attached && isShown() && getWindowVisibility() == VISIBLE && dialog == null) handler.post(tick);
        else if (!InspectorNavigation.owns(getContext()) && (!attached || !isShown() || getWindowVisibility() != VISIBLE) && dialog != null) dismissDialog();
    }
    private static final class Item {
        final HTTPEvent http;
        final InspectorQuery.Connection connection;
        final WebSocketEvent event;
        Item(HTTPEvent http, InspectorQuery.Connection connection, WebSocketEvent event) { this.http = http; this.connection = connection; this.event = event; }
        long time() { return http != null ? http.timestampMillis : connection != null ? connection.first.timestampMillis : event.timestampMillis; }
        String id() { return http != null ? http.id : connection != null ? connection.first.connectionId : event.id; }
    }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) {
            Item item = items.get(position);
            return item.http != null ? item.http : item.connection != null ? item.connection.latest : item.event;
        }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            InspectorWidgets.EventRow row = recycled instanceof InspectorWidgets.EventRow
                ? (InspectorWidgets.EventRow) recycled : new InspectorWidgets.EventRow(getContext());
            Item item = items.get(position); Context context = getContext();
            if (item.http != null) {
                HTTPEvent event = item.http;
                boolean failed = event.state == HTTPEvent.State.FAILED || (event.statusCode != null && event.statusCode >= 400);
                int tone = failed ? InspectorTheme.color(context, R.color.osservio_error) : InspectorTheme.accent(context);
                row.bind(event.method + (event.statusCode == null ? "" : " · " + event.statusCode), InspectorQuery.path(event.url),
                    InspectorQuery.host(event.url) + " · " + HTTPPresentation.summary(context, event), event.timestampMillis, tone);
            } else if (item.connection != null) {
                InspectorQuery.Connection connection = item.connection;
                String observed = connection.lifecycle == null ? context.getString(R.string.osservio_ui_unknown)
                    : WebSocketPresentation.kind(context, connection.lifecycle.kind);
                row.bind("WebSocket", InspectorQuery.path(connection.latest.url), InspectorQuery.host(connection.latest.url) + " · " + observed
                    + " · " + context.getString(R.string.osservio_ui_connection_count, connection.count), connection.first.timestampMillis, InspectorTheme.accent(context));
            } else {
                WebSocketEvent event = item.event;
                String direction = event.direction == null ? "WebSocket" : context.getString(event.direction == WebSocketEvent.Direction.INCOMING
                    ? R.string.osservio_ws_incoming : R.string.osservio_ws_outgoing);
                row.bind(direction, WebSocketPresentation.summary(context, event), "#" + event.sequence + " · " + event.id,
                    event.timestampMillis, event.kind == WebSocketEvent.Kind.FAILED || event.outcome == WebSocketEvent.Outcome.FAILED
                        ? InspectorTheme.color(context, R.color.osservio_error) : InspectorTheme.accent(context));
            }
            return row;
        }
    }
}
