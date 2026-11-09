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
import com.sandrox.osservio.core.BreadcrumbEvent;
import com.sandrox.osservio.core.BreadcrumbHistory;
import com.sandrox.osservio.core.BreadcrumbRecorder;
import com.sandrox.osservio.core.BreadcrumbSnapshot;
import com.sandrox.osservio.core.CaptureController;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Capture and histories are supplied by the host; this view is never inflated from XML.
@SuppressLint("ViewConstructor")
final class BreadcrumbInspectorView extends LinearLayout {
    private final BreadcrumbRecorder recorder;
    private final BreadcrumbHistory history;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final InspectorFeedHeader header;
    private final ListView list;
    private final List<BreadcrumbEvent> events = new ArrayList<>();
    private final Rows rows = new Rows();
    private InspectorQuery query = new InspectorQuery();
    private BreadcrumbSnapshot latest;
    private long timeAnchor;
    private boolean paused;
    private boolean attached;
    private boolean dirty = true;
    private InspectorScreen detail;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!attached || !isShown() || getWindowVisibility() != VISIBLE || detail != null) return;
            refreshView(); handler.postDelayed(this, 500);
        }
    };
    BreadcrumbInspectorView(Context host, BreadcrumbRecorder recorder, BreadcrumbHistory history) {
        super(InspectorTheme.context(host)); Context context = getContext(); this.recorder = recorder; this.history = history;
        setOrientation(VERTICAL); setBackgroundColor(InspectorTheme.canvas(context));
        header = new InspectorFeedHeader(context, R.string.osservio_crumb_search, "", value -> { query.text = value; dirty = true; refreshView(); },
            () -> setPaused(!paused), this::filters, this::actions);
        list = new ListView(context); list.addHeaderView(header, null, false); list.setItemsCanFocus(true); list.setAdapter(rows);
        list.setDivider(new android.graphics.drawable.ColorDrawable(InspectorTheme.color(context, R.color.osservio_separator)));
        list.setDividerHeight(InspectorTheme.dp(context, 1));
        list.setOnItemClickListener((parent, view, position, id) -> {
            int index = position - list.getHeaderViewsCount(); if (index >= 0 && index < events.size()) showDetail(events.get(index));
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) { if (state == SCROLL_STATE_TOUCH_SCROLL) setPaused(true); }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) { }
        });
        addView(list, new LayoutParams(-1, -1));
    }
    private List<InspectorFilterSheet.Group> groups() {
        InspectorFilterSheet.Group kinds = InspectorFilterOptions.group(getContext(), InspectorQuery.Field.KIND, R.string.osservio_ui_kind);
        for (BreadcrumbEvent.Kind kind : BreadcrumbEvent.Kind.values()) kinds.option(kind.name(), RuntimePresentation.kind(getContext(), kind));
        return Collections.singletonList(kinds);
    }
    private void filters() {
        detail = InspectorFilterSheet.show(getContext(), query, groups(), value -> { query = value; dirty = true; refreshView(); }); watchDialog();
    }
    private void actions() {
        CaptureController.Status status = recorder.captureController.status();
        if (status == null) { busy(); return; }
        String[] labels = {getContext().getString(status.baselineEnabled ? R.string.osservio_crumb_disable_baseline : R.string.osservio_crumb_enable_baseline),
            getContext().getString(R.string.osservio_ui_capture_details), getContext().getString(R.string.osservio_crumb_clear)};
        detail = InspectorDialogs.menu(getContext(), R.string.osservio_ui_actions, labels, (dialog, which) -> {
            dialog.dismiss(); detail = null;
            if (which == 0) {
                CaptureController.Status current = recorder.captureController.status();
                if (current == null || !recorder.captureController.setBaselineEnabled(!current.baselineEnabled)) busy();
                refreshView();
            } else if (which == 1) {
                BreadcrumbRecorder.Status recorded = recorder.status();
                String message = recorded == null ? getContext().getString(R.string.osservio_busy)
                    : getContext().getString(R.string.osservio_crumb_summary, captureLabel(), latest == null ? 0 : latest.events.size(),
                        latest == null ? 0L : latest.evictedCount, recorded.rateLimitedCount);
                detail = InspectorDialogs.message(getContext(), R.string.osservio_ui_capture_details, message + "\n\n" + getContext().getString(R.string.osservio_crumb_baseline_hint)); watchDialog();
            } else {
                detail = InspectorDialogs.confirm(getContext(), R.string.osservio_crumb_clear, getContext().getString(R.string.osservio_ui_clear_hint), R.string.osservio_crumb_clear, (ignored, value) -> {
                        history.clear(); latest = history.snapshot(); dirty = true; refreshView();
                    }); watchDialog();
            }
        }); watchDialog();
    }
    private void busy() { Toast.makeText(getContext(), R.string.osservio_busy, Toast.LENGTH_SHORT).show(); }
    private String captureLabel() {
        CaptureController.Status status = recorder.captureController.status();
        return getContext().getString(status == null ? R.string.osservio_busy : status.mode == CaptureController.Mode.DETAILED
            ? R.string.osservio_ui_detailed : status.mode == CaptureController.Mode.BASELINE ? R.string.osservio_ui_baseline : R.string.osservio_ui_not_capturing);
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attached = true; schedule(); }
    @Override protected void onDetachedFromWindow() { attached = false; handler.removeCallbacks(refresh); if (!InspectorNavigation.owns(getContext()) && detail != null) detail.dismiss(); super.onDetachedFromWindow(); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); schedule(); }
    @Override protected void onVisibilityChanged(View view, int visibility) { super.onVisibilityChanged(view, visibility); schedule(); }
    private void schedule() {
        if (handler == null || refresh == null) return;
        handler.removeCallbacks(refresh);
        if (attached && isShown() && getWindowVisibility() == VISIBLE && detail == null) handler.post(refresh);
        else if (!InspectorNavigation.owns(getContext()) && (!attached || !isShown() || getWindowVisibility() != VISIBLE) && detail != null) detail.dismiss();
    }
    private void refreshView() {
        if (list == null) return;
        if (!paused) {
            BreadcrumbSnapshot snapshot = history.snapshot(); timeAnchor = System.currentTimeMillis();
            if (snapshot != null) { dirty |= latest == null || latest.revision != snapshot.revision || query.recentMinutes > 0; latest = snapshot; }
        }
        if (dirty) {
            events.clear();
            if (latest != null) for (int i = latest.events.size() - 1; i >= 0; i--) {
                BreadcrumbEvent event = latest.events.get(i);
                if (query.allows(InspectorQuery.Field.KIND, event.kind.name()) && query.includesTime(event.timestampMillis, timeAnchor)
                    && query.contains(event.label, RuntimePresentation.kind(getContext(), event.kind), event.message)) events.add(event);
            }
            header.setQuery(query, groups(), events.size(), latest == null ? 0 : latest.events.size());
            header.empty.setText(latest == null ? R.string.osservio_busy : latest.events.isEmpty() ? R.string.osservio_crumb_empty : R.string.osservio_ui_filtered_empty);
            rows.notifyDataSetChanged(); dirty = false;
        }
        header.status.setText(paused ? getContext().getString(R.string.osservio_ui_paused) : captureLabel()); header.setPaused(paused);
    }
    private void setPaused(boolean value) { paused = value; refreshView(); if (!value) list.setSelection(0); }
    private void showDetail(BreadcrumbEvent event) {
        if (detail != null) return;
        InspectorContentReader reader = new InspectorContentReader(getContext());
        reader.display(getContext().getString(R.string.osservio_runtime_notice, getContext().getString(R.string.osservio_crumb_detail_hint),
            RuntimePresentation.breadcrumb(getContext(), event)), false);
        detail = InspectorDialogs.screen(getContext(), RuntimePresentation.kind(getContext(), event.kind), reader); watchDialog(); detail.show();
    }
    private void watchDialog() {
        handler.removeCallbacks(refresh); InspectorScreen watched = detail;
        watched.setOnDismissListener(ignored -> { if (detail == watched) { detail = null; schedule(); } });
    }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return events.size(); }
        @Override public BreadcrumbEvent getItem(int position) { return events.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            InspectorWidgets.EventRow row = recycled instanceof InspectorWidgets.EventRow ? (InspectorWidgets.EventRow) recycled : new InspectorWidgets.EventRow(getContext());
            BreadcrumbEvent event = getItem(position);
            row.bind(RuntimePresentation.kind(getContext(), event.kind), event.label, event.message == null ? "#" + event.sequence : event.message,
                event.timestampMillis, InspectorTheme.accent(getContext())); return row;
        }
    }
}
