package com.sandrox.osservio.ui;

import android.content.Context;
import android.widget.CheckedTextView;
import android.widget.LinearLayout;

/** Selecting a language updates this retained preference page in place. */
final class InspectorLanguageView extends InspectorScroll.Page implements InspectorScreen.Content {
    InspectorLanguageView(Context context) {
        super(context);
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        int inset = InspectorTheme.dp(context, 16); content.setPadding(inset, InspectorTheme.dp(context, 20), inset, inset); addView(content);
        LinearLayout choices = InspectorWidgets.group(context); content.addView(choices);
        for (boolean english : new boolean[] {false, true}) {
            CheckedTextView row = InspectorWidgets.choice(context);
            InspectorAppearance.text(row, english ? R.string.osservio_ui_english : R.string.osservio_ui_chinese);
            InspectorAppearance.content(row, () -> row.setChecked(InspectorPreferences.english(context) == english));
            row.setChecked(InspectorPreferences.english(context) == english);
            InspectorAppearance.style(row, () -> { row.setTextColor(InspectorTheme.primary(context)); row.setCheckMarkDrawable(InspectorWidgets.checkmark(context)); InspectorTheme.feedback(row, 0); });
            row.setOnClickListener(ignored -> InspectorPreferences.setEnglish(context, english));
            InspectorWidgets.addGrouped(choices, row);
        }
        android.widget.TextView hint = InspectorWidgets.caption(context); hint.setPadding(inset, InspectorTheme.dp(context, 8), inset, 0);
        InspectorAppearance.text(hint, R.string.osservio_ui_preferences_hint); content.addView(hint);
    }
    @Override public void bindScreen(InspectorScreen screen) { screen.title(R.string.osservio_ui_language); }
}
