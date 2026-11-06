package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import com.sandrox.osservio.core.CaptureController;
import com.sandrox.osservio.core.WebSocketEvent;
import com.sandrox.osservio.core.WebSocketHistory;
import com.sandrox.osservio.core.WebSocketRecorder;
import com.sandrox.osservio.core.WebSocketSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.text.DateFormat;
import java.util.Date;
import android.view.WindowManager;
import java.util.Locale;

/** Connection groups and event timelines. Hiding or pausing rendering never changes capture. */
// Explicit runtime dependencies make this a programmatic view, not an XML-inflatable widget.
@SuppressLint("ViewConstructor")
public final class WebSocketInspectorView extends LinearLayout {
    private final WebSocketRecorder recorder;
    private final WebSocketHistory history;
    private final String connectionId;
    private final Map<String, Integer> counts = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView summary;
    private final TextView empty;
    private final EditText search;
    private final Spinner filter;
    private final Button capture;
    private final Button pause;
    private final ListView list;
    private final Rows rows = new Rows();
    private final List<WebSocketEvent> events = new ArrayList<>();
    private WebSocketSnapshot latest;
    private boolean paused;
    private boolean dirty = true;
    private boolean attached;
    private AlertDialog detail;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!attached || !isShown() || getWindowVisibility() != VISIBLE || detail != null) return;
            refreshView();
            handler.postDelayed(this, 250L);
        }
    };
    public WebSocketInspectorView(Context context, WebSocketRecorder recorder, WebSocketHistory history) {
        this(context, recorder, history, null);
    }
    private WebSocketInspectorView(Context context, WebSocketRecorder recorder, WebSocketHistory history, String connectionId) {
        super(context);
        this.recorder = recorder; this.history = history; this.connectionId = connectionId;
        setOrientation(VERTICAL); setPadding(dp(16), dp(8), dp(16), 0);
        LinearLayout header = new LinearLayout(context); header.setOrientation(VERTICAL);
        summary = new TextView(context);
        summary.setTextAppearance(android.R.style.TextAppearance_Material_Caption);
        header.addView(summary, new LayoutParams(-1, -2));
        capture = button(R.string.osservio_http_start);
        capture.setOnClickListener(view -> toggleCapture());
        pause = button(R.string.osservio_pause);
        pause.setOnClickListener(view -> { setPaused(!paused); refreshView(); });
        Button clear = button(R.string.osservio_ws_clear);
        clear.setOnClickListener(view -> { history.clear(); latest = history.snapshot(); dirty = true; refreshView(); });
        header.addView(new InspectorControls(context, capture, pause, clear), new LayoutParams(-1, -2));
        search = new EditText(context);
        search.setSingleLine(true); search.setHint(R.string.osservio_ws_search); search.setMinHeight(dp(48));
        header.addView(search, new LayoutParams(-1, -2));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { dirty = true; refreshView(); }
            @Override public void afterTextChanged(Editable value) { }
        });
        filter = new Spinner(context);
        ArrayAdapter<String> options = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item,
            new String[] {context.getString(R.string.osservio_ws_all), context.getString(R.string.osservio_ws_issues),
                context.getString(R.string.osservio_ws_messages)});
        options.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        filter.setAdapter(options); filter.setMinimumHeight(dp(48));
        filter.setContentDescription(context.getString(R.string.osservio_ws_filter));
        header.addView(filter, new LayoutParams(-1, -2));
        filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { dirty = true; refreshView(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        empty = new TextView(context);
        empty.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        empty.setPadding(0, dp(24), 0, dp(24));
        header.addView(empty, new LayoutParams(-1, -2));
        list = new ListView(context);
        list.addHeaderView(header, null, false); list.setItemsCanFocus(true);
        list.setAdapter(rows);
        list.setOnItemClickListener((parent, view, position, id) -> {
            int index = position - list.getHeaderViewsCount();
            if (index >= 0 && index < events.size()) showDetail(events.get(index));
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {
                if (state == SCROLL_STATE_TOUCH_SCROLL) setPaused(true);
            }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) { }
        });
        addView(list, new LayoutParams(-1, 0, 1));
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attached = true; schedule(); }
    @Override protected void onDetachedFromWindow() {
        attached = false; handler.removeCallbacks(refresh);
        if (detail != null) detail.dismiss();
        super.onDetachedFromWindow();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); schedule(); }
    @Override protected void onVisibilityChanged(View view, int visibility) { super.onVisibilityChanged(view, visibility); schedule(); }
    private void schedule() {
        // Construction can dispatch visibility changes before field initialization.
        if (handler == null || refresh == null) return;
        handler.removeCallbacks(refresh);
        if (attached && isShown() && getWindowVisibility() == VISIBLE) handler.post(refresh);
        else if (detail != null) detail.dismiss();
    }
    private void refreshView() {
        if (list == null || filter == null) return;
        if (!paused) {
            WebSocketSnapshot snapshot = history.snapshot();
            if (snapshot != null) {
                if (latest == null || latest.revision != snapshot.revision) dirty = true;
                latest = snapshot;
            }
        }
        if (dirty) render();
        CaptureController.Status status = recorder.captureController.status();
        WebSocketRecorder.Status socketStatus = recorder.status();
        if (status != null && socketStatus != null && latest != null) {
            capture.setText(status.mode == CaptureController.Mode.DETAILED ? R.string.osservio_http_stop : R.string.osservio_http_start);
            int state = status.mode == CaptureController.Mode.DETAILED ? R.string.osservio_http_detailed
                : status.mode == CaptureController.Mode.BASELINE ? R.string.osservio_http_baseline : R.string.osservio_http_off;
            summary.setText(getContext().getString(R.string.osservio_ws_summary, getContext().getString(state),
                latest.events.size(), latest.evictedEvents, latest.evictedPayloads, socketStatus.rateLimitedCount));
        }
    }
    private void render() {
        String query = search.getText().toString().toLowerCase(Locale.ROOT);
        int selected = filter.getSelectedItemPosition();
        events.clear(); counts.clear();
        if (latest != null) {
            for (int i = latest.events.size() - 1; i >= 0; i--) {
                WebSocketEvent event = latest.events.get(i);
                boolean matchesFilter = selected == 0 || (selected == 1 && WebSocketPresentation.hasIssue(event))
                    || (selected == 2 && WebSocketPresentation.isMessage(event));
                if ((connectionId == null || connectionId.equals(event.connectionId)) && matchesFilter
                    && (query.isEmpty() || event.url.toLowerCase(Locale.ROOT).contains(query)
                        || event.connectionId.toLowerCase(Locale.ROOT).contains(query)
                        || event.id.toLowerCase(Locale.ROOT).contains(query)
                        || WebSocketPresentation.summary(getContext(), event).toLowerCase(Locale.ROOT).contains(query))) {
                    Integer count = counts.get(event.connectionId);
                    counts.put(event.connectionId, count == null ? 1 : count + 1);
                    if (connectionId != null || count == null) events.add(event);
                }
            }
        }
        empty.setText(latest == null ? R.string.osservio_busy : latest.events.isEmpty() ? R.string.osservio_ws_empty : R.string.osservio_ws_no_matches);
        empty.setVisibility(events.isEmpty() ? VISIBLE : GONE);
        rows.notifyDataSetChanged(); dirty = false;
    }
    private void toggleCapture() {
        CaptureController.Status status = recorder.captureController.status();
        if (status == null) { busy(); return; }
        if (status.mode == CaptureController.Mode.DETAILED) { if (!recorder.captureController.stopDetailed()) busy(); }
        else { try { recorder.captureController.startDetailed(); } catch (IllegalStateException failure) { busy(); } }
        refreshView();
    }
    private void setPaused(boolean value) { paused = value; pause.setText(value ? R.string.osservio_resume : R.string.osservio_pause); }
    private void busy() { Toast.makeText(getContext(), R.string.osservio_busy, Toast.LENGTH_SHORT).show(); }
    private Button button(int title) {
        Button button = new Button(getContext()); button.setText(title); button.setAllCaps(false); button.setMinHeight(dp(48));
        return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void showDetail(WebSocketEvent event) {
        if (detail != null) return;
        if (connectionId == null) {
            WebSocketInspectorView timeline = new WebSocketInspectorView(getContext(), recorder, history, event.connectionId);
            detail = new AlertDialog.Builder(getContext()).setTitle(event.connectionId.substring(0, 8))
                .setView(timeline).setNegativeButton(R.string.osservio_close, null).create();
            detail.setOnShowListener(ignored -> {
                if (detail.getWindow() != null) detail.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            });
        } else detail = new WebSocketDetailDialog(getContext(), event, history).dialog;
        handler.removeCallbacks(refresh);
        detail.setOnDismissListener(dialog -> { detail = null; schedule(); });
        detail.show();
    }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return events.size(); }
        @Override public WebSocketEvent getItem(int position) { return events.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row;
            if (recycled instanceof LinearLayout) row = (LinearLayout) recycled;
            else {
                row = new LinearLayout(getContext()); row.setOrientation(VERTICAL);
                row.setPadding(dp(4), dp(12), dp(4), dp(12));
                TextView title = new TextView(getContext());
                title.setTextAppearance(android.R.style.TextAppearance_Material_Body1); title.setMaxLines(2);
                row.addView(title, new LayoutParams(-1, -2));
                TextView subtitle = new TextView(getContext());
                subtitle.setTextAppearance(android.R.style.TextAppearance_Material_Caption);
                row.addView(subtitle, new LayoutParams(-1, -2));
            }
            WebSocketEvent event = getItem(position);
            ((TextView) row.getChildAt(0)).setText(connectionId == null ? event.url : WebSocketPresentation.summary(getContext(), event));
            ((TextView) row.getChildAt(1)).setText(connectionId == null
                ? event.connectionId.substring(0, 8) + " · " + getContext().getString(R.string.osservio_ws_group, counts.get(event.connectionId), WebSocketPresentation.kind(getContext(), event.kind))
                : DateFormat.getTimeInstance().format(new Date(event.timestampMillis)) + " · #" + event.sequence);
            return row;
        }
    }
}
