package com.sandrox.osservio.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Toast;

/** Recycled, ordered fields. Long values open the existing content reader. */
final class InspectorFormView extends InspectorScroll.List {
    private InspectorFormDocument document;
    private int focused = -1;
    private final Rows rows = new Rows();
    interface Open { void accept(InspectorFormDocument.Field field); }
    Open open;
    InspectorFormView(Context context) {
        super(context); setDivider(null); setDividerHeight(0); setAdapter(rows);
        setPadding(0, InspectorTheme.dp(context, 12), 0, InspectorTheme.dp(context, 16)); setClipToPadding(false);
        InspectorAppearance.style(this, () -> setBackgroundColor(InspectorTheme.canvas(context)));
        InspectorAppearance.content(this, rows::notifyDataSetChanged);
        setOnItemClickListener((parent, view, position, id) -> { if (document != null && open != null) open.accept(document.fields.get(position)); });
        setOnItemLongClickListener((parent, view, position, id) -> {
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            if (clipboard == null || document == null) return false;
            clipboard.setPrimaryClip(ClipData.newPlainText("Form field", document.fields.get(position).original));
            Toast.makeText(context, R.string.osservio_ui_copied, Toast.LENGTH_SHORT).show(); return true;
        });
    }
    InspectorFormDocument document() { return document; }
    void display(InspectorFormDocument value) { document = value; focused = -1; rows.notifyDataSetChanged(); }
    void focus(int offset, boolean reveal) {
        int next = document == null ? -1 : document.fieldAt(offset);
        if (next != focused) { focused = next; rows.notifyDataSetChanged(); }
        if (reveal && next >= 0) setSelection(next);
    }
    static String value(Context context, InspectorFormDocument.Field field) {
        if (!field.hasEquals) return context.getString(R.string.osservio_form_missing_equals);
        if (field.value.isEmpty()) return context.getString(R.string.osservio_form_empty_value);
        int end = field.value.offsetByCodePoints(0, Math.min(512, field.value.codePointCount(0, field.value.length())));
        return field.value.substring(0, end) + (end < field.value.length() ? "…" : "");
    }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return document == null ? 0 : document.fields.size(); }
        @Override public Object getItem(int position) { return document.fields.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean hasStableIds() { return true; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            InspectorWidgets.ValueRow row = recycled instanceof InspectorWidgets.ValueRow ? (InspectorWidgets.ValueRow) recycled : new InspectorWidgets.ValueRow(getContext());
            InspectorFormDocument.Field field = document.fields.get(position);
            row.bind(field.name.isEmpty() ? getContext().getString(R.string.osservio_form_empty_name) : InspectorRecordPresentation.compact(field.name, 180), value(getContext(), field), true);
            row.title.setTextSize(12); row.title.setTextColor(InspectorTheme.secondary(getContext())); row.title.setMaxLines(3);
            row.value.setTextSize(17); row.value.setTextColor(InspectorTheme.primary(getContext())); row.value.setMaxLines(6);
            row.title.setEllipsize(android.text.TextUtils.TruncateAt.END); row.value.setEllipsize(android.text.TextUtils.TruncateAt.END);
            InspectorWidgets.groupedRow(row, position == 0, position == getCount() - 1);
            if (focused == position) row.setBackgroundColor(InspectorTheme.color(getContext(), R.color.osservio_tint));
            return row;
        }
    }
}
