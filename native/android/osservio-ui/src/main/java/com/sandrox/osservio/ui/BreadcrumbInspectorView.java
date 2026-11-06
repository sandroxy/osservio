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
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.sandrox.osservio.core.BreadcrumbEvent;
import com.sandrox.osservio.core.BreadcrumbHistory;
import com.sandrox.osservio.core.BreadcrumbRecorder;
import com.sandrox.osservio.core.BreadcrumbSnapshot;
import com.sandrox.osservio.core.CaptureController;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Capture and history are host-owned dependencies, supplied through programmatic construction.
@SuppressLint("ViewConstructor")
final class BreadcrumbInspectorView extends LinearLayout {
    private final BreadcrumbRecorder recorder;
    private final BreadcrumbHistory history;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView summary;
    private final TextView empty;
    private final EditText search;
    private final Button baseline;
    private final Button pause;
    private final ListView list;
    private final List<BreadcrumbEvent> events = new ArrayList<>();
    private final Rows rows = new Rows();
    private BreadcrumbSnapshot latest;
    private boolean paused;
    private boolean attached;
    private boolean dirty = true;
    private AlertDialog detail;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!attached || !isShown() || getWindowVisibility() != VISIBLE || detail != null) return;
            refreshView(); handler.postDelayed(this, 250L);
        }
    };
    BreadcrumbInspectorView(Context context, BreadcrumbRecorder recorder, BreadcrumbHistory history) {
        super(context); this.recorder = recorder; this.history = history;
        setOrientation(VERTICAL); setPadding(dp(16), dp(8), dp(16), 0);
        LinearLayout header = new LinearLayout(context); header.setOrientation(VERTICAL);
        summary = new TextView(context); summary.setTextAppearance(android.R.style.TextAppearance_Material_Caption);
        header.addView(summary, new LayoutParams(-1, -2));
        baseline = button(R.string.osservio_crumb_enable_baseline); baseline.setOnClickListener(view -> toggleBaseline());
        pause = button(R.string.osservio_pause); pause.setOnClickListener(view -> { setPaused(!paused); refreshView(); });
        Button clear = button(R.string.osservio_crumb_clear);
        clear.setOnClickListener(view -> { history.clear(); latest = history.snapshot(); dirty = true; refreshView(); });
        header.addView(new InspectorControls(context, baseline, pause, clear), new LayoutParams(-1, -2));
        TextView explanation = new TextView(context); explanation.setTextAppearance(android.R.style.TextAppearance_Material_Caption);
        explanation.setText(R.string.osservio_crumb_baseline_hint); header.addView(explanation, new LayoutParams(-1, -2));
        search = new EditText(context); search.setSingleLine(true); search.setMinHeight(dp(48)); search.setHint(R.string.osservio_crumb_search);
        header.addView(search, new LayoutParams(-1, -2));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { dirty = true; refreshView(); }
            @Override public void afterTextChanged(Editable text) { }
        });
        empty = new TextView(context); empty.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        empty.setPadding(0, dp(24), 0, dp(24)); header.addView(empty, new LayoutParams(-1, -2));
        list = new ListView(context);
        list.addHeaderView(header, null, false); list.setItemsCanFocus(true); list.setAdapter(rows);
        list.setOnItemClickListener((parent, view, position, id) -> {
            int index = position - list.getHeaderViewsCount();
            if (index >= 0 && index < events.size()) showDetail(events.get(index));
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) { if (state == SCROLL_STATE_TOUCH_SCROLL) setPaused(true); }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) { }
        });
        addView(list, new LayoutParams(-1, 0, 1));
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attached = true; schedule(); }
    @Override protected void onDetachedFromWindow() {
        attached = false; handler.removeCallbacks(refresh); if (detail != null) detail.dismiss(); super.onDetachedFromWindow();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); schedule(); }
    @Override protected void onVisibilityChanged(View view, int visibility) { super.onVisibilityChanged(view, visibility); schedule(); }
    private void schedule() {
        if (handler == null || refresh == null) return;
        handler.removeCallbacks(refresh);
        if (attached && isShown() && getWindowVisibility() == VISIBLE) handler.post(refresh);
        else if (detail != null) detail.dismiss();
    }
    private void refreshView() {
        if (list == null) return;
        if (!paused) {
            BreadcrumbSnapshot snapshot = history.snapshot();
            if (snapshot != null) { if (latest == null || latest.revision != snapshot.revision) dirty = true; latest = snapshot; }
        }
        if (dirty) {
            String query = search.getText().toString().toLowerCase(Locale.ROOT);
            events.clear();
            if (latest != null) for (int i = latest.events.size() - 1; i >= 0; i--) {
                BreadcrumbEvent event = latest.events.get(i);
                if (query.isEmpty() || event.label.toLowerCase(Locale.ROOT).contains(query)
                    || RuntimePresentation.kind(getContext(), event.kind).toLowerCase(Locale.ROOT).contains(query)
                    || (event.message != null && event.message.toLowerCase(Locale.ROOT).contains(query))) events.add(event);
            }
            empty.setText(latest == null ? R.string.osservio_busy : latest.events.isEmpty() ? R.string.osservio_crumb_empty : R.string.osservio_ws_no_matches);
            empty.setVisibility(events.isEmpty() ? VISIBLE : GONE);
            rows.notifyDataSetChanged(); dirty = false;
        }
        CaptureController.Status status = recorder.captureController.status();
        BreadcrumbRecorder.Status recording = recorder.status();
        if (status != null && recording != null) {
            baseline.setText(status.baselineEnabled ? R.string.osservio_crumb_disable_baseline : R.string.osservio_crumb_enable_baseline);
            int mode = status.mode == CaptureController.Mode.DETAILED ? R.string.osservio_http_detailed
                : status.mode == CaptureController.Mode.BASELINE ? R.string.osservio_http_baseline : R.string.osservio_http_off;
            summary.setText(getContext().getString(R.string.osservio_crumb_summary, getContext().getString(mode),
                latest == null ? 0 : latest.events.size(), latest == null ? 0 : latest.evictedCount, recording.rateLimitedCount));
        }
    }
    private void toggleBaseline() {
        CaptureController.Status status = recorder.captureController.status();
        if (status == null || !recorder.captureController.setBaselineEnabled(!status.baselineEnabled)) {
            Toast.makeText(getContext(), R.string.osservio_busy, Toast.LENGTH_SHORT).show();
        }
        refreshView();
    }
    private void setPaused(boolean value) { paused = value; pause.setText(value ? R.string.osservio_resume : R.string.osservio_pause); }
    private Button button(int title) {
        Button button = new Button(getContext()); button.setText(title); button.setAllCaps(false); button.setMinHeight(dp(48)); return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void showDetail(BreadcrumbEvent event) {
        if (detail != null) return;
        String content = RuntimePresentation.breadcrumb(getContext(), event);
        TextView text = new TextView(getContext()); text.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        text.setTextIsSelectable(true); text.setPadding(dp(16), dp(16), dp(16), dp(16));
        text.setText(getContext().getString(R.string.osservio_runtime_notice, getContext().getString(R.string.osservio_crumb_detail_hint), content));
        ScrollView scroll = new ScrollView(getContext()); scroll.addView(text);
        detail = new AlertDialog.Builder(getContext()).setTitle(RuntimePresentation.kind(getContext(), event.kind))
            .setView(scroll).setNegativeButton(R.string.osservio_close, null).setPositiveButton(R.string.osservio_crumb_copy, null).create();
        detail.setOnShowListener(ignored -> detail.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            ClipboardManager clipboard = getContext().getSystemService(ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Osservio", content));
        }));
        handler.removeCallbacks(refresh);
        detail.setOnDismissListener(ignored -> { detail = null; schedule(); }); detail.show();
    }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return events.size(); }
        @Override public BreadcrumbEvent getItem(int position) { return events.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row;
            if (recycled instanceof LinearLayout) row = (LinearLayout) recycled;
            else {
                row = new LinearLayout(getContext()); row.setOrientation(VERTICAL); row.setPadding(dp(4), dp(12), dp(4), dp(12));
                TextView title = new TextView(getContext()); title.setTextAppearance(android.R.style.TextAppearance_Material_Body1); title.setMaxLines(2);
                TextView subtitle = new TextView(getContext()); subtitle.setTextAppearance(android.R.style.TextAppearance_Material_Caption); subtitle.setMaxLines(3);
                row.addView(title, new LayoutParams(-1, -2)); row.addView(subtitle, new LayoutParams(-1, -2));
            }
            BreadcrumbEvent event = getItem(position);
            ((TextView) row.getChildAt(0)).setText(getContext().getString(R.string.osservio_runtime_pair, RuntimePresentation.kind(getContext(), event.kind), event.label));
            ((TextView) row.getChildAt(1)).setText(getContext().getString(R.string.osservio_crumb_subtitle, RuntimePresentation.date(event.timestampMillis),
                event.sequence, event.message == null ? "" : "\n" + event.message));
            return row;
        }
    }
}
