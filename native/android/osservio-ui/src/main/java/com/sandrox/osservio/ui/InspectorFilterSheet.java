package com.sandrox.osservio.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckedTextView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Virtualized filter choices edit a draft; cancel leaves the live query untouched. */
final class InspectorFilterSheet {
    private static final String ALL = "\u0000";
    interface Applied { void apply(InspectorQuery query); }
    static final class Group {
        final InspectorQuery.Field field;
        final String title;
        final Map<String, String> options = new LinkedHashMap<>();
        Group(InspectorQuery.Field field, String title) { this.field = field; this.title = title; }
        Group option(String value, String title) { options.put(value, title); return this; }
    }
    private static final class Choice {
        final Group group; final String key; final String title;
        Choice(Group group, String key, String title) { this.group = group; this.key = key; this.title = title; }
    }
    private InspectorFilterSheet() { }
    static InspectorScreen show(Context context, InspectorQuery original, List<Group> groups, Applied applied) {
        return show(context, original, groups, R.string.osservio_ui_time, applied);
    }
    static InspectorScreen show(Context context, InspectorQuery original, List<Group> groups, boolean payload, Applied applied) {
        return show(context, original, groups, R.string.osservio_ui_time, payload, applied);
    }
    static InspectorScreen show(Context context, InspectorQuery original, List<Group> groups, int timeLabel, Applied applied) {
        return show(context, original, groups, timeLabel, false, applied);
    }
    private static InspectorScreen show(Context context, InspectorQuery original, List<Group> groups, int timeLabel, boolean payload, Applied applied) {
        InspectorQuery draft = original.copy();
        boolean network = false, hasHTTP = false;
        for (Group group : groups) {
            if (group.field == InspectorQuery.Field.TRANSPORT || group.field == InspectorQuery.Field.DIRECTION) network = true;
            if (group.field == InspectorQuery.Field.METHOD) hasHTTP = true;
        }
        final boolean networkQuery = network;
        Group time = new Group(null, context.getString(timeLabel))
            .option("0", context.getString(R.string.osservio_ui_time_all))
            .option("5", context.getString(R.string.osservio_ui_time_5))
            .option("30", context.getString(R.string.osservio_ui_time_30))
            .option("60", context.getString(R.string.osservio_ui_time_60))
            .option("1440", context.getString(R.string.osservio_ui_time_1440));
        List<Group> sections = new ArrayList<>(groups); sections.add(time);
        for (Group group : groups) {
            Set<String> selected = draft.values.get(group.field);
            if (selected != null) for (String value : selected) if (!group.options.containsKey(value)) group.option(value, value);
        }
        List<Choice> choices = new ArrayList<>();
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        int inset = InspectorTheme.dp(context, 16); content.setPadding(inset, 0, inset, 0);
        ListView list = new ListView(context); list.setDividerHeight(0);
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return choices.size(); }
            @Override public Choice getItem(int position) { return choices.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public int getViewTypeCount() { return 2; }
            @Override public int getItemViewType(int position) { return getItem(position).key == null ? 0 : 1; }
            @Override public boolean isEnabled(int position) { return getItem(position).key != null; }
            @Override public boolean areAllItemsEnabled() { return false; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                Choice choice = getItem(position);
                if (choice.key == null) {
                    TextView label = recycled instanceof TextView ? (TextView) recycled : InspectorTheme.text(context, 12, true);
                    label.setText(choice.title); label.setTextColor(InspectorTheme.secondary(context));
                    label.setPadding(0, inset, 0, inset / 2); return label;
                }
                CheckedTextView row = recycled instanceof CheckedTextView ? (CheckedTextView) recycled
                    : (CheckedTextView) android.view.LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_multiple_choice, parent, false);
                row.setText(choice.title); row.setTextColor(InspectorTheme.primary(context)); row.setTextSize(15);
                row.setMinHeight(InspectorTheme.dp(context, 48)); row.setSingleLine(false);
                Set<String> selection = draft.values.get(choice.group.field);
                row.setChecked(choice.group.field == null ? Integer.toString(draft.recentMinutes).equals(choice.key)
                    : choice.key.equals(ALL) ? selection == null || selection.isEmpty() : selection != null && selection.contains(choice.key));
                return row;
            }
        };
        InspectorWidgets.TextChange filter = value -> {
            choices.clear(); String query = value.toLowerCase(Locale.ROOT);
            for (Group group : sections) {
                List<Choice> matches = new ArrayList<>();
                if (group.field != null && query.isEmpty()) matches.add(new Choice(group, ALL, context.getString(R.string.osservio_ui_all)));
                for (Map.Entry<String, String> entry : group.options.entrySet()) {
                    if (query.isEmpty() || entry.getValue().toLowerCase(Locale.ROOT).contains(query)
                        || group.title.toLowerCase(Locale.ROOT).contains(query)) matches.add(new Choice(group, entry.getKey(), entry.getValue()));
                }
                if (!matches.isEmpty()) { choices.add(new Choice(group, null, group.title)); choices.addAll(matches); }
            }
            adapter.notifyDataSetChanged();
        };
        LinearLayout header = new LinearLayout(context); header.setOrientation(LinearLayout.VERTICAL);
        header.addView(InspectorWidgets.search(context, R.string.osservio_ui_filter_options, "", filter), new LinearLayout.LayoutParams(-1, -2));
        TextView hint = InspectorWidgets.caption(context); hint.setText(network ? R.string.osservio_ui_network_filter_hint : R.string.osservio_ui_filter_hint);
        header.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        Conditions conditions = payload ? new Conditions(context, draft, hasHTTP, adapter::notifyDataSetChanged) : null;
        if (conditions != null) {
            header.addView(InspectorWidgets.button(context, R.string.osservio_ui_content_conditions,
                () -> conditions.view.setVisibility(conditions.view.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)), new LinearLayout.LayoutParams(-1, -2));
            header.addView(conditions.view, new LinearLayout.LayoutParams(-1, -2));
        }
        list.addHeaderView(header, null, false); list.setItemsCanFocus(true);
        content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1)); list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            int index = position - list.getHeaderViewsCount(); if (index < 0 || index >= choices.size()) return;
            Choice choice = choices.get(index); if (choice.key == null) return;
            if (choice.group.field == null) draft.recentMinutes = Integer.parseInt(choice.key);
            else if (networkQuery) { draft.select(choice.group.field, choice.key.equals(ALL) ? null : choice.key); if (conditions != null) conditions.sync(); }
            else {
                Set<String> selection = draft.values.get(choice.group.field);
                if (selection == null) { selection = new HashSet<>(); draft.values.put(choice.group.field, selection); }
                if (choice.key.equals(ALL)) selection.clear();
                else if (selection.contains(choice.key)) selection.remove(choice.key); else selection.add(choice.key);
            }
            adapter.notifyDataSetChanged();
        });
        InspectorScreen dialog = InspectorDialogs.panel(context, R.string.osservio_ui_filters, content);
        dialog.leadingAction(R.string.osservio_ui_reset, () -> {
            draft.resetFilters(); if (conditions != null) conditions.sync(); adapter.notifyDataSetChanged();
        });
        dialog.action(R.string.osservio_ui_apply, () -> {
            InspectorWidgets.hideKeyboard(content);
            if (conditions != null && !conditions.valid()) {
                android.widget.Toast.makeText(context, R.string.osservio_ui_field_invalid, android.widget.Toast.LENGTH_LONG).show(); return;
            }
            applied.apply(draft); dialog.dismiss();
        });
        filter.changed(""); dialog.show(); return dialog;
    }
    private static final class Conditions {
        final LinearLayout view;
        final android.widget.EditText exclude, path, value, duration;
        private final InspectorQuery draft;
        private boolean syncing;
        Conditions(Context context, InspectorQuery draft, boolean hasHTTP, Runnable changed) {
            this.draft = draft; view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL);
            TextView hint = InspectorWidgets.caption(context); hint.setText(R.string.osservio_ui_field_hint); view.addView(hint);
            exclude = input(context, R.string.osservio_ui_exclude, 512); path = input(context, R.string.osservio_ui_field_path, 256);
            value = input(context, R.string.osservio_ui_field_value, 512); duration = input(context, R.string.osservio_ui_duration, 19);
            duration.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            for (android.widget.EditText field : new android.widget.EditText[] {exclude, path, value, duration}) {
                if (field != duration || hasHTTP) view.addView(field, new LinearLayout.LayoutParams(-1, -2));
                field.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
                    @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
                    @Override public void afterTextChanged(android.text.Editable text) {
                        if (syncing || android.view.inputmethod.BaseInputConnection.getComposingSpanStart(text) >= 0) return;
                        read(); changed.run();
                    }
                });
            }
            sync(); view.setVisibility(draft.fieldPath.isEmpty() && draft.excludedText.isEmpty() && draft.minimumDurationMillis == 0 ? View.GONE : View.VISIBLE);
        }
        private android.widget.EditText input(Context context, int hint, int limit) {
            android.widget.EditText field = InspectorWidgets.search(context, hint, "", ignored -> { });
            field.setCompoundDrawables(null, null, null, null);
            field.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(limit)}); return field;
        }
        void sync() {
            syncing = true; exclude.setText(draft.excludedText); path.setText(draft.fieldPath); value.setText(draft.fieldValue);
            duration.setText(draft.minimumDurationMillis == 0 ? "" : Long.toString(draft.minimumDurationMillis)); syncing = false;
        }
        private void read() {
            draft.excludedText = exclude.getText().toString(); draft.fieldPath = path.getText().toString(); draft.fieldValue = value.getText().toString();
            long millis = 0; try { if (duration.length() > 0) millis = Long.parseLong(duration.getText().toString()); } catch (NumberFormatException invalid) { /* Validated on Apply. */ }
            if (millis > 0) draft.select(InspectorQuery.Field.TRANSPORT, "http"); draft.minimumDurationMillis = Math.max(0, millis);
        }
        boolean valid() {
            read();
            if (!draft.fieldPath.isEmpty() && InspectorJsonField.components(draft.fieldPath) == null) return false;
            try { return duration.length() == 0 || Long.parseLong(duration.getText().toString()) >= 0; }
            catch (NumberFormatException invalid) { return false; }
        }
    }

}
