package com.sandrox.osservio.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Native row reuse and full-row activation; no disclosure icon or key/value columns. */
final class InspectorJsonTreeView extends ListView {
    private InspectorJsonDocument document;
    private final Set<Integer> expanded = new HashSet<>();
    private List<InspectorJsonDocument.Row> rows = new ArrayList<>();
    private final Rows adapter = new Rows();
    private int focused = -1;
    InspectorJsonTreeView(Context context) {
        super(context); setDivider(null); setDividerHeight(0); setAdapter(adapter);
        InspectorAppearance.style(this, () -> setBackgroundColor(InspectorTheme.surface(context)));
        InspectorAppearance.content(this, adapter::notifyDataSetChanged);
        setPadding(0, InspectorTheme.dp(context, 12), 0, InspectorTheme.dp(context, 16)); setClipToPadding(false);
        setOnItemClickListener((parent, view, position, id) -> toggle(position));
        setOnItemLongClickListener((parent, view, position, id) -> {
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            if (clipboard == null || document == null) return false;
            clipboard.setPrimaryClip(ClipData.newPlainText("JSON", document.original(rows.get(position).node)));
            Toast.makeText(context, R.string.osservio_ui_copied, Toast.LENGTH_SHORT).show(); return true;
        });
    }
    boolean hasDocument() { return document != null; }
    void clear() { document = null; expanded.clear(); focused = -1; rebuild(); }
    void display(InspectorJsonDocument value) { document = value; expanded.clear(); focused = -1; rebuild(); }
    void focus(int offset, boolean reveal) {
        if (document == null) return;
        int next = offset < 0 ? -1 : document.nodeAt(offset);
        boolean changed = next != focused; focused = next;
        if (focused >= 0 && reveal) changed = expanded.addAll(document.ancestors(focused)) || changed;
        if (changed) rebuild();
        if (reveal && focused >= 0) for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).node == focused && !rows.get(i).closing) { setSelection(i); break; }
        }
    }
    private void rebuild() { rows = document == null ? new ArrayList<>() : document.rows(expanded); adapter.notifyDataSetChanged(); }
    private void toggle(int position) {
        InspectorJsonDocument.Row row = rows.get(position);
        if (row.closing || row.node == 0 || !document.nodes.get(row.node).expandable()) return;
        int first = getFirstVisiblePosition(), top = getChildCount() == 0 ? 0 : getChildAt(0).getTop();
        long anchor = first < rows.size() ? rows.get(first).identity() : 0;
        if (!expanded.remove(row.node)) expanded.add(row.node);
        rebuild();
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).identity() == anchor) { setSelectionFromTop(i, top); break; }
    }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return rows.get(position).identity(); }
        @Override public boolean hasStableIds() { return true; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Context context = getContext(); TextView text;
            if (recycled instanceof TextView) text = (TextView) recycled;
            else { text = InspectorTheme.text(context, 14, false); text.setTypeface(Typeface.MONOSPACE); text.setGravity(android.view.Gravity.CENTER_VERTICAL); }
            InspectorJsonDocument.Row row = rows.get(position); InspectorJsonDocument.Node node = document.nodes.get(row.node);
            boolean toggle = !row.closing && row.node != 0 && node.expandable();
            String key = (node.depth > 8 ? "… " : "") + (row.closing ? "" : document.key(row.node));
            String token = document.token(row, expanded) + document.comma(row, expanded);
            SpannableString line = new SpannableString(key + token);
            int color;
            switch (node.kind) {
                case STRING: color = R.color.osservio_success; break;
                case NUMBER: color = R.color.osservio_json_number; break;
                case LITERAL: color = R.color.osservio_json_literal; break;
                default: color = R.color.osservio_secondary;
            }
            if (!key.isEmpty()) line.setSpan(new ForegroundColorSpan(InspectorTheme.accent(context)), 0, key.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            line.setSpan(new ForegroundColorSpan(InspectorTheme.color(context, color)), key.length(), line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setText(line); text.setMinHeight(InspectorTheme.dp(context, toggle ? 44 : 28));
            text.setPadding(InspectorTheme.dp(context, 16 + Math.min(node.depth, 8) * 14), InspectorTheme.dp(context, 3), InspectorTheme.dp(context, 16), InspectorTheme.dp(context, 3));
            text.setBackgroundColor(InspectorTheme.color(context, focused == row.node && !row.closing ? R.color.osservio_tint : R.color.osservio_surface));
            text.setContentDescription(line + (toggle ? ", " + context.getString(expanded.contains(row.node) ? R.string.osservio_json_expanded : R.string.osservio_json_collapsed) : "")
                + ", " + context.getString(toggle ? R.string.osservio_json_toggle_hint : R.string.osservio_json_copy_hint));
            return text;
        }
    }
}
