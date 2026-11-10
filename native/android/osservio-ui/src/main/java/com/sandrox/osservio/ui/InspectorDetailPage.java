package com.sandrox.osservio.ui;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Grouped metadata and explicit readers share one compact native visual hierarchy. */
final class InspectorDetailPage extends ScrollView {
    private final LinearLayout content;
    private LinearLayout group;
    private InspectorScreen reader;
    InspectorDetailPage(Context context) {
        super(context); setFillViewport(true);
        content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        int inset = InspectorTheme.dp(context, 16); content.setPadding(inset, 0, inset, inset); addView(content);
    }
    void section(int title) { section(() -> getContext().getString(title)); }
    void section(String title) { section(() -> title); }
    private void section(InspectorAppearance.Value<String> title) {
        TextView label = InspectorWidgets.caption(getContext()); InspectorAppearance.text(label, title);
        InspectorAppearance.style(label, () -> label.setTextColor(InspectorTheme.accent(getContext())));
        content.addView(label, new LinearLayout.LayoutParams(-1, -2));
        group = new LinearLayout(getContext()); group.setOrientation(LinearLayout.VERTICAL); LinearLayout surface = group;
        InspectorAppearance.style(surface, () -> InspectorTheme.surface(surface, false, 12));
        content.addView(group, new LinearLayout.LayoutParams(-1, -2));
    }
    void field(int title, Object value) { field(title, () -> value); }
    void field(int title, InspectorAppearance.Value<?> value) { field(() -> getContext().getString(title), value); }
    void field(String title, Object value) { field(() -> title, () -> value); }
    private void field(InspectorAppearance.Value<String> title, InspectorAppearance.Value<?> value) {
        if (group == null) section(R.string.osservio_ui_metadata);
        Context context = getContext(); int inset = InspectorTheme.dp(context, 12);
        LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(inset, inset, inset, inset);
        if (group.getChildCount() > 0) group.addView(InspectorWidgets.divider(context));
        TextView label = InspectorWidgets.caption(context); InspectorAppearance.text(label, title); label.setPadding(0, 0, 0, InspectorTheme.dp(context, 4)); row.addView(label);
        TextView text = InspectorTheme.text(context, 15, false); InspectorAppearance.text(text, () -> { Object result = value.get(); return result == null ? context.getString(R.string.osservio_http_none) : result.toString(); });
        text.setTextIsSelectable(true); row.addView(text); group.addView(row);
    }
    void note(String message) { note(() -> message); }
    void note(int message) { note(() -> getContext().getString(message)); }
    void note(InspectorAppearance.Value<String> message) {
        TextView note = InspectorWidgets.caption(getContext());
        InspectorAppearance.text(note, () -> { String value = message.get(); note.setVisibility(value.isEmpty() ? GONE : VISIBLE); return value; });
        content.addView(note);
    }
    void action(int title, Runnable run) {
        content.addView(InspectorWidgets.button(getContext(), title, run), new LinearLayout.LayoutParams(-1, -2));
    }
    void read(int title, String text, boolean code) {
        read(getContext().getString(title), text, code); reader.title(title);
    }
    void read(int title, InspectorReadContent value) {
        read(getContext().getString(title), value); reader.title(title);
    }
    void read(String title, String text, boolean code) {
        read(title, new InspectorReadContent(text, "", code), code);
    }
    void read(String title, InspectorReadContent value) { read(title, value, true); }
    private void read(String title, InspectorReadContent value, boolean code) {
        if (reader != null) reader.dismiss();
        InspectorContentReader content = new InspectorContentReader(getContext()); content.display(value, code);
        reader = InspectorDialogs.screen(getContext(), title, content);
        InspectorScreen shown = reader; shown.setOnDismissListener(ignored -> { if (reader == shown) reader = null; }); reader.show();
    }
    @Override protected void onDetachedFromWindow() { if (!InspectorNavigation.owns(getContext()) && reader != null) reader.dismiss(); super.onDetachedFromWindow(); }
}
