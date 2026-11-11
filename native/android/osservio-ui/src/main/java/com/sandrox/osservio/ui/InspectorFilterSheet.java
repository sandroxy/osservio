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
        List<Group> sections = available(groups, draft); sections.add(time);
        List<Choice> choices = new ArrayList<>();
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        int inset = InspectorTheme.dp(context, 16); content.setPadding(inset, 0, inset, 0);
        ListView list = new InspectorScroll.List(context); list.setDivider(null); list.setDividerHeight(0);
        list.setVerticalScrollBarEnabled(false); list.setSelector(android.R.color.transparent);
        list.setPadding(0, 0, 0, inset); list.setClipToPadding(false);
        InspectorFilterConditions conditions = payload ? new InspectorFilterConditions(context, draft, () -> {
            // Checkmarks are rebound without rebuilding focused editors.
            for (int index = 0; index < list.getChildCount(); index++) {
                View child = list.getChildAt(index);
                int position = list.getFirstVisiblePosition() + index - list.getHeaderViewsCount();
                if (child instanceof CheckedTextView && position >= 0 && position < choices.size()) bind((CheckedTextView) child, choices.get(position), draft);
            }
        }) : null;
        final boolean durationEnabled = hasHTTP && conditions != null;
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return choices.size(); }
            @Override public Choice getItem(int position) { return choices.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public int getViewTypeCount() { return 3; }
            @Override public int getItemViewType(int position) { return getItem(position).group == null ? 2 : getItem(position).key == null ? 0 : 1; }
            @Override public boolean isEnabled(int position) { return getItem(position).key != null; }
            @Override public boolean areAllItemsEnabled() { return false; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                Choice choice = getItem(position);
                if (choice.group == null) return conditions.durationView;
                if (choice.key == null) {
                    TextView label = recycled instanceof TextView ? (TextView) recycled : InspectorWidgets.sectionLabel(context);
                    label.setText(choice.title); label.setTextColor(InspectorTheme.secondary(context));
                    return label;
                }
                CheckedTextView row = recycled instanceof CheckedTextView ? (CheckedTextView) recycled
                    : InspectorWidgets.choice(context);
                row.setText(choice.title);
                boolean first = position == 0 || getItem(position - 1).key == null;
                boolean last = position + 1 == choices.size() || getItem(position + 1).key == null;
                InspectorWidgets.groupedRow(row, first, last);
                bind(row, choice, draft);
                return row;
            }
        };
        InspectorWidgets.TextChange filter = value -> {
            choices.clear(); String query = value.toLowerCase(Locale.ROOT);
            int lastHTTP = -1;
            for (int index = 0; index < sections.size(); index++) if (isHTTP(sections.get(index).field)) lastHTTP = index;
            for (int index = 0; index < sections.size(); index++) {
                Group group = sections.get(index);
                List<Choice> matches = new ArrayList<>();
                if (group.field != null && query.isEmpty()) matches.add(new Choice(group, ALL, context.getString(R.string.osservio_ui_all)));
                for (Map.Entry<String, String> entry : group.options.entrySet()) {
                    if (query.isEmpty() || entry.getValue().toLowerCase(Locale.ROOT).contains(query)
                        || group.title.toLowerCase(Locale.ROOT).contains(query)) matches.add(new Choice(group, entry.getKey(), entry.getValue()));
                }
                if (!matches.isEmpty()) { choices.add(new Choice(group, null, group.title)); choices.addAll(matches); }
                if (durationEnabled && query.isEmpty() && (index == lastHTTP || lastHTTP < 0 && index == sections.size() - 2)) choices.add(new Choice(null, null, ""));
            }
            adapter.notifyDataSetChanged();
        };
        LinearLayout header = new LinearLayout(context); header.setOrientation(LinearLayout.VERTICAL); header.setPadding(0, InspectorTheme.dp(context, 8), 0, 0);
        header.addView(InspectorWidgets.search(context, R.string.osservio_ui_filter_options, "", filter), new LinearLayout.LayoutParams(-1, -2));
        TextView hint = InspectorWidgets.caption(context); hint.setText(network ? R.string.osservio_ui_network_filter_hint : R.string.osservio_ui_filter_hint);
        hint.setTextSize(12); hint.setPadding(inset, InspectorTheme.dp(context, 6), inset, 0);
        header.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        if (conditions != null) header.addView(conditions.view, new LinearLayout.LayoutParams(-1, -2));
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
    static List<Group> available(List<Group> groups, InspectorQuery draft) {
        List<Group> result = new ArrayList<>();
        for (Group original : groups) {
            Group group = new Group(original.field, original.title); group.options.putAll(original.options);
            Set<String> selected = draft.values.get(group.field);
            if (selected != null) for (String value : new java.util.TreeSet<>(selected)) if (!group.options.containsKey(value)) group.option(value, value);
            if (!group.options.isEmpty()) result.add(group);
        }
        return result;
    }
    private static boolean isHTTP(InspectorQuery.Field field) {
        return field == InspectorQuery.Field.METHOD || field == InspectorQuery.Field.STATE || field == InspectorQuery.Field.STATUS || field == InspectorQuery.Field.CONTENT;
    }
    private static void bind(CheckedTextView row, Choice choice, InspectorQuery draft) {
        if (choice.group == null || choice.key == null) return;
        Set<String> selection = draft.values.get(choice.group.field);
        row.setChecked(choice.group.field == null ? Integer.toString(draft.recentMinutes).equals(choice.key)
            : choice.key.equals(ALL) ? selection == null || selection.isEmpty() : selection != null && selection.contains(choice.key));
    }
}
