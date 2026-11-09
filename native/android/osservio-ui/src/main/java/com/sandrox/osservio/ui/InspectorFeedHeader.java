package com.sandrox.osservio.ui;

import android.content.Context;
import android.annotation.SuppressLint;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/** A scrolling header keeps the reading area reachable in compact and large-text layouts. */
// Callbacks and query state are required; this view is never inflated from XML.
@SuppressLint("ViewConstructor")
final class InspectorFeedHeader extends LinearLayout {
    final TextView status;
    final TextView results;
    final TextView empty;
    final EditText search;
    private final Button pause;
    private final Button filters;
    private final TextView criteria;
    InspectorFeedHeader(Context context, int searchHint, String text, InspectorWidgets.TextChange changed,
                        Runnable togglePause, Runnable showFilters, Runnable actions) {
        super(context); setOrientation(VERTICAL);
        int inset = InspectorTheme.dp(context, 16); setPadding(inset, 0, inset, 0);
        LinearLayout bar = new LinearLayout(context); bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        status = InspectorWidgets.caption(context); status.setTextColor(InspectorTheme.accent(context));
        pause = InspectorWidgets.button(context, R.string.osservio_ui_pause, togglePause);
        Button menu = InspectorWidgets.button(context, R.string.osservio_ui_actions, actions);
        menu.setText("⋯"); menu.setTextSize(22); menu.setContentDescription(context.getString(R.string.osservio_ui_actions));
        bar.addView(status, new LayoutParams(0, -2, 1)); bar.addView(pause, new LayoutParams(-2, -2));
        bar.addView(menu, new LayoutParams(InspectorTheme.dp(context, 48), -2));
        if (context.getResources().getConfiguration().fontScale >= 1.5f) {
            bar.removeView(status); addView(status, new LayoutParams(-1, -2));
        }
        addView(bar, new LayoutParams(-1, -2));
        search = InspectorWidgets.search(context, searchHint, text, changed);
        addView(search, new LayoutParams(-1, -2));
        filters = InspectorWidgets.button(context, R.string.osservio_ui_filters, showFilters);
        LayoutParams filterLayout = new LayoutParams(-2, -2); filterLayout.topMargin = InspectorTheme.dp(context, 10);
        addView(filters, filterLayout);
        criteria = InspectorWidgets.caption(context); criteria.setMaxLines(3); criteria.setEllipsize(android.text.TextUtils.TruncateAt.END);
        addView(criteria, new LayoutParams(-1, -2));
        results = InspectorWidgets.caption(context); addView(results, new LayoutParams(-1, -2));
        empty = InspectorWidgets.caption(context); empty.setTextSize(15); empty.setGravity(android.view.Gravity.CENTER);
        empty.setPadding(inset, inset * 2, inset, inset * 2); addView(empty, new LayoutParams(-1, -2));
    }
    void setQuery(InspectorQuery query, List<InspectorFilterSheet.Group> groups, int visible, int total) {
        String title = getContext().getString(R.string.osservio_ui_filters);
        filters.setText(query.activeCount() == 0 ? title : title + " · " + query.activeCount());
        InspectorWidgets.chip(filters, query.activeCount() > 0);
        criteria.setText(InspectorFilterOptions.summary(getContext(), query, groups));
        criteria.setVisibility(query.activeCount() == 0 ? GONE : VISIBLE);
        results.setText(getContext().getString(R.string.osservio_ui_result_count, visible, total));
        empty.setVisibility(visible == 0 ? VISIBLE : GONE);
    }
    void setPaused(boolean paused) {
        pause.setText(paused ? R.string.osservio_ui_follow_latest : R.string.osservio_ui_pause);
        pause.setContentDescription(getContext().getString(paused ? R.string.osservio_ui_follow_latest : R.string.osservio_ui_pause));
    }
}
