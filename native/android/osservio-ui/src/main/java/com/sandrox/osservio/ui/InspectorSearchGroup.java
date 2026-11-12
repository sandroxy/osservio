package com.sandrox.osservio.ui;

import android.content.Context;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A persistent list header shares its surface with reusable result rows below it. */
@android.annotation.SuppressLint("ViewConstructor") // The page owns the search editor and its callback.
final class InspectorSearchGroup extends LinearLayout {
    private final LinearLayout content;
    private final TextView title, summary, message;
    private final EditText search;
    private boolean hasResults;

    InspectorSearchGroup(Context context, EditText search, int hint) {
        super(context); this.search = search; setOrientation(VERTICAL);
        setPadding(0, InspectorTheme.dp(context, 20), 0, 0);
        content = new LinearLayout(context); content.setOrientation(VERTICAL);
        int inset = InspectorTheme.dp(context, 16); content.setPadding(inset, inset, inset, inset);
        title = InspectorTheme.text(context, 17, true); title.setText(R.string.osservio_runtime_breadcrumbs);
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        summary = InspectorWidgets.caption(context); summary.setTextSize(12); summary.setPadding(0, 0, 0, 0);
        message = InspectorWidgets.caption(context); message.setPadding(0, 0, 0, 0);
        content.addView(title, new LayoutParams(-1, -2)); add(summary); add(search); add(message);
        addView(content, new LayoutParams(-1, -2));
        InspectorAppearance.style(content, () -> InspectorWidgets.groupedRow(content, true, !hasResults, false));
        InspectorAppearance.style(search, () -> {
            search.setTextColor(InspectorTheme.primary(context)); search.setHintTextColor(InspectorTheme.secondary(context));
            search.setHint(hint); search.setContentDescription(context.getString(hint));
            search.setBackgroundTintList(null);
            search.setBackground(InspectorTheme.shape(context, InspectorTheme.canvas(context), 12, false));
            search.setCompoundDrawablesRelative(InspectorWidgets.symbol(context, R.drawable.osservio_search, 19, InspectorTheme.secondary(context)), null, null, null);
        });
    }
    private void add(android.view.View child) {
        LayoutParams params = new LayoutParams(-1, -2); params.topMargin = InspectorTheme.dp(getContext(), 10); content.addView(child, params);
    }
    void bind(String detail, boolean searchable, String emptyMessage, boolean hasResults) {
        this.hasResults = hasResults; title.setText(R.string.osservio_runtime_breadcrumbs);
        summary.setText(detail); summary.setVisibility(detail == null || detail.isEmpty() ? GONE : VISIBLE);
        search.setVisibility(searchable ? VISIBLE : GONE);
        message.setText(emptyMessage); message.setVisibility(emptyMessage == null ? GONE : VISIBLE);
        InspectorWidgets.groupedRow(content, true, !hasResults, false);
    }
}
