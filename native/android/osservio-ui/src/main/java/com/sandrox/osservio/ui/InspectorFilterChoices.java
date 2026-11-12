package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckedTextView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Direct choices for short, stable dimensions. The query owns selection semantics. */
@SuppressLint("ViewConstructor") // Constructed by the filter editor, never inflated or restored by Android.
final class InspectorFilterChoices extends LinearLayout {
    interface Selected { void select(String value); }
    InspectorFilterChoices(Context context, InspectorFilterSheet.Group group, Set<String> selected, Selected action) {
        super(context); setOrientation(VERTICAL); setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView heading = InspectorTheme.text(context, 16, false); heading.setText(group.title);
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        addView(heading);
        List<View> choices = new ArrayList<>();
        choices.add(choice(context.getString(R.string.osservio_ui_all), selected == null || selected.isEmpty(), () -> action.select(null)));
        for (Map.Entry<String, String> option : group.options.entrySet()) {
            choices.add(choice(option.getValue(), selected != null && selected.contains(option.getKey()), () -> action.select(option.getKey())));
        }
        int columns = context.getResources().getConfiguration().fontScale >= 1.5f ? 1 : 3;
        for (int start = 0; start < choices.size(); start += columns) {
            LinearLayout row = new LinearLayout(context); row.setOrientation(HORIZONTAL);
            LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, -2); line.topMargin = dp(start == 0 ? 10 : 8); addView(row, line);
            for (int index = start; index < start + columns; index++) {
                LinearLayout.LayoutParams slot = new LinearLayout.LayoutParams(0, -1, 1); if (index > start) slot.leftMargin = dp(8);
                row.addView(index < choices.size() ? choices.get(index) : new View(context), slot);
            }
        }
    }
    private CheckedTextView choice(String title, boolean checked, Runnable action) {
        Context context = getContext(); CheckedTextView view = new CheckedTextView(context);
        InspectorTheme.typography(view, 15, false); view.setText(title); view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(10), dp(12), dp(10), dp(12)); view.setMinHeight(dp(48));
        view.setChecked(checked); view.setFocusable(true); view.setOnClickListener(ignored -> action.run());
        InspectorAppearance.style(view, () -> {
            view.setTextColor(checked ? InspectorTheme.accent(context) : InspectorTheme.primary(context));
            view.setCheckMarkDrawable(InspectorWidgets.checkmark(context));
            view.setBackground(InspectorTheme.ripple(context, InspectorTheme.shape(context,
                checked ? InspectorTheme.color(context, R.color.osservio_tint) : InspectorTheme.canvas(context), 12, false), null));
        });
        return view;
    }
    private int dp(int value) { return InspectorTheme.dp(getContext(), value); }
}
