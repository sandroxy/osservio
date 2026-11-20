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
    static final class Target {
        static final Target TIME = new Target(null), DURATION = new Target(null),
            EXCLUDED_TEXT = new Target(null), JSON = new Target(null);
        final InspectorQuery.Field field;
        private Target(InspectorQuery.Field field) { this.field = field; }
        static Target field(InspectorQuery.Field field) { return new Target(field); }
    }
    interface Edit { void open(Target target); }
    interface Applied { void apply(InspectorQuery query); }
    static final class Group {
        final InspectorQuery.Field field; final String title;
        final Map<String, String> options = new LinkedHashMap<>();
        Group(InspectorQuery.Field field, String title) { this.field = field; this.title = title; }
        Group option(String value, String title) { options.put(value, title); return this; }
    }
    private InspectorFilterSheet() { }
    static InspectorScreen show(Context context, InspectorQuery original, List<Group> groups, Applied applied) { return show(context, original, groups, R.string.osservio_ui_time, false, null, applied); }
    static InspectorScreen show(Context context, InspectorQuery original, List<Group> groups, boolean payload, Applied applied) { return show(context, original, groups, R.string.osservio_ui_time, payload, null, applied); }
    static InspectorScreen show(Context context, InspectorQuery original, List<Group> groups, int timeLabel, Applied applied) { return show(context, original, groups, timeLabel, false, null, applied); }
    static InspectorScreen show(Context context, InspectorQuery original, List<Group> groups, int timeLabel, boolean payload, Target target, Applied applied) {
        return show(context, original, groups, timeLabel, payload, target, null, applied);
    }
    static InspectorScreen show(Context context, InspectorQuery original, List<Group> groups, int timeLabel, boolean payload,
                                Target target, InspectorRecentFilters recent, Applied applied) {
        Editor editor = new Editor(context, original, groups, timeLabel, payload, recent, applied); editor.focus(target); editor.screen.show(); return editor.screen;
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
    private enum Type { HEADING, GAP, NOTE, SCOPE, GROUP_HEADING, GROUP, OPTION, TIME, TIME_OPTION, DURATION, INPUT, REFINE, RECENT_TOGGLE, RECENT }
    private static final class Row {
        final Type type; final Group group; final String value, label;
        Row(Type type, Group group, String value, String label) { this.type = type; this.group = group; this.value = value; this.label = label; }
        Row(Type type, String label) { this(type, null, null, label); }
    }
    private static final class Editor {
        final Context context; final InspectorQuery draft; final List<Group> groups; final Applied applied;
        final List<Group> candidates;
        final InspectorRecentFilters recent;
        List<InspectorQuery> recentItems = new ArrayList<>();
        boolean showsRecent;
        final boolean payload, hasHTTP, hasSockets, network; final int timeLabel;
        final InspectorScreen screen; final ListView list; final LinearLayout content, header, footer;
        final EditText search; final Button leading; final InspectorFilterConditions conditions;
        final List<Row> rows = new ArrayList<>(); final Set<InspectorQuery.Field> expanded = new HashSet<>();
        final int[] times = {0, 5, 30, 60, 1440};
        final BaseAdapter adapter;
        boolean refined, timeExpanded, durationExpanded, searchAttached = true, footerAttached = true; Group picker;
        final InspectorQuery.ScopeDraft scopes = new InspectorQuery.ScopeDraft();
        int overviewPosition, overviewOffset;
        Editor(Context context, InspectorQuery original, List<Group> candidates, int timeLabel, boolean payload, InspectorRecentFilters recent, Applied applied) {
            this.recent = recent;
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
            this.candidates = sources;
            groups = new ArrayList<>(); refreshGroups();
            content = column(); int inset = dp(16); content.setPadding(inset, 0, inset, 0);
            list = new InspectorScroll.List(context); list.setDivider(null); list.setDividerHeight(0); list.setSelector(android.R.color.transparent);
            list.setVerticalScrollBarEnabled(false); list.setPadding(0, 0, 0, inset); list.setClipToPadding(false); list.setItemsCanFocus(true);
            header = column(); search = InspectorWidgets.search(context, R.string.osservio_ui_filter_options, "", value -> filterChoices());
            header.addView(search, new LinearLayout.LayoutParams(-1, -2)); header.setPadding(0, dp(12), 0, dp(12));
            list.addHeaderView(header, null, false);
            footer = column(); footer.setPadding(0, dp(12), 0, dp(24));
            conditions = payload ? new InspectorFilterConditions(context, draft, () -> { }) : null;
            if (conditions != null) footer.addView(conditions.view);
            TextView hint = InspectorWidgets.caption(context); hint.setPadding(inset, dp(8), inset, dp(8)); hint.setText(R.string.osservio_ui_filter_hint); footer.addView(hint);
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
                if (!backToOverview()) { scopes.reset(); draft.resetFilters(); if (conditions != null) conditions.sync(); rebuild(); }
            });
            screen.setBackNavigation(this::backToOverview);
            screen.action(R.string.osservio_ui_apply, () -> {
                InspectorWidgets.hideKeyboard(content);
                if (conditions != null && !conditions.valid()) { android.widget.Toast.makeText(context, R.string.osservio_ui_field_invalid, android.widget.Toast.LENGTH_LONG).show(); return; }
                if (!(hasHTTP && hasSockets)) draft.values.remove(InspectorQuery.Field.TRANSPORT);
                if (recent != null) recent.update(draft, false);
                applied.apply(draft); screen.dismiss();
            });
            rebuild();
            if (recent == null) return;
            java.lang.ref.WeakReference<InspectorScreen> openSheet = new java.lang.ref.WeakReference<>(screen);
            recent.ownerChanged = () -> { InspectorScreen previous = openSheet.get(); if (previous != null && previous.isShowing()) previous.dismiss(); };
            recent.load(items -> {
                for (InspectorQuery item : items) {
                    if ((!hasHTTP && "http".equals(item.transport())) || (!hasSockets && "webSocket".equals(item.transport()))) continue;
                    recentItems.add(item);
                }
                if (!recentItems.isEmpty() && picker == null && !refined && !durationExpanded) rebuild();
            });
        }
        void refreshGroups() {
            groups.clear(); groups.addAll(InspectorFilterOptions.ordered(available(candidates, draft)));
            for (Group group : groups) {
                for (Group source : candidates) if (source.field == group.field) {
                    for (String key : group.options.keySet()) if (!source.options.containsKey(key)) {
                        group.options.put(key, InspectorFilterOptions.label(context, group.field, key));
                    }
                    break;
                }
            }
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
            if (group.field == InspectorQuery.Field.EXCLUDED_HOST || group.field == InspectorQuery.Field.EXCLUDED_CATEGORY) return true;
            switch (group.field) {
                case SEARCH_SCOPE: case CONTENT: case SOURCE: case CONNECTION: case OUTCOME: case CLOSE_CODE: return true;
                case KIND: return network;
                case HOST: case CATEGORY: return group.options.size() == 1;
                default: return false;
            }
        }
        boolean direct(Group group) {
            return (group.field == InspectorQuery.Field.LEVEL || group.field == InspectorQuery.Field.ACTIVITY || group.field == InspectorQuery.Field.DIRECTION) && group.options.size() <= 6;
        }
        void addGroup(Group group) {
            rows.add(new Row(direct(group) ? Type.GROUP_HEADING : Type.GROUP, group, null, group.title));
            if (direct(group) || expanded.contains(group.field)) addChoices(group, "");
        }
        void addChoices(Group group, String term) {
            int start = rows.size();
            if (term.isEmpty()) rows.add(new Row(Type.OPTION, group, ALL, text(group.field == InspectorQuery.Field.EXCLUDED_CATEGORY || group.field == InspectorQuery.Field.EXCLUDED_HOST ? R.string.osservio_ui_no_exclusions : R.string.osservio_ui_all)));
            for (Map.Entry<String, String> entry : group.options.entrySet()) {
                if (network && group.field == InspectorQuery.Field.KIND && !draft.allows(InspectorQuery.Field.ACTIVITY, entry.getKey().equals("MESSAGE") || entry.getKey().equals("MESSAGE_RESULT") ? "messages" : "connections")) continue;
                if (group.field == InspectorQuery.Field.SEARCH_SCOPE && (("http".equals(transport()) && entry.getKey().equals("message")) || ("webSocket".equals(transport()) && (entry.getKey().equals("request") || entry.getKey().equals("response"))))) continue;
                if (term.isEmpty() || entry.getValue().toLowerCase(Locale.ROOT).contains(term) || entry.getKey().toLowerCase(Locale.ROOT).contains(term)) rows.add(new Row(Type.OPTION, group, entry.getKey(), entry.getValue()));
            }
            if (group.field == InspectorQuery.Field.STATUS && term.matches("[1-5][0-9]{2}") && !group.options.containsKey(term)) rows.add(new Row(Type.OPTION, group, term, term));
            if (rows.size() == start) rows.add(new Row(Type.NOTE, text(R.string.osservio_ui_filter_options_empty)));
            if (group.field == InspectorQuery.Field.SEARCH_SCOPE) rows.add(new Row(Type.NOTE, text(R.string.osservio_ui_search_scope_hint)));
        }
        String selectionRule(Group group) {
            return text(group.field == InspectorQuery.Field.ACTIVITY || group.field == InspectorQuery.Field.SEARCH_SCOPE
                ? R.string.osservio_ui_filter_single : R.string.osservio_ui_filter_multiple);
        }
        void pickerRows() {
            rows.add(new Row(Type.NOTE, selectionRule(picker)));
            addChoices(picker, search.getText().toString().toLowerCase(Locale.ROOT));
        }
        boolean protocolField(Group group) {
            return network && (Arrays.asList(InspectorQuery.HTTP_FIELDS).contains(group.field)
                || Arrays.asList(InspectorQuery.SOCKET_FIELDS).contains(group.field));
        }
        void filterChoices() { if (picker == null || adapter == null) return; rows.clear(); pickerRows(); adapter.notifyDataSetChanged(); }
        void rebuild() {
            rows.clear();
            if (picker != null) pickerRows();
            else {
                if (hasHTTP && hasSockets) { rows.add(new Row(Type.HEADING, text(R.string.osservio_ui_filter_scope))); rows.add(new Row(Type.SCOPE, "")); }
                rows.add(new Row(Type.HEADING, text(R.string.osservio_ui_filter_common)));
                List<Group> shared = new ArrayList<>(), specific = new ArrayList<>();
                for (Group group : groups) {
                    if (!applicable(group) || advanced(group)) continue;
                    if (direct(group)) {
                        addGroup(group);
                        rows.add(new Row(Type.GAP, ""));
                    } else if (protocolField(group)) specific.add(group);
                    else shared.add(group);
                }
                rows.add(new Row(Type.TIME, text(timeLabel)));
                if (timeExpanded) for (int value : times) rows.add(new Row(Type.TIME_OPTION, null, Integer.toString(value), timeText(value)));
                for (Group group : shared) addGroup(group);
                if (!specific.isEmpty() || (payload && "http".equals(transport()))) rows.add(new Row(Type.GAP, ""));
                for (Group group : specific) addGroup(group);
                if (payload && "http".equals(transport())) { rows.add(new Row(Type.DURATION, text(R.string.osservio_ui_http_timing))); if (durationExpanded) rows.add(new Row(Type.INPUT, "")); }
                if (!recentItems.isEmpty()) {
                    rows.add(new Row(Type.GAP, "")); rows.add(new Row(Type.RECENT_TOGGLE, text(R.string.osservio_ui_recent_filters)));
                    if (showsRecent) for (int i = 0; i < recentItems.size(); i++) {
                        InspectorQuery item = recentItems.get(i);
                        rows.add(new Row(Type.RECENT, null, Integer.toString(i), InspectorFilterOptions.summary(context, item, candidates)));
                    }
                }
                boolean precise = payload; for (Group group : groups) if (applicable(group) && advanced(group)) { precise = true; break; }
                if (precise) { rows.add(new Row(Type.GAP, "")); rows.add(new Row(Type.REFINE, text(R.string.osservio_ui_filter_refine))); if (refined) for (Group group : groups) if (applicable(group) && advanced(group)) addGroup(group); }
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
            List<String> details = new ArrayList<>();
            for (Group group : groups) if (applicable(group) && advanced(group) && draft.values.containsKey(group.field) && !draft.values.get(group.field).isEmpty()) details.add(group.title + ": " + compact(summary(group)));
            if (!draft.excludedText.isEmpty()) details.add(text(R.string.osservio_ui_exclude) + ": " + compact(draft.excludedText));
            if (!draft.fieldPath.isEmpty()) details.add(text(R.string.osservio_ui_json_field) + ": " + compact(InspectorFilterOptions.jsonCondition(context, draft.fieldPath, draft.fieldValue)));
            return details.isEmpty() ? text(R.string.osservio_ui_filter_refine_hint) : android.text.TextUtils.join(" · ", details.subList(0, Math.min(3, details.size()))) + (details.size() > 3 ? " · +" + (details.size() - 3) : "");
        }
        String compact(String value) { return value.codePointCount(0, value.length()) > 80 ? value.substring(0, value.offsetByCodePoints(0, 80)) + "…" : value; }
        String timeText(int value) { return text(value == 0 ? R.string.osservio_ui_time_all : value == 5 ? R.string.osservio_ui_time_5 : value == 30 ? R.string.osservio_ui_time_30 : value == 60 ? R.string.osservio_ui_time_60 : R.string.osservio_ui_time_1440); }
        String summary(Group group) {
            return InspectorFilterOptions.selectionSummary(context, group, draft.values.get(group.field));
        }
        boolean outsideCard(int position) {
            if (position < 0 || position >= rows.size()) return true;
            Type type = rows.get(position).type;
            return type == Type.HEADING || type == Type.GAP || type == Type.NOTE || type == Type.SCOPE;
        }
        View rowView(int position, View recycled) {
            Row row = rows.get(position);
            if (row.type == Type.GAP) {
                View gap = recycled != null ? recycled : new View(context);
                gap.setMinimumHeight(dp(12)); gap.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                return gap;
            }
            if (row.type == Type.NOTE) { TextView label = InspectorWidgets.caption(context); label.setText(row.label); label.setPadding(dp(16), dp(12), dp(16), dp(12)); return label; }
            if (row.type == Type.HEADING) {
                TextView label = InspectorWidgets.sectionLabel(context); label.setText(row.label);
                return label;
            }
            if (row.type == Type.RECENT_TOGGLE) {
                InspectorFilterRow toggle = new InspectorFilterRow(context); toggle.bind(row.label, "", false);
                toggle.disclosureRotation(showsRecent ? -90 : 90); toggle.setOnClickListener(v -> choose(row));
                InspectorWidgets.groupedRow(toggle, true, !showsRecent); return toggle;
            }
            if (row.type == Type.RECENT) {
                InspectorQuery preset = recentItems.get(Integer.parseInt(row.value));
                LinearLayout entry = new LinearLayout(context); entry.setGravity(android.view.Gravity.CENTER_VERTICAL);
                boolean last = outsideCard(position + 1);
                InspectorFilterRow restore = new InspectorFilterRow(context);
                restore.hideDisclosure(); restore.bind(row.label, text(R.string.osservio_ui_restore_filter), true);
                restore.setOnClickListener(v -> choose(row));
                entry.addView(restore, new LinearLayout.LayoutParams(0, -2, 1));
                Button remove = new Button(context); remove.setText("×"); remove.setMinWidth(0); remove.setMinimumWidth(0);
                remove.setPadding(0, 0, 0, 0); remove.setMinHeight(dp(48)); remove.setStateListAnimator(null);
                remove.setTextColor(InspectorTheme.accent(context)); remove.setContentDescription(text(R.string.osservio_ui_forget_filter) + ", " + row.label);
                remove.setOnClickListener(v -> { recent.update(preset, true); recentItems.remove(preset); rebuild(); });
                entry.addView(remove, new LinearLayout.LayoutParams(dp(48), -1));
                InspectorWidgets.groupedRow(entry, false, last);
                recentFeedback(restore, last, true); recentFeedback(remove, last, false);
                return entry;
            }
            if (row.type == Type.SCOPE) {
                int selected = "http".equals(draft.transport()) ? 1 : "webSocket".equals(draft.transport()) ? 2 : 0;
                if (recycled instanceof LinearLayout && ((LinearLayout) recycled).getChildAt(0) instanceof InspectorSegmentControl) {
                    InspectorSegmentControl control = (InspectorSegmentControl) ((LinearLayout) recycled).getChildAt(0);
                    control.setOnCheckedChangeListener(null);
                    ((android.widget.RadioButton) control.getChildAt(selected)).setChecked(true);
                    control.setOnCheckedChangeListener((group, id) -> scopeSelected((Integer) group.findViewById(id).getTag()));
                    return recycled;
                }
                LinearLayout region = column();
                region.setClipChildren(false); region.setClipToPadding(false);
                region.addView(InspectorWidgets.segments(content, list, new int[] {R.string.osservio_ui_all, R.string.osservio_ui_http, R.string.osservio_ws_title}, selected, this::scopeSelected));
                TextView hint = InspectorWidgets.caption(context); hint.setPadding(dp(16), dp(8), dp(16), 0); hint.setText(R.string.osservio_ui_filter_switch); region.addView(hint); return region;
            }
            boolean first = outsideCard(position - 1), last = outsideCard(position + 1);
            if (row.type == Type.GROUP_HEADING) {
                InspectorFilterRow heading = recycled instanceof InspectorFilterRow ? (InspectorFilterRow) recycled : new InspectorFilterRow(context, true);
                heading.bind(row.label, selectionRule(row.group), false);
                InspectorWidgets.groupedRow(heading, first, last); return heading;
            }
            if (row.type == Type.INPUT) {
                if (conditions.duration.getParent() instanceof ViewGroup) ((ViewGroup) conditions.duration.getParent()).removeView(conditions.duration);
                LinearLayout region = column(); region.setPadding(dp(16), dp(8), dp(16), dp(12)); region.addView(conditions.duration); InspectorWidgets.groupedRow(region, first, last); return region;
            }
            if (row.type == Type.OPTION || row.type == Type.TIME_OPTION) {
                CheckedTextView view = recycled instanceof CheckedTextView ? (CheckedTextView) recycled : InspectorWidgets.choice(context); view.setText(row.label);
                view.setPadding(dp(picker == null && (row.group == null || !direct(row.group)) ? 28 : 16), dp(13), dp(16), dp(13));
                Set<String> values = row.group == null ? null : draft.values.get(row.group.field);
                view.setChecked(row.type == Type.TIME_OPTION ? row.value.equals(Integer.toString(draft.recentMinutes)) : row.value.equals(ALL) ? values == null || values.isEmpty() : values != null && values.contains(row.value));
                InspectorWidgets.groupedRow(view, first, last); return view;
            }
            InspectorFilterRow view = recycled instanceof InspectorFilterRow ? (InspectorFilterRow) recycled : new InspectorFilterRow(context);
            String detail = row.type == Type.GROUP ? summary(row.group) : row.type == Type.TIME ? timeText(draft.recentMinutes) : row.type == Type.DURATION ? draft.minimumDurationMillis == 0 ? text(R.string.osservio_ui_all) : "≥ " + draft.minimumDurationMillis + " ms" : refineSummary();
            view.bind(row.label, detail, row.type == Type.REFINE);
            boolean opensPicker = row.type == Type.GROUP && (row.group.options.size() > 6 || row.group.field == InspectorQuery.Field.STATUS);
            boolean open = row.type == Type.GROUP ? expanded.contains(row.group.field) : row.type == Type.TIME ? timeExpanded : row.type == Type.DURATION ? durationExpanded : refined;
            view.disclosureRotation(opensPicker ? 0 : open ? -90 : 90); view.setOnClickListener(v -> choose(row)); InspectorWidgets.groupedRow(view, first, last); return view;
        }
        void recentFeedback(View view, boolean last, boolean start) {
            boolean left = start != (list.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
            float endLeft = last && left ? dp(20) : 0, endRight = last && !left ? dp(20) : 0;
            android.graphics.drawable.GradientDrawable mask = InspectorTheme.shape(context, android.graphics.Color.WHITE, 0, false);
            mask.setCornerRadii(new float[] {0, 0, 0, 0, endRight, endRight, endLeft, endLeft});
            view.setBackground(InspectorTheme.ripple(context, null, mask));
        }
        boolean backToOverview() {
            if (picker == null) return false;
            picker = null; search.setText(""); rebuild();
            list.setSelectionFromTop(overviewPosition, overviewOffset);
            return true;
        }
        void focus(Target target) {
            if (target == null) return;
            Type type;
            if (target.field == InspectorQuery.Field.TRANSPORT) type = Type.SCOPE;
            else if (target == Target.TIME) { timeExpanded = true; type = Type.TIME; }
            else if (target == Target.DURATION) { durationExpanded = true; type = Type.DURATION; }
            else if (target == Target.EXCLUDED_TEXT || target == Target.JSON) {
                refined = true; rebuild();
                if (conditions != null) {
                    View field = target == Target.JSON ? conditions.path : conditions.exclude;
                    list.setSelection(list.getCount() - 1);
                    field.post(() -> field.requestRectangleOnScreen(new android.graphics.Rect(0, 0, field.getWidth(), field.getHeight())));
                }
                return;
            } else {
                Group group = null;
                for (Group candidate : groups) if (candidate.field == target.field) { group = candidate; break; }
                if (group == null) return;
                refined = advanced(group);
                if (!direct(group) && (group.options.size() > 6 || group.field == InspectorQuery.Field.STATUS)) {
                    picker = group; rebuild(); return;
                }
                expanded.add(group.field); type = direct(group) ? Type.GROUP_HEADING : Type.GROUP;
            }
            rebuild();
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                if (row.type == type && (row.group == null || row.group.field == target.field)) {
                    list.setSelectionFromTop(i + list.getHeaderViewsCount(), 0); return;
                }
            }
        }
        void scopeSelected(int index) {
            InspectorWidgets.hideKeyboard(list);
            scopes.switchTo(index == 1 ? "http" : index == 2 ? "webSocket" : null, draft);
            if (conditions != null) conditions.sync(); rebuild();
        }
        void choose(Row row) {
            switch (row.type) {
                case RECENT_TOGGLE: showsRecent = !showsRecent; break;
                case RECENT:
                    draft.replaceFilters(recentItems.get(Integer.parseInt(row.value))); scopes.reset(); refreshGroups();
                    if (conditions != null) conditions.sync();
                    showsRecent = false; rebuild(); list.setSelectionFromTop(0, 0); return;
                case GROUP:
                    InspectorWidgets.hideKeyboard(list);
                    if (row.group.options.size() > 6 || row.group.field == InspectorQuery.Field.STATUS) {
                        overviewPosition = list.getFirstVisiblePosition(); View first = list.getChildAt(0); overviewOffset = first == null ? 0 : first.getTop();
                        picker = row.group; search.setText(""); rebuild(); list.setSelectionFromTop(0, 0); return;
                    }
                    if (!expanded.remove(row.group.field)) expanded.add(row.group.field); break;
                case OPTION:
                    InspectorWidgets.hideKeyboard(list);
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
