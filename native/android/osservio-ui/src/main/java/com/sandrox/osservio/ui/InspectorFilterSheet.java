package com.sandrox.osservio.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** One virtualized draft: common criteria first, precise criteria on demand. */
final class InspectorFilterSheet {
    private static final String ALL = "\u0000";
    interface Applied { void apply(InspectorQuery query); }
    static final class Group {
        final InspectorQuery.Field field; final String title;
        final Map<String, String> options = new LinkedHashMap<>();
        Group(InspectorQuery.Field field, String title) { this.field = field; this.title = title; }
        Group option(String value, String title) { options.put(value, title); return this; }
    }
    private InspectorFilterSheet() { }
    static InspectorScreen show(Context context, InspectorQuery original, List<Group> groups, Applied applied) { return show(context, original, groups, R.string.osservio_ui_time, false, applied); }
    static InspectorScreen show(Context context, InspectorQuery original, List<Group> groups, boolean payload, Applied applied) { return show(context, original, groups, R.string.osservio_ui_time, payload, applied); }
    static InspectorScreen show(Context context, InspectorQuery original, List<Group> groups, int timeLabel, Applied applied) { return show(context, original, groups, timeLabel, false, applied); }
    private static InspectorScreen show(Context context, InspectorQuery original, List<Group> groups, int timeLabel, boolean payload, Applied applied) {
        Editor editor = new Editor(context, original, groups, timeLabel, payload, applied); editor.screen.show(); return editor.screen;
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
    private enum Type { HEADING, NOTE, SCOPE, GROUP, OPTION, TIME, TIME_OPTION, DURATION, INPUT, REFINE }
    private static final class Row {
        final Type type; final Group group; final String value, label;
        Row(Type type, Group group, String value, String label) { this.type = type; this.group = group; this.value = value; this.label = label; }
        Row(Type type, String label) { this(type, null, null, label); }
    }
    private static final class Editor {
        final Context context; final InspectorQuery draft; final List<Group> groups; final Applied applied;
        final boolean payload, hasHTTP, hasSockets, network; final int timeLabel;
        final InspectorScreen screen; final ListView list; final LinearLayout header, footer;
        final EditText search; final Button leading; final InspectorFilterConditions conditions;
        final List<Row> rows = new ArrayList<>(); final Set<InspectorQuery.Field> expanded = new HashSet<>();
        final int[] times = {0, 5, 30, 60, 1440};
        final BaseAdapter adapter;
        boolean refined, timeExpanded, durationExpanded, searchAttached = true, footerAttached = true; Group picker;
        int overviewPosition, overviewOffset;
        Editor(Context context, InspectorQuery original, List<Group> candidates, int timeLabel, boolean payload, Applied applied) {
            this.context = context; draft = original.copy(); this.timeLabel = timeLabel; this.payload = payload; this.applied = applied;
            hasHTTP = capability(candidates, InspectorQuery.Field.METHOD, "http");
            hasSockets = capability(candidates, InspectorQuery.Field.DIRECTION, "webSocket"); network = hasHTTP || hasSockets;
            List<Group> sources = new ArrayList<>(candidates);
            if (payload) {
                Group scope = new Group(InspectorQuery.Field.SEARCH_SCOPE, text(R.string.osservio_ui_search_scope));
                List<String> names = new ArrayList<>();
                if (network) { names.add("address"); names.add("headers"); if (hasHTTP) { names.add("request"); names.add("response"); } if (hasSockets) names.add("message"); }
                else names.add("message");
                names.add("metadata"); names.add("identity");
                for (String value : names) scope.option(value, InspectorFilterOptions.label(context, scope.field, value)); sources.add(scope);
            }
            groups = InspectorFilterOptions.ordered(available(sources, draft));
            for (Group group : groups) {
                Group source = null; for (Group candidate : sources) if (candidate.field == group.field) { source = candidate; break; }
                for (String key : new ArrayList<>(group.options.keySet())) if (source == null || !source.options.containsKey(key)) group.options.put(key, InspectorFilterOptions.label(context, group.field, key));
            }
            LinearLayout content = column(); int inset = dp(16); content.setPadding(inset, 0, inset, 0);
            list = new InspectorScroll.List(context); list.setDivider(null); list.setDividerHeight(0); list.setSelector(android.R.color.transparent);
            list.setVerticalScrollBarEnabled(false); list.setPadding(0, 0, 0, inset); list.setClipToPadding(false); list.setItemsCanFocus(true);
            header = column(); search = InspectorWidgets.search(context, R.string.osservio_ui_filter_options, "", value -> filterChoices());
            header.addView(search, new LinearLayout.LayoutParams(-1, -2)); header.setPadding(0, dp(12), 0, dp(12));
            list.addHeaderView(header, null, false);
            footer = column(); footer.setPadding(inset, dp(12), inset, dp(24));
            conditions = payload ? new InspectorFilterConditions(context, draft, () -> { }) : null;
            if (conditions != null) footer.addView(conditions.view);
            TextView hint = InspectorWidgets.caption(context); hint.setText(R.string.osservio_ui_filter_hint); footer.addView(hint);
            list.addFooterView(footer, null, false);
            adapter = new BaseAdapter() {
                @Override public int getCount() { return rows.size(); }
                @Override public Row getItem(int position) { return rows.get(position); }
                @Override public long getItemId(int position) { return position; }
                @Override public int getViewTypeCount() { return Type.values().length; }
                @Override public int getItemViewType(int position) { return getItem(position).type.ordinal(); }
                @Override public boolean areAllItemsEnabled() { return false; }
                @Override public boolean isEnabled(int position) { Type type = getItem(position).type; return type == Type.OPTION || type == Type.TIME_OPTION; }
                @Override public View getView(int position, View recycled, ViewGroup parent) { return rowView(position, recycled); }
            };
            list.setAdapter(adapter); content.addView(list, new LinearLayout.LayoutParams(-1, -1));
            list.setOnItemClickListener((parent, view, position, id) -> { int index = position - list.getHeaderViewsCount(); if (index >= 0 && index < rows.size()) choose(rows.get(index)); });
            screen = InspectorDialogs.panel(context, R.string.osservio_ui_filters, content);
            leading = screen.leadingAction(R.string.osservio_ui_reset, () -> {
                InspectorWidgets.hideKeyboard(content);
                if (picker != null) { picker = null; search.setText(""); rebuild(); list.setSelectionFromTop(overviewPosition, overviewOffset); }
                else { draft.resetFilters(); if (conditions != null) conditions.sync(); rebuild(); }
            });
            screen.action(R.string.osservio_ui_apply, () -> {
                InspectorWidgets.hideKeyboard(content);
                if (conditions != null && !conditions.valid()) { android.widget.Toast.makeText(context, R.string.osservio_ui_field_invalid, android.widget.Toast.LENGTH_LONG).show(); return; }
                if (!(hasHTTP && hasSockets)) draft.values.remove(InspectorQuery.Field.TRANSPORT);
                applied.apply(draft); screen.dismiss();
            });
            rebuild();
        }
        String text(int key) { return context.getString(key); }
        int dp(int value) { return InspectorTheme.dp(context, value); }
        LinearLayout column() { LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); return view; }
        String transport() { String value = draft.transport(); return value != null ? value : hasHTTP && !hasSockets ? "http" : hasSockets && !hasHTTP ? "webSocket" : null; }
        boolean applicable(Group group) {
            if (!network) return group.field != InspectorQuery.Field.TRANSPORT;
            if (Arrays.asList(InspectorQuery.HTTP_FIELDS).contains(group.field)) return "http".equals(transport());
            if (Arrays.asList(InspectorQuery.SOCKET_FIELDS).contains(group.field)) {
                if (!"webSocket".equals(transport())) return false;
                if ((group.field == InspectorQuery.Field.DIRECTION || group.field == InspectorQuery.Field.OUTCOME) && !draft.allows(InspectorQuery.Field.ACTIVITY, "messages")) return false;
                if (group.field == InspectorQuery.Field.CLOSE_CODE && !draft.allows(InspectorQuery.Field.ACTIVITY, "connections")) return false;
                if (group.field == InspectorQuery.Field.KIND) {
                    boolean applicable = false;
                    for (String kind : group.options.keySet()) if (draft.allows(InspectorQuery.Field.ACTIVITY, kind.equals("MESSAGE") || kind.equals("MESSAGE_RESULT") ? "messages" : "connections")) { applicable = true; break; }
                    if (!applicable) return false;
                }
            }
            return group.field != InspectorQuery.Field.TRANSPORT;
        }
        boolean advanced(Group group) {
            switch (group.field) {
                case SEARCH_SCOPE: case CONTENT: case SOURCE: case CONNECTION: case OUTCOME: case CLOSE_CODE: return true;
                case KIND: return network;
                case HOST: case CATEGORY: return group.options.size() == 1;
                default: return false;
            }
        }
        void addGroup(Group group) { rows.add(new Row(Type.GROUP, group, null, group.title)); if (expanded.contains(group.field)) addChoices(group, ""); }
        void addChoices(Group group, String term) {
            int start = rows.size();
            if (term.isEmpty()) rows.add(new Row(Type.OPTION, group, ALL, text(R.string.osservio_ui_all)));
            for (Map.Entry<String, String> entry : group.options.entrySet()) {
                if (network && group.field == InspectorQuery.Field.KIND && !draft.allows(InspectorQuery.Field.ACTIVITY, entry.getKey().equals("MESSAGE") || entry.getKey().equals("MESSAGE_RESULT") ? "messages" : "connections")) continue;
                if (group.field == InspectorQuery.Field.SEARCH_SCOPE && (("http".equals(transport()) && entry.getKey().equals("message")) || ("webSocket".equals(transport()) && (entry.getKey().equals("request") || entry.getKey().equals("response"))))) continue;
                if (term.isEmpty() || entry.getValue().toLowerCase(Locale.ROOT).contains(term) || entry.getKey().toLowerCase(Locale.ROOT).contains(term)) rows.add(new Row(Type.OPTION, group, entry.getKey(), entry.getValue()));
            }
            if (group.field == InspectorQuery.Field.STATUS && term.matches("[1-5][0-9]{2}") && !group.options.containsKey(term)) rows.add(new Row(Type.OPTION, group, term, term));
            if (rows.size() == start) rows.add(new Row(Type.NOTE, text(R.string.osservio_ui_filter_options_empty)));
            if (group.field == InspectorQuery.Field.SEARCH_SCOPE) rows.add(new Row(Type.NOTE, text(R.string.osservio_ui_search_scope_hint)));
        }
        void filterChoices() { if (picker == null || adapter == null) return; rows.clear(); addChoices(picker, search.getText().toString().toLowerCase(Locale.ROOT)); adapter.notifyDataSetChanged(); }
        void rebuild() {
            rows.clear();
            if (picker != null) addChoices(picker, search.getText().toString().toLowerCase(Locale.ROOT));
            else {
                if (hasHTTP && hasSockets) { rows.add(new Row(Type.HEADING, text(R.string.osservio_ui_filter_scope))); rows.add(new Row(Type.SCOPE, "")); }
                rows.add(new Row(Type.HEADING, text(R.string.osservio_ui_filter_common)));
                rows.add(new Row(Type.TIME, text(timeLabel)));
                if (timeExpanded) for (int value : times) rows.add(new Row(Type.TIME_OPTION, null, Integer.toString(value), timeText(value)));
                for (Group group : groups) if (applicable(group) && !advanced(group)) addGroup(group);
                if (payload && "http".equals(transport())) { rows.add(new Row(Type.DURATION, text(R.string.osservio_ui_http_timing))); if (durationExpanded) rows.add(new Row(Type.INPUT, "")); }
                boolean precise = payload; for (Group group : groups) if (applicable(group) && advanced(group)) { precise = true; break; }
                if (precise) { rows.add(new Row(Type.HEADING, "")); rows.add(new Row(Type.REFINE, text(R.string.osservio_ui_filter_refine))); if (refined) for (Group group : groups) if (applicable(group) && advanced(group)) addGroup(group); }
            }
            // Fixed ListView items are measured even when GONE or assigned zero height.
            // Detach unused regions so the overview and picker have no phantom spacing.
            if (picker == null && searchAttached) { list.removeHeaderView(header); searchAttached = false; }
            else if (picker != null && !searchAttached) { list.addHeaderView(header, null, false); searchAttached = true; }
            if (picker != null && footerAttached) { list.removeFooterView(footer); footerAttached = false; }
            else if (picker == null && !footerAttached) { list.addFooterView(footer, null, false); footerAttached = true; }
            if (conditions != null) conditions.view.setVisibility(refined ? View.VISIBLE : View.GONE);
            search.setHint(picker != null && picker.field == InspectorQuery.Field.STATUS ? R.string.osservio_ui_specific_status : R.string.osservio_ui_filter_options);
            leading.setText(picker == null ? R.string.osservio_ui_reset : R.string.osservio_ui_back);
            leading.setContentDescription(leading.getText());
            screen.title(() -> picker == null ? text(R.string.osservio_ui_filters) : picker.title);
            adapter.notifyDataSetChanged();
        }
        static boolean capability(List<Group> groups, InspectorQuery.Field field, String value) {
            for (Group group : groups) if (group.field == field || group.field == InspectorQuery.Field.TRANSPORT && group.options.containsKey(value)) return true;
            return false;
        }
        String refineSummary() {
            int count = (draft.excludedText.isEmpty() ? 0 : 1) + (draft.fieldPath.isEmpty() ? 0 : 1);
            for (Group group : groups) if (applicable(group) && advanced(group) && draft.values.containsKey(group.field) && !draft.values.get(group.field).isEmpty()) count++;
            return count == 0 ? text(R.string.osservio_ui_filter_refine_hint) : context.getString(R.string.osservio_ui_filter_count, count);
        }
        String timeText(int value) { return text(value == 0 ? R.string.osservio_ui_time_all : value == 5 ? R.string.osservio_ui_time_5 : value == 30 ? R.string.osservio_ui_time_30 : value == 60 ? R.string.osservio_ui_time_60 : R.string.osservio_ui_time_1440); }
        String summary(Group group) {
            Set<String> values = draft.values.get(group.field); if (values == null || values.isEmpty()) return text(R.string.osservio_ui_all);
            List<String> labels = new ArrayList<>(); for (String value : new java.util.TreeSet<>(values)) { if (labels.size() == 2) break; labels.add(group.options.containsKey(value) ? group.options.get(value) : InspectorFilterOptions.label(context, group.field, value)); }
            return android.text.TextUtils.join(", ", labels) + (values.size() > 2 ? " +" + (values.size() - 2) : "");
        }
        View rowView(int position, View recycled) {
            Row row = rows.get(position);
            if (row.type == Type.NOTE) { TextView label = InspectorWidgets.caption(context); label.setText(row.label); label.setPadding(dp(16), dp(12), dp(16), dp(12)); return label; }
            if (row.type == Type.HEADING) {
                TextView label = InspectorWidgets.sectionLabel(context); label.setText(row.label);
                if (row.label.isEmpty()) { label.setPadding(0, 0, 0, 0); label.setHeight(dp(20)); }
                return label;
            }
            if (row.type == Type.SCOPE) {
                LinearLayout region = column();
                region.addView(InspectorWidgets.segments(context, new int[] {R.string.osservio_ui_all, R.string.osservio_ui_http, R.string.osservio_ws_title}, "http".equals(draft.transport()) ? 1 : "webSocket".equals(draft.transport()) ? 2 : 0,
                    index -> { InspectorWidgets.hideKeyboard(list); draft.select(InspectorQuery.Field.TRANSPORT, index == 1 ? "http" : index == 2 ? "webSocket" : null); if (conditions != null) conditions.sync(); rebuild(); }));
                TextView hint = InspectorWidgets.caption(context); hint.setPadding(dp(16), dp(8), dp(16), 0); hint.setText(R.string.osservio_ui_filter_switch); region.addView(hint); return region;
            }
            boolean first = position == 0 || rows.get(position - 1).type == Type.HEADING;
            boolean last = position + 1 == rows.size() || rows.get(position + 1).type == Type.HEADING || rows.get(position + 1).type == Type.NOTE;
            if (row.type == Type.INPUT) {
                if (conditions.duration.getParent() instanceof ViewGroup) ((ViewGroup) conditions.duration.getParent()).removeView(conditions.duration);
                LinearLayout region = column(); region.setPadding(dp(16), dp(8), dp(16), dp(12)); region.addView(conditions.duration); InspectorWidgets.groupedRow(region, first, last); return region;
            }
            if (row.type == Type.OPTION || row.type == Type.TIME_OPTION) {
                CheckedTextView view = recycled instanceof CheckedTextView ? (CheckedTextView) recycled : InspectorWidgets.choice(context); view.setText(row.label);
                view.setPadding(dp(picker == null ? 28 : 16), dp(13), dp(16), dp(13));
                Set<String> values = row.group == null ? null : draft.values.get(row.group.field);
                view.setChecked(row.type == Type.TIME_OPTION ? row.value.equals(Integer.toString(draft.recentMinutes)) : row.value.equals(ALL) ? values == null || values.isEmpty() : values != null && values.contains(row.value));
                InspectorWidgets.groupedRow(view, first, last); return view;
            }
            InspectorWidgets.ValueRow view = recycled instanceof InspectorWidgets.ValueRow ? (InspectorWidgets.ValueRow) recycled : new InspectorWidgets.ValueRow(context);
            String detail = row.type == Type.GROUP ? summary(row.group) : row.type == Type.TIME ? timeText(draft.recentMinutes) : row.type == Type.DURATION ? draft.minimumDurationMillis == 0 ? text(R.string.osservio_ui_all) : "≥ " + draft.minimumDurationMillis + " ms" : refineSummary();
            view.bind(row.label, detail, true); view.value.setMaxLines(2); view.value.setEllipsize(android.text.TextUtils.TruncateAt.END);
            boolean opensPicker = row.type == Type.GROUP && (row.group.options.size() > 6 || row.group.field == InspectorQuery.Field.STATUS);
            boolean open = row.type == Type.GROUP ? expanded.contains(row.group.field) : row.type == Type.TIME ? timeExpanded : row.type == Type.DURATION ? durationExpanded : refined;
            view.disclosureRotation(opensPicker ? 0 : open ? -90 : 90); view.action(() -> choose(row)); InspectorWidgets.groupedRow(view, first, last); return view;
        }
        void choose(Row row) {
            switch (row.type) {
                case GROUP:
                    InspectorWidgets.hideKeyboard(list);
                    if (row.group.options.size() > 6 || row.group.field == InspectorQuery.Field.STATUS) {
                        overviewPosition = list.getFirstVisiblePosition(); View first = list.getChildAt(0); overviewOffset = first == null ? 0 : first.getTop();
                        picker = row.group; search.setText(""); rebuild(); list.setSelectionFromTop(0, 0); return;
                    }
                    if (!expanded.remove(row.group.field)) expanded.add(row.group.field); break;
                case OPTION:
                    draft.select(row.group.field, row.value.equals(ALL) ? null : row.value);
                    if (!row.value.equals(ALL) && !row.group.options.containsKey(row.value)) row.group.option(row.value, row.value);
                    if (conditions != null) conditions.sync(); break;
                case TIME: timeExpanded = !timeExpanded; break;
                case TIME_OPTION: draft.recentMinutes = Integer.parseInt(row.value); timeExpanded = false; break;
                case DURATION: durationExpanded = !durationExpanded; break;
                case REFINE: InspectorWidgets.hideKeyboard(list); refined = !refined; break;
                default: return;
            }
            rebuild();
        }
    }
}
