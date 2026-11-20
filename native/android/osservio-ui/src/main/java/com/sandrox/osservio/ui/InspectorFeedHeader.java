package com.sandrox.osservio.ui;

import android.content.Context;
import android.view.View;
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
    interface Groups { List<InspectorFilterSheet.Group> get(); }
    final TextView status;
    final TextView results;
    final TextView empty;
    final EditText search;
    private final ImageButton pause;
    private final ImageButton filters;
    private final TextView criteria;
    final ImageButton actions;
    private final boolean compact;
    private final Button latest;
    private final android.widget.ProgressBar activity;
    private final android.widget.HorizontalScrollView tokenScroll;
    private final LinearLayout tokens;
    private InspectorQuery displayedQuery;
    private String displayedStyle;
    private final InspectorFilterSheet.Edit editFilter;
    private final Button undo;
    private InspectorQuery beforeRemoval, afterRemoval;
    private final Runnable expireUndo = this::finishUndo;
    InspectorFilterSheet.Applied onQueryChange;
    InspectorFeedHeader(Context context, int searchHint, String text, InspectorWidgets.TextChange changed,
                        Runnable togglePause, InspectorFilterSheet.Edit showFilters, Runnable actions) {
        this(context, searchHint, text, changed, togglePause, showFilters, actions, false);
    }
    InspectorFeedHeader(Context context, int searchHint, String text, InspectorWidgets.TextChange changed,
                        Runnable togglePause, InspectorFilterSheet.Edit showFilters, Runnable actions, boolean compact) {
        this(context, searchHint, text, changed, togglePause, showFilters, actions, compact, R.string.osservio_clear);
    }
    InspectorFeedHeader(Context context, int searchHint, String text, InspectorWidgets.TextChange changed,
                        Runnable togglePause, InspectorFilterSheet.Edit showFilters, Runnable actions, boolean compact, int actionLabel) {
        super(context); this.compact = compact; this.editFilter = showFilters; setOrientation(VERTICAL);
        int inset = InspectorTheme.dp(context, 16); setPadding(inset, InspectorTheme.dp(context, 12), inset, 0);
        LinearLayout searchRow = new LinearLayout(context); searchRow.setGravity(android.view.Gravity.CENTER_VERTICAL); searchRow.setBaselineAligned(false);
        search = InspectorWidgets.search(context, searchHint, text, changed);
        searchRow.addView(search, new LayoutParams(0, -2, 1));
        filters = InspectorWidgets.icon(context, R.drawable.osservio_filter, R.string.osservio_ui_filters, () -> editFilter.open(null));
        LayoutParams filterLayout = new LayoutParams(InspectorTheme.dp(context, 48), InspectorTheme.dp(context, 48)); filterLayout.leftMargin = InspectorTheme.dp(context, 8);
        searchRow.addView(filters, filterLayout); addView(searchRow, new LayoutParams(-1, -2));
        LinearLayout bar = new LinearLayout(context); bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setBaselineAligned(false); bar.setPadding(0, InspectorTheme.dp(context, 8), 0, InspectorTheme.dp(context, 12));
        status = InspectorWidgets.caption(context); InspectorAppearance.style(status, () -> status.setTextColor(InspectorTheme.accent(context)));
        pause = InspectorWidgets.icon(context, R.drawable.osservio_pause, R.string.osservio_ui_pause, togglePause);
        ImageButton menu = InspectorWidgets.icon(context, compact ? R.drawable.osservio_trash : R.drawable.osservio_more,
            compact ? actionLabel : R.string.osservio_ui_actions, actions);
        this.actions = menu;
        latest = InspectorWidgets.button(context, R.string.osservio_ui_follow_latest, togglePause);
        latest.setVisibility(GONE); latest.setGravity(android.view.Gravity.CENTER);
        InspectorAppearance.text(latest, () -> getContext().getString(R.string.osservio_ui_follow_latest));
        InspectorAppearance.style(latest, () -> {
            latest.setTextColor(InspectorTheme.accent(context)); latest.setBackgroundTintList(null);
            InspectorTheme.surface(latest, true, 1000);
            latest.setCompoundDrawablesRelative(InspectorWidgets.symbol(context, R.drawable.osservio_arrow_up, 18, InspectorTheme.accent(context)), null, null, null);
        });
        latest.setCompoundDrawablePadding(InspectorTheme.dp(context, 6)); latest.setElevation(InspectorTheme.dp(context, 4));
        latest.setPadding(inset, InspectorTheme.dp(context, 10), inset, InspectorTheme.dp(context, 10));
        results = InspectorWidgets.caption(context);
        activity = new android.widget.ProgressBar(context); activity.setVisibility(INVISIBLE);
        activity.setContentDescription(context.getString(R.string.osservio_archive_reading));
        InspectorAppearance.style(activity, () -> activity.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(InspectorTheme.accent(context))));
        LinearLayout summary = new LinearLayout(context); summary.setOrientation(VERTICAL);
        undo = InspectorWidgets.button(context, R.string.osservio_ui_undo_remove, () -> {
            InspectorQuery original = beforeRemoval;
            boolean current = afterRemoval != null && afterRemoval.sameAs(displayedQuery);
            finishUndo();
            if (current && onQueryChange != null) onQueryChange.apply(original);
        });
        undo.setVisibility(GONE);
        if (compact) {
            LinearLayout countRow = new LinearLayout(context); countRow.setGravity(android.view.Gravity.CENTER_VERTICAL); countRow.setBaselineAligned(false);
            countRow.addView(results, new LayoutParams(0, -2, 1));
            LayoutParams progress = new LayoutParams(InspectorTheme.dp(context, 20), InspectorTheme.dp(context, 20)); progress.leftMargin = InspectorTheme.dp(context, 8);
            countRow.addView(activity, progress); countRow.addView(undo); summary.addView(countRow);
            results.setPadding(0, 0, 0, 0); status.setPadding(0, 0, 0, 0);
        }
        summary.addView(status);
        bar.addView(summary, new LayoutParams(0, -2, 1)); bar.addView(pause);
        LayoutParams menuLayout = new LayoutParams(InspectorTheme.dp(context, 48), InspectorTheme.dp(context, 48));
        menuLayout.leftMargin = InspectorTheme.dp(context, 8); bar.addView(menu, menuLayout);
        tokens = new LinearLayout(context); tokenScroll = new android.widget.HorizontalScrollView(context);
        tokenScroll.setHorizontalScrollBarEnabled(false); tokenScroll.addView(tokens);
        if (compact) addView(tokenScroll, new LayoutParams(-1, -2));
        addView(bar, new LayoutParams(-1, -2));
        criteria = InspectorWidgets.caption(context); criteria.setMaxLines(3); criteria.setEllipsize(android.text.TextUtils.TruncateAt.END);
        addView(criteria, new LayoutParams(-1, -2));
        if (!compact) addView(results, new LayoutParams(-1, -2));
        empty = InspectorWidgets.caption(context); empty.setTextSize(15); empty.setGravity(android.view.Gravity.CENTER);
        empty.setPadding(inset, inset * 2, inset, inset * 2); addView(empty, new LayoutParams(-1, -2));
    }
    void setQuery(InspectorQuery query, List<InspectorFilterSheet.Group> groups, int visible, int total) {
        setQuery(query, () -> groups, visible, total);
    }
    void setQuery(InspectorQuery query, Groups groups, int visible, int total) {
        String style = getContext().getString(R.string.osservio_ui_filters) + InspectorTheme.accent(getContext())
            + getResources().getConfiguration().fontScale;
        if (!query.sameAs(displayedQuery) || !style.equals(displayedStyle)) {
            if (afterRemoval != null && !query.sameAs(afterRemoval)) finishUndo();
            displayedQuery = query.copy(); displayedStyle = style;
            filters.setContentDescription(getContext().getString(R.string.osservio_ui_filters));
            if (android.os.Build.VERSION.SDK_INT >= 30) filters.setStateDescription(getContext().getString(R.string.osservio_ui_filter_count, query.activeCount()));
            filters.setSelected(query.activeCount() > 0);
            List<InspectorFilterSheet.Group> choices = query.activeCount() == 0 ? java.util.Collections.emptyList() : groups.get();
            criteria.setText(InspectorFilterOptions.summary(getContext(), query, choices));
            criteria.setVisibility(compact || query.activeCount() == 0 ? GONE : VISIBLE);
            if (compact) updateTokens(query, choices);
        }
        String count = getContext().getString(R.string.osservio_ui_result_count, visible, total);
        if (!count.contentEquals(results.getText())) results.setText(count);
        empty.setVisibility(visible == 0 ? VISIBLE : GONE);
    }
    View navigationAction() {
        if (actions.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) actions.getParent()).removeView(actions);
        return actions;
    }
    View viewport(android.widget.ListView list) {
        android.widget.FrameLayout viewport = new android.widget.FrameLayout(getContext());
        viewport.addView(list, new android.widget.FrameLayout.LayoutParams(-1, -1));
        android.widget.FrameLayout.LayoutParams floating = new android.widget.FrameLayout.LayoutParams(-2, -2,
            android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        int inset = InspectorTheme.dp(getContext(), 16); floating.setMargins(inset, inset, inset, inset);
        viewport.addView(latest, floating);
        latest.setTag(R.id.osservio_floating_margin, inset);
        return viewport;
    }
    void hideActions() { actions.setVisibility(GONE); }
    void setReading(boolean reading) { activity.setVisibility(reading ? VISIBLE : INVISIBLE); }
    void setPaused(boolean paused) {
        if (compact) {
            pause.setVisibility(GONE); latest.setVisibility(paused ? VISIBLE : GONE);
            status.setVisibility(status.getText().length() == 0 ? GONE : VISIBLE); return;
        }
        pause.setImageResource(paused ? R.drawable.osservio_play : R.drawable.osservio_pause);
        pause.setContentDescription(getContext().getString(paused ? R.string.osservio_ui_follow_latest : R.string.osservio_ui_pause));
    }
    private void updateTokens(InspectorQuery query, List<InspectorFilterSheet.Group> groups) {
        tokens.removeAllViews(); tokenScroll.setVisibility(query.activeCount() == 0 ? GONE : VISIBLE);
        for (InspectorFilterSheet.Group group : InspectorFilterOptions.displayGroups(getContext(), query, groups)) {
            java.util.Set<String> values = query.values.get(group.field);
            if (values == null || values.isEmpty()) continue;
            java.util.List<String> labels = new java.util.ArrayList<>();
            for (String value : new java.util.TreeSet<>(values)) labels.add(InspectorFilterOptions.label(getContext(), group.field, value));
            InspectorQuery changed = query.copy();
            if (group.field == InspectorQuery.Field.TRANSPORT) changed.select(group.field, null); else changed.values.remove(group.field);
            token(group.title + ": " + android.text.TextUtils.join(", ", labels), InspectorFilterSheet.Target.field(group.field),
                group.field == InspectorQuery.Field.TRANSPORT && query.hasTransportConditions() ? null : changed);
        }
        if (query.recentMinutes > 0) { InspectorQuery changed = query.copy(); changed.recentMinutes = 0;
            token(getContext().getString(query.recentMinutes == 5 ? R.string.osservio_ui_time_5 : query.recentMinutes == 30 ? R.string.osservio_ui_time_30 : query.recentMinutes == 60 ? R.string.osservio_ui_time_60 : R.string.osservio_ui_time_1440), InspectorFilterSheet.Target.TIME, changed); }
        if (!query.excludedText.isEmpty()) { InspectorQuery changed = query.copy(); changed.excludedText = ""; token(getContext().getString(R.string.osservio_ui_exclude) + ": " + query.excludedText, InspectorFilterSheet.Target.EXCLUDED_TEXT, changed); }
        if (!query.fieldPath.isEmpty()) { InspectorQuery changed = query.copy(); changed.fieldPath = ""; changed.fieldValue = ""; token(query.fieldPath + " = " + query.fieldValue, InspectorFilterSheet.Target.JSON, changed); }
        if (query.minimumDurationMillis > 0) { InspectorQuery changed = query.copy(); changed.minimumDurationMillis = 0; token("HTTP ≥ " + query.minimumDurationMillis + " ms", InspectorFilterSheet.Target.DURATION, changed); }
    }
    private void token(String label, InspectorFilterSheet.Target target, InspectorQuery changed) {
        Context context = getContext();
        LinearLayout group = new LinearLayout(context);
        group.setGravity(android.view.Gravity.CENTER_VERTICAL);
        group.setBackground(InspectorTheme.shape(context, InspectorTheme.color(context, R.color.osservio_tint), 20, false));
        Button edit = tokenAction(label, () -> editFilter.open(target));
        edit.setContentDescription(label + ", " + context.getString(R.string.osservio_ui_edit_filter));
        int available = tokenScroll.getWidth() > 0 ? tokenScroll.getWidth() : getResources().getDisplayMetrics().widthPixels;
        edit.setMaxWidth(Math.max(InspectorTheme.dp(context, 48), available - InspectorTheme.dp(context, 54)));
        group.addView(edit);
        if (changed != null) {
            Button close = tokenAction("×", () -> removeCondition(changed));
            close.setContentDescription(context.getString(R.string.osservio_ui_remove_filter) + ", " + label);
            group.addView(close, new LayoutParams(InspectorTheme.dp(context, 48), -2));
        }
        LayoutParams layout = new LayoutParams(-2, -2);
        layout.setMarginEnd(InspectorTheme.dp(context, 6)); tokens.addView(group, layout);
    }
    private Button tokenAction(String text, Runnable action) {
        Context context = getContext();
        Button button = new Button(context);
        button.setAllCaps(false); button.setText(text); button.setSingleLine(true);
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        button.setMinWidth(InspectorTheme.dp(context, 48)); button.setMinimumWidth(0);
        button.setMinHeight(InspectorTheme.dp(context, 48)); button.setStateListAnimator(null);
        button.setPadding(InspectorTheme.dp(context, 10), 0, InspectorTheme.dp(context, 10), 0);
        InspectorTheme.typography(button, 14, false); InspectorTheme.feedback(button, 20);
        button.setTextColor(InspectorTheme.accent(context)); button.setOnClickListener(v -> action.run());
        return button;
    }
    private void removeCondition(InspectorQuery changed) {
        if (onQueryChange == null) return;
        removeCallbacks(expireUndo);
        beforeRemoval = displayedQuery.copy(); afterRemoval = changed.copy(); undo.setVisibility(VISIBLE);
        int timeout = 6000;
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.view.accessibility.AccessibilityManager manager = getContext().getSystemService(android.view.accessibility.AccessibilityManager.class);
            if (manager != null) timeout = manager.getRecommendedTimeoutMillis(timeout, android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS);
        }
        postDelayed(expireUndo, timeout);
        onQueryChange.apply(changed);
    }
    private void finishUndo() {
        removeCallbacks(expireUndo); beforeRemoval = null; afterRemoval = null; undo.setVisibility(GONE);
    }
}
