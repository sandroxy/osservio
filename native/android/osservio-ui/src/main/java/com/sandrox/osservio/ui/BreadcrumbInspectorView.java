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
import com.sandrox.osservio.core.BreadcrumbEvent;
import com.sandrox.osservio.core.BreadcrumbHistory;
import com.sandrox.osservio.core.BreadcrumbRecorder;
import com.sandrox.osservio.core.BreadcrumbSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Capture and histories are supplied by the host; this view is never inflated from XML.
@SuppressLint("ViewConstructor")
final class BreadcrumbInspectorView extends LinearLayout implements InspectorScreen.Content {
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
    private boolean stale;
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
            () -> setPaused(false), this::filters, this::actions, true, R.string.osservio_crumb_clear);
        header.onQueryChange = value -> { query = value; dirty = true; refreshView(); };
        list = new ListView(context); list.addHeaderView(header, null, false); list.setItemsCanFocus(true); list.setAdapter(rows);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setOnItemClickListener((parent, view, position, id) -> {
            int index = position - list.getHeaderViewsCount(); if (index >= 0 && index < events.size()) showDetail(events.get(index));
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {
                if (state == SCROLL_STATE_TOUCH_SCROLL) setPaused(true);
                else if (state == SCROLL_STATE_IDLE && list.getFirstVisiblePosition() == 0 && list.getChildAt(0) != null && list.getChildAt(0).getTop() >= 0) setPaused(false);
            }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) { }
        });
        addView(header.viewport(list), new LayoutParams(-1, -1));
        InspectorAppearance.style(this, () -> setBackgroundColor(InspectorTheme.canvas(context)));
        InspectorAppearance.content(this, () -> {
            header.search.setHint(R.string.osservio_crumb_search);
            list.setDivider(null);
            dirty = true; if (detail == null) refreshView();
        });
    }
    private List<InspectorFilterSheet.Group> groups() {
        InspectorFilterSheet.Group kinds = InspectorFilterOptions.group(getContext(), InspectorQuery.Field.KIND, R.string.osservio_ui_kind);
        if (latest != null) for (BreadcrumbEvent event : latest.events) kinds.option(event.kind.name(), RuntimePresentation.kind(getContext(), event.kind));
        return Collections.singletonList(kinds);
    }
    private void filters() {
        detail = InspectorFilterSheet.show(getContext(), query, groups(), value -> { query = value; dirty = true; refreshView(); }); watchDialog();
    }
    private void actions() {
        detail = InspectorDialogs.confirm(getContext(), R.string.osservio_crumb_clear, getContext().getString(R.string.osservio_ui_clear_hint), R.string.osservio_crumb_clear, (ignored, value) -> {
            history.clear(); latest = history.snapshot(); dirty = true; refreshView();
        }); watchDialog();
    }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        InspectorKeyboardDismissal.outside(this, event); return super.dispatchTouchEvent(event);
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
            BreadcrumbSnapshot snapshot = history.snapshot(); timeAnchor = System.currentTimeMillis(); stale = snapshot == null;
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
        String notice = stale ? getContext().getString(latest == null ? R.string.osservio_busy : R.string.osservio_ui_previous_window) : "";
        BreadcrumbRecorder.Status status = recorder.status();
        if (latest != null && latest.evictedCount > 0 || status != null && status.rateLimitedCount > 0)
            notice += (notice.isEmpty() ? "" : " · ") + getContext().getString(R.string.osservio_ui_retention_loss);
        header.status.setText(notice); header.setPaused(paused);
    }
    private void setPaused(boolean value) { paused = value; refreshView(); if (!value) list.setSelectionFromTop(0, 0); }
    private void showDetail(BreadcrumbEvent event) {
        if (detail != null) return;
        detail = BreadcrumbDetailDialog.create(getContext(), event); watchDialog(); detail.show();
    }
    private void watchDialog() {
        handler.removeCallbacks(refresh); InspectorScreen watched = detail;
        watched.setOnDismissListener(ignored -> { if (detail == watched) { detail = null; schedule(); } });
    }
    @Override public void bindScreen(InspectorScreen screen) { screen.addAction(header.navigationAction()); }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return events.size(); }
        @Override public BreadcrumbEvent getItem(int position) { return events.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            InspectorWidgets.EventRow row = recycled instanceof InspectorWidgets.EventRow ? (InspectorWidgets.EventRow) recycled : new InspectorWidgets.EventRow(getContext());
            BreadcrumbEvent event = getItem(position);
            row.bind(RuntimePresentation.kind(getContext(), event.kind), event.label, event.message == null ? "#" + event.sequence : event.message,
                event.timestampMillis, InspectorTheme.accent(getContext())); row.grouped(position == 0, position == getCount() - 1); return row;
        }
    }
}
