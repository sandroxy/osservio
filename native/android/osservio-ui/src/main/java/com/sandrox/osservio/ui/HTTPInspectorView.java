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
import com.sandrox.osservio.core.HTTPEvent;
import com.sandrox.osservio.core.HTTPHistory;
import com.sandrox.osservio.core.HTTPRecorder;
import com.sandrox.osservio.core.HTTPSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Main-thread HTTP inspector. Hiding or pausing rendering never changes capture state. */
// Explicit runtime dependencies make this a programmatic view, not an XML-inflatable widget.
@SuppressLint("ViewConstructor")
public final class HTTPInspectorView extends LinearLayout {
    private final HTTPRecorder recorder;
    private final HTTPHistory history;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView summary;
    private final TextView empty;
    private final EditText search;
    private final Spinner filter;
    private final Button capture;
    private final Button pause;
    private final ListView list;
    private final Rows rows = new Rows();
    private final List<HTTPEvent> events = new ArrayList<>();
    private HTTPSnapshot latest;
    private boolean paused;
    private boolean dirty = true;
    private boolean attached;
    private AlertDialog detail;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!attached || !isShown() || getWindowVisibility() != VISIBLE) return;
            refreshView();
            handler.postDelayed(this, 250L);
        }
    };
    public HTTPInspectorView(Context context, HTTPRecorder recorder, HTTPHistory history) {
        super(context);
        this.recorder = recorder; this.history = history;
        setOrientation(VERTICAL); setPadding(dp(16), dp(8), dp(16), 0);
        summary = new TextView(context);
        summary.setTextAppearance(android.R.style.TextAppearance_Material_Caption);
        addView(summary, new LayoutParams(-1, -2));
        LinearLayout controls = new LinearLayout(context);
        capture = button(R.string.osservio_http_start);
        capture.setOnClickListener(view -> toggleCapture());
        pause = button(R.string.osservio_pause);
        pause.setOnClickListener(view -> { setPaused(!paused); refreshView(); });
        Button clear = button(R.string.osservio_http_clear);
        clear.setOnClickListener(view -> { history.clear(); latest = history.snapshot(); dirty = true; refreshView(); });
        for (Button button : new Button[] {capture, pause, clear}) controls.addView(button, new LayoutParams(0, -2, 1));
        addView(controls, new LayoutParams(-1, -2));
        search = new EditText(context);
        search.setSingleLine(true); search.setHint(R.string.osservio_http_search); search.setMinHeight(dp(48));
        addView(search, new LayoutParams(-1, -2));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { dirty = true; refreshView(); }
            @Override public void afterTextChanged(Editable value) { }
        });
        filter = new Spinner(context);
        ArrayAdapter<String> options = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item,
            new String[] {context.getString(R.string.osservio_http_all), context.getString(R.string.osservio_http_issues),
                context.getString(R.string.osservio_http_active)});
        options.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        filter.setAdapter(options); filter.setMinimumHeight(dp(48));
        filter.setContentDescription(context.getString(R.string.osservio_http_filter));
        addView(filter, new LayoutParams(-1, -2));
        filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { dirty = true; refreshView(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        empty = new TextView(context);
        empty.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        empty.setPadding(0, dp(24), 0, dp(24));
        addView(empty, new LayoutParams(-1, -2));
        list = new ListView(context);
        list.setAdapter(rows); list.setEmptyView(empty);
        list.setOnItemClickListener((parent, view, position, id) -> showDetail(events.get(position)));
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
        recorder.maintenance();
        if (!paused) {
            HTTPSnapshot snapshot = history.snapshot();
            if (snapshot != null) {
                if (latest == null || latest.revision != snapshot.revision) dirty = true;
                latest = snapshot;
            }
        }
        if (dirty) render();
        CaptureController.Status status = recorder.captureController.status();
        if (status != null) {
            capture.setText(status.mode == CaptureController.Mode.DETAILED ? R.string.osservio_http_stop : R.string.osservio_http_start);
            int state = status.mode == CaptureController.Mode.DETAILED ? R.string.osservio_http_detailed
                : status.mode == CaptureController.Mode.BASELINE ? R.string.osservio_http_baseline : R.string.osservio_http_off;
            summary.setText(getContext().getString(R.string.osservio_http_summary, getContext().getString(state),
                latest == null ? 0 : latest.events.size(), latest == null ? 0L : latest.evictedEvents, latest == null ? 0L : latest.evictedBodies));
        }
    }
    private void render() {
        String query = search.getText().toString().toLowerCase(Locale.ROOT);
        int selected = filter.getSelectedItemPosition();
        events.clear();
        if (latest != null) {
            for (int i = latest.events.size() - 1; i >= 0; i--) {
                HTTPEvent event = latest.events.get(i);
                boolean matchesFilter = selected == 0 || (selected == 1 && HTTPPresentation.hasIssue(event))
                    || (selected == 2 && HTTPPresentation.isActive(event));
                if (matchesFilter && (query.isEmpty() || event.url.toLowerCase(Locale.ROOT).contains(query)
                    || (event.responseURL != null && event.responseURL.toLowerCase(Locale.ROOT).contains(query))
                    || event.method.toLowerCase(Locale.ROOT).contains(query)
                    || (event.statusCode != null && event.statusCode.toString().contains(query)))) events.add(event);
            }
        }
        empty.setText(latest == null || latest.events.isEmpty() ? R.string.osservio_http_empty : R.string.osservio_http_no_matches);
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
    private void showDetail(HTTPEvent event) {
        if (detail != null) return;
        detail = new HTTPDetailDialog(getContext(), event, history, recorder).dialog;
        detail.setOnDismissListener(dialog -> detail = null);
        detail.show();
    }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return events.size(); }
        @Override public HTTPEvent getItem(int position) { return events.get(position); }
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
            HTTPEvent event = getItem(position);
            ((TextView) row.getChildAt(0)).setText(getContext().getString(R.string.osservio_http_title, event.method, event.url));
            ((TextView) row.getChildAt(1)).setText(HTTPPresentation.summary(getContext(), event));
            return row;
        }
    }
}
