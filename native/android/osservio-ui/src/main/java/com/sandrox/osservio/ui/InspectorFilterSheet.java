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
    static InspectorScreen show(Context context, InspectorQuery original, List<Group> groups, int timeLabel, Applied applied) {
        InspectorQuery draft = original.copy();
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
        content.addView(InspectorWidgets.search(context, R.string.osservio_ui_filter_options, "", filter), new LinearLayout.LayoutParams(-1, -2));
        TextView hint = InspectorWidgets.caption(context); hint.setText(R.string.osservio_ui_filter_hint);
        content.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1)); list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Choice choice = choices.get(position); if (choice.key == null) return;
            if (choice.group.field == null) draft.recentMinutes = Integer.parseInt(choice.key);
            else {
                Set<String> selection = draft.values.get(choice.group.field);
                if (selection == null) { selection = new HashSet<>(); draft.values.put(choice.group.field, selection); }
                if (choice.key.equals(ALL)) selection.clear();
                else if (selection.contains(choice.key)) selection.remove(choice.key); else selection.add(choice.key);
            }
            adapter.notifyDataSetChanged();
        });
        InspectorScreen dialog = InspectorDialogs.panel(context, R.string.osservio_ui_filters, content);
        dialog.showCancel();
        dialog.action(R.string.osservio_ui_apply, () -> { applied.apply(draft); dialog.dismiss(); });
        content.addView(InspectorWidgets.button(context, R.string.osservio_ui_reset, () -> {
            draft.values.clear(); draft.recentMinutes = 0; adapter.notifyDataSetChanged();
        }), 2, new LinearLayout.LayoutParams(-1, -2));
        filter.changed(""); dialog.show(); return dialog;
    }
}
