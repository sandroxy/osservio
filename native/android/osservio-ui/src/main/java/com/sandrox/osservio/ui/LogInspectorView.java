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
import com.sandrox.osservio.core.LogEvent;
import com.sandrox.osservio.core.LogHistory;
import com.sandrox.osservio.core.LogLevel;
import com.sandrox.osservio.core.LogRecorder;
import com.sandrox.osservio.core.LogSnapshot;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Main-thread view. Attaching, hiding, or pausing it never changes capture state. */
// This view requires explicit runtime dependencies and is not an XML-inflatable widget.
@SuppressLint("ViewConstructor")
public final class LogInspectorView extends LinearLayout {
    private final LogRecorder recorder;
    private final LogHistory history;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView summary;
    private final TextView empty;
    private final EditText search;
    private final Spinner levels;
    private final Button capture;
    private final Button pause;
    private final ListView list;
    private final Rows rows = new Rows();
    private final List<LogEvent> visibleEvents = new ArrayList<>();
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
    private LogSnapshot latest;
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

    public LogInspectorView(Context context, LogRecorder recorder, LogHistory history) {
        super(context);
        this.recorder = recorder;
        this.history = history;
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(8), dp(16), 0);
        summary = new TextView(context);
        summary.setTextAppearance(android.R.style.TextAppearance_Material_Caption);
        addView(summary, new LayoutParams(-1, -2));
        LinearLayout controls = new LinearLayout(context);
        capture = button(R.string.osservio_start);
        capture.setOnClickListener(view -> toggleCapture());
        pause = button(R.string.osservio_pause);
        pause.setOnClickListener(view -> { setPaused(!paused); refreshView(); });
        Button clear = button(R.string.osservio_clear);
        clear.setOnClickListener(view -> {
            history.clear(); latest = history.snapshot(); dirty = true; refreshView();
        });
        for (Button button : new Button[] {capture, pause, clear}) {
            controls.addView(button, new LayoutParams(0, -2, 1));
        }
        addView(controls, new LayoutParams(-1, -2));
        search = new EditText(context);
        search.setSingleLine(true);
        search.setHint(R.string.osservio_search);
        search.setMinHeight(dp(48));
        addView(search, new LayoutParams(-1, -2));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { dirty = true; refreshView(); }
            @Override public void afterTextChanged(Editable value) { }
        });
        levels = new Spinner(context);
        ArrayAdapter<String> options = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item,
            new String[] {context.getString(R.string.osservio_all), "DEBUG", "INFO", "WARNING", "ERROR"});
        options.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        levels.setAdapter(options);
        levels.setMinimumHeight(dp(48));
        levels.setContentDescription(context.getString(R.string.osservio_level));
        addView(levels, new LayoutParams(-1, -2));
        levels.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { dirty = true; refreshView(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        empty = new TextView(context);
        empty.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        empty.setPadding(0, dp(24), 0, dp(24));
        addView(empty, new LayoutParams(-1, -2));
        list = new ListView(context);
        list.setAdapter(rows);
        list.setEmptyView(empty);
        list.setOnItemClickListener((parent, view, position, id) -> showDetail(visibleEvents.get(position)));
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
        attached = false;
        handler.removeCallbacks(refresh);
        if (detail != null) { detail.dismiss(); detail = null; }
        super.onDetachedFromWindow();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); schedule(); }
    @Override protected void onVisibilityChanged(View view, int visibility) { super.onVisibilityChanged(view, visibility); schedule(); }
    private void schedule() {
        // View construction can dispatch visibility changes before field initialization.
        if (handler == null || refresh == null) return;
        handler.removeCallbacks(refresh);
        if (attached && isShown() && getWindowVisibility() == VISIBLE) handler.post(refresh);
        else if (detail != null) { detail.dismiss(); detail = null; }
    }

    private void refreshView() {
        if (list == null || levels == null) return;
        if (!paused) {
            LogSnapshot snapshot = history.snapshot();
            if (snapshot != null) {
                if (latest == null || snapshot.revision != latest.revision) dirty = true;
                latest = snapshot;
            }
        }
        if (dirty) render();
        LogRecorder.Status status = recorder.status();
        if (status != null) {
            capture.setText(status.isRecording ? R.string.osservio_stop : R.string.osservio_start);
            summary.setText(getContext().getString(R.string.osservio_summary,
                getContext().getString(status.isRecording ? R.string.osservio_capturing : R.string.osservio_stopped),
                latest == null ? 0 : latest.events.size(), latest == null ? 0L : latest.evictedCount,
                status.rateLimitedCount));
        }
    }

    private void render() {
        String query = search.getText().toString().toLowerCase(Locale.ROOT);
        int selected = levels.getSelectedItemPosition();
        LogLevel level = selected > 0 ? LogLevel.values()[selected - 1] : null;
        visibleEvents.clear();
        if (latest != null) {
            for (int i = latest.events.size() - 1; i >= 0; i--) {
                LogEvent event = latest.events.get(i);
                if ((level == null || event.level == level) && (query.isEmpty()
                    || event.message.toLowerCase(Locale.ROOT).contains(query)
                    || event.category.toLowerCase(Locale.ROOT).contains(query))) visibleEvents.add(event);
            }
        }
        empty.setText(latest == null || latest.events.isEmpty() ? R.string.osservio_empty : R.string.osservio_no_matches);
        rows.notifyDataSetChanged();
        dirty = false;
    }

    private void toggleCapture() {
        LogRecorder.Status status = recorder.status();
        if (status == null) { busy(); return; }
        if (status.isRecording) { if (!recorder.stop()) busy(); }
        else { try { recorder.start(); } catch (IllegalStateException failure) { busy(); } }
        refreshView();
    }
    private void setPaused(boolean value) {
        paused = value;
        pause.setText(value ? R.string.osservio_resume : R.string.osservio_pause);
    }
    private void busy() { Toast.makeText(getContext(), R.string.osservio_busy, Toast.LENGTH_SHORT).show(); }
    private Button button(int title) {
        Button result = new Button(getContext());
        result.setText(title);
        result.setAllCaps(false);
        result.setMinHeight(dp(48));
        return result;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void showDetail(LogEvent event) {
        if (detail != null) return;
        detail = LogDetailDialog.create(getContext(), event);
        detail.setOnDismissListener(dialog -> detail = null);
        detail.show();
    }

    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return visibleEvents.size(); }
        @Override public LogEvent getItem(int position) { return visibleEvents.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row;
            if (recycled instanceof LinearLayout) row = (LinearLayout) recycled;
            else {
                row = new LinearLayout(getContext());
                row.setOrientation(VERTICAL);
                row.setPadding(dp(4), dp(12), dp(4), dp(12));
                TextView message = new TextView(getContext());
                message.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
                message.setMaxLines(2);
                row.addView(message, new LayoutParams(-1, -2));
                TextView metadata = new TextView(getContext());
                metadata.setTextAppearance(android.R.style.TextAppearance_Material_Caption);
                row.addView(metadata, new LayoutParams(-1, -2));
            }
            LogEvent event = getItem(position);
            ((TextView) row.getChildAt(0)).setText(event.message);
            String marker = event.isTruncated ? " · " + getContext().getString(R.string.osservio_truncated) : "";
            ((TextView) row.getChildAt(1)).setText(getContext().getString(R.string.osservio_metadata,
                time.format(new Date(event.timestampMillis)), event.level.name(), event.category, marker));
            return row;
        }
    }
}
