package com.sandrox.osservio.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Grouped metadata and explicit readers share one compact native visual hierarchy. */
final class InspectorDetailPage extends ScrollView {
    private final LinearLayout content;
    private LinearLayout group;
    private AlertDialog reader;
    InspectorDetailPage(Context context) {
        super(context); setFillViewport(true);
        content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        int inset = InspectorTheme.dp(context, 16); content.setPadding(inset, 0, inset, inset); addView(content);
    }
    void section(int title) { section(getContext().getString(title)); }
    void section(String title) {
        TextView label = InspectorWidgets.caption(getContext()); label.setText(title); label.setTextColor(InspectorTheme.accent(getContext()));
        content.addView(label, new LinearLayout.LayoutParams(-1, -2));
        group = new LinearLayout(getContext()); group.setOrientation(LinearLayout.VERTICAL); InspectorTheme.surface(group, false, 12);
        content.addView(group, new LinearLayout.LayoutParams(-1, -2));
    }
    void field(int title, Object value) { field(getContext().getString(title), value); }
    void field(String title, Object value) {
        if (group == null) section(R.string.osservio_ui_metadata);
        Context context = getContext(); int inset = InspectorTheme.dp(context, 12);
        LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(inset, inset, inset, inset);
        if (group.getChildCount() > 0) group.addView(InspectorWidgets.divider(context));
        TextView label = InspectorWidgets.caption(context); label.setText(title); label.setPadding(0, 0, 0, InspectorTheme.dp(context, 4)); row.addView(label);
        TextView text = InspectorTheme.text(context, 15, false); text.setText(value == null ? context.getString(R.string.osservio_http_none) : value.toString());
        text.setTextIsSelectable(true); row.addView(text); group.addView(row);
    }
    void note(String message) {
        if (message.isEmpty()) return;
        TextView note = InspectorWidgets.caption(getContext()); note.setText(message); content.addView(note);
    }
    void action(int title, Runnable run) {
        content.addView(InspectorWidgets.button(getContext(), title, run), new LinearLayout.LayoutParams(-1, -2));
    }
    void read(String title, String text, boolean code) {
        if (reader != null) reader.dismiss();
        InspectorContentReader content = new InspectorContentReader(getContext()); content.display(text, code);
        reader = InspectorDialogs.screen(getContext(), title, content); reader.show();
    }
    @Override protected void onDetachedFromWindow() { if (reader != null) reader.dismiss(); super.onDetachedFromWindow(); }
}
