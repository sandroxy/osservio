package com.sandrox.osservio.ui;

import android.content.Context;
import android.annotation.SuppressLint;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
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
    private final ImageButton pause;
    private final Button filters;
    private final TextView criteria;
    InspectorFeedHeader(Context context, int searchHint, String text, InspectorWidgets.TextChange changed,
                        Runnable togglePause, Runnable showFilters, Runnable actions) {
        super(context); setOrientation(VERTICAL);
        int inset = InspectorTheme.dp(context, 16); setPadding(inset, InspectorTheme.dp(context, 12), inset, 0);
        LinearLayout searchRow = new LinearLayout(context); searchRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        search = InspectorWidgets.search(context, searchHint, text, changed);
        searchRow.addView(search, new LayoutParams(0, -2, 1));
        filters = InspectorWidgets.button(context, R.string.osservio_ui_filters, showFilters);
        filters.setContentDescription(context.getString(R.string.osservio_ui_filters));
        android.graphics.drawable.Drawable symbol = context.getDrawable(R.drawable.osservio_filter);
        if (symbol != null) symbol.setTint(InspectorTheme.accent(context));
        filters.setCompoundDrawablesRelativeWithIntrinsicBounds(symbol, null, null, null);
        filters.setCompoundDrawablePadding(InspectorTheme.dp(context, 4));
        filters.setMinimumWidth(InspectorTheme.dp(context, 48));
        LayoutParams filterLayout = new LayoutParams(-2, -2); filterLayout.leftMargin = InspectorTheme.dp(context, 8);
        searchRow.addView(filters, filterLayout); addView(searchRow, new LayoutParams(-1, -2));
        LinearLayout bar = new LinearLayout(context); bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setPadding(0, InspectorTheme.dp(context, 8), 0, 0);
        status = InspectorWidgets.caption(context); status.setTextColor(InspectorTheme.accent(context));
        pause = InspectorWidgets.icon(context, R.drawable.osservio_pause, R.string.osservio_ui_pause, togglePause);
        ImageButton menu = InspectorWidgets.icon(context, R.drawable.osservio_more, R.string.osservio_ui_actions, actions);
        bar.addView(status, new LayoutParams(0, -2, 1)); bar.addView(pause);
        LayoutParams menuLayout = new LayoutParams(InspectorTheme.dp(context, 48), InspectorTheme.dp(context, 48));
        menuLayout.leftMargin = InspectorTheme.dp(context, 8); bar.addView(menu, menuLayout);
        addView(bar, new LayoutParams(-1, -2));
        criteria = InspectorWidgets.caption(context); criteria.setMaxLines(3); criteria.setEllipsize(android.text.TextUtils.TruncateAt.END);
        addView(criteria, new LayoutParams(-1, -2));
        results = InspectorWidgets.caption(context); addView(results, new LayoutParams(-1, -2));
        empty = InspectorWidgets.caption(context); empty.setTextSize(15); empty.setGravity(android.view.Gravity.CENTER);
        empty.setPadding(inset, inset * 2, inset, inset * 2); addView(empty, new LayoutParams(-1, -2));
    }
    void setQuery(InspectorQuery query, List<InspectorFilterSheet.Group> groups, int visible, int total) {
        filters.setText(query.activeCount() == 0 ? "" : Integer.toString(query.activeCount()));
        InspectorWidgets.chip(filters, query.activeCount() > 0);
        criteria.setText(InspectorFilterOptions.summary(getContext(), query, groups));
        criteria.setVisibility(query.activeCount() == 0 ? GONE : VISIBLE);
        results.setText(getContext().getString(R.string.osservio_ui_result_count, visible, total));
        empty.setVisibility(visible == 0 ? VISIBLE : GONE);
    }
    void setPaused(boolean paused) {
        pause.setImageResource(paused ? R.drawable.osservio_play : R.drawable.osservio_pause);
        pause.setContentDescription(getContext().getString(paused ? R.string.osservio_ui_follow_latest : R.string.osservio_ui_pause));
    }
}
