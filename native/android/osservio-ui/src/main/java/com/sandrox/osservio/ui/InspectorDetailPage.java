package com.sandrox.osservio.ui;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Grouped metadata and explicit readers share one compact native visual hierarchy. */
final class InspectorDetailPage extends InspectorScroll.Page {
    private final LinearLayout content;
    private LinearLayout group;
    private InspectorScreen reader;
    InspectorDetailPage(Context context) {
        super(context); setFillViewport(true); setVerticalScrollBarEnabled(false);
        content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        int inset = InspectorTheme.dp(context, 16); content.setPadding(inset, 0, inset, inset); addView(content);
    }
    void section(int title) { section(() -> getContext().getString(title)); }
    void section(String title) { section(() -> title); }
    private void section(InspectorAppearance.Value<String> title) {
        TextView label = InspectorWidgets.sectionLabel(getContext());
        InspectorAppearance.text(label, () -> { String value = title.get(); label.setVisibility(value.isEmpty() ? GONE : VISIBLE); return value; });
        content.addView(label, new LinearLayout.LayoutParams(-1, -2));
        group = InspectorWidgets.group(getContext());
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        if (title.get().isEmpty()) layout.topMargin = InspectorTheme.dp(getContext(), 20);
        content.addView(group, layout);
    }
    void field(int title, Object value) { field(title, () -> value); }
    void value(InspectorAppearance.Value<String> value) { value(value, null); }
    InspectorWidgets.ValueRow value(InspectorAppearance.Value<String> value, Runnable action) {
        return value(value, action, 0);
    }
    InspectorWidgets.ValueRow value(InspectorAppearance.Value<String> value, Runnable action, int actionLabel) {
        InspectorWidgets.ValueRow row = new InspectorWidgets.ValueRow(getContext());
        Runnable render = () -> {
            row.bind(value.get(), "", action != null);
            if (actionLabel != 0) row.setContentDescription(getContext().getString(actionLabel));
        };
        InspectorAppearance.content(row, render); render.run(); row.action(action);
        if (action != null) { row.title.setMaxLines(4); row.title.setEllipsize(android.text.TextUtils.TruncateAt.END); InspectorAppearance.style(row.title, () -> row.title.setTextColor(InspectorTheme.accent(getContext()))); }
        InspectorWidgets.addGrouped(group, row); return row;
    }
    void row(int title, InspectorAppearance.Value<String> value, Runnable action) {
        InspectorWidgets.ValueRow row = new InspectorWidgets.ValueRow(getContext());
        Runnable render = () -> row.bind(getContext().getString(title), value.get(), action != null);
        InspectorAppearance.content(row, render); render.run(); row.action(action);
        InspectorWidgets.addGrouped(group, row);
    }
    void field(int title, InspectorAppearance.Value<?> value) { field(() -> getContext().getString(title), value); }
    void header(com.sandrox.osservio.core.HTTPHeader header) {
        field(() -> header.name, () -> InspectorRecordPresentation.compact(header.value, 180), () ->
            read(R.string.osservio_ui_header_value, new InspectorReadContent(header.value, "", false).withContext(() -> header.name)));
    }
    void field(String title, Object value) { field(() -> title, () -> value); }
    void field(InspectorAppearance.Value<String> title, InspectorAppearance.Value<?> value) { field(title, value, null); }
    private void field(InspectorAppearance.Value<String> title, InspectorAppearance.Value<?> value, Runnable action) {
        if (group == null) section(R.string.osservio_ui_metadata);
        Context context = getContext(); int inset = InspectorTheme.dp(context, 16);
        LinearLayout row = new LinearLayout(context); row.setGravity(android.view.Gravity.CENTER_VERTICAL); row.setBaselineAligned(false);
        row.setPadding(inset, InspectorTheme.dp(context, 12), inset, InspectorTheme.dp(context, 12));
        LinearLayout labels = new LinearLayout(context); labels.setOrientation(LinearLayout.VERTICAL); row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        if (group.getChildCount() > 0) group.addView(InspectorWidgets.divider(context));
        TextView label = InspectorWidgets.caption(context); label.setTextSize(12); InspectorAppearance.text(label, title); label.setPadding(0, 0, 0, InspectorTheme.dp(context, 4)); labels.addView(label);
        TextView text = InspectorTheme.text(context, 17, false); InspectorAppearance.text(text, () -> { Object result = value.get(); return result == null ? context.getString(R.string.osservio_http_none) : result.toString(); });
        text.setTextIsSelectable(action == null); labels.addView(text); group.addView(row);
        if (action != null) {
            text.setMaxLines(3); row.setFocusable(true); row.setOnClickListener(ignored -> action.run());
            InspectorAppearance.style(row, () -> InspectorTheme.feedback(row, 0));
            android.widget.ImageView arrow = new android.widget.ImageView(context); arrow.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(InspectorTheme.dp(context, 16), InspectorTheme.dp(context, 20)); layout.leftMargin = InspectorTheme.dp(context, 12);
            row.addView(arrow, layout);
            InspectorAppearance.style(label, () -> label.setTextColor(InspectorTheme.accent(context)));
            InspectorAppearance.style(arrow, () -> arrow.setImageDrawable(InspectorWidgets.symbol(context, R.drawable.osservio_forward, 16, InspectorTheme.secondary(context))));
        }

    }
    void disclosure(int title, InspectorAppearance.Value<String> value, Runnable action) {
        field(() -> getContext().getString(title), value, action);
    }
    void expandable(int title, int show, int hide, Runnable appendFields) {
        section(title); LinearLayout target = group;
        boolean[] expanded = {false};
        android.widget.Button toggle = InspectorWidgets.button(getContext(), show, () -> { });
        toggle.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        toggle.setPadding(InspectorTheme.dp(getContext(), 16), InspectorTheme.dp(getContext(), 12), InspectorTheme.dp(getContext(), 16), InspectorTheme.dp(getContext(), 12));
        InspectorAppearance.style(toggle, () -> {
            toggle.setTextColor(InspectorTheme.primary(getContext()));
            android.graphics.drawable.RotateDrawable arrow = new android.graphics.drawable.RotateDrawable();
            arrow.setDrawable(InspectorWidgets.symbol(getContext(), R.drawable.osservio_expand, 18, InspectorTheme.secondary(getContext())));
            arrow.setFromDegrees(0); arrow.setToDegrees(180); int edge = InspectorTheme.dp(getContext(), 18); arrow.setBounds(0, 0, edge, edge);
            arrow.setLevel(expanded[0] ? 10000 : 0); toggle.setCompoundDrawablesRelative(null, null, arrow, null);
        });
        target.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
        appendFields.run();
        for (int index = 1; index < target.getChildCount(); index++) target.getChildAt(index).setVisibility(GONE);
        InspectorAppearance.text(toggle, () -> getContext().getString(expanded[0] ? hide : show));
        toggle.setOnClickListener(ignored -> {
            expanded[0] = !expanded[0]; toggle.setText(expanded[0] ? hide : show);
            toggle.getCompoundDrawablesRelative()[2].setLevel(expanded[0] ? 10000 : 0);
            for (int index = 1; index < target.getChildCount(); index++) target.getChildAt(index).setVisibility(expanded[0] ? VISIBLE : GONE);
        });
    }
    void note(String message) { note(() -> message); }
    void note(int message) { note(() -> getContext().getString(message)); }
    void note(InspectorAppearance.Value<String> message) {
        TextView note = InspectorWidgets.caption(getContext());
        note.setTextSize(12); int inset = InspectorTheme.dp(getContext(), 16); note.setPadding(inset, InspectorTheme.dp(getContext(), 8), inset, 0);
        InspectorAppearance.text(note, () -> { String value = message.get(); note.setVisibility(value.isEmpty() ? GONE : VISIBLE); return value; });
        content.addView(note);
    }
    void action(int title, Runnable run) {
        if (group == null) section("");
        value(() -> getContext().getString(title), run);
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
