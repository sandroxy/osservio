package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.sandrox.osservio.core.HTTPEvent;
import com.sandrox.osservio.core.IncidentHTTP;
import com.sandrox.osservio.core.IncidentLogs;
import com.sandrox.osservio.core.IncidentWebSockets;
import com.sandrox.osservio.core.LogEvent;
import com.sandrox.osservio.core.WebSocketEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One frozen group. Searching and opening details cannot query a live history or change capture. */
@SuppressLint("ViewConstructor")
final class IncidentEvidenceView extends LinearLayout {
    private final IncidentLogs logs;
    private final IncidentHTTP http;
    private final IncidentWebSockets sockets;
    private InspectorQuery query = new InspectorQuery();
    private final EditText search;
    private final Button filters;
    private final TextView criteria;
    private final TextView footer;
    private final List<Integer> indices = new ArrayList<>();
    private final Rows rows = new Rows();
    private InspectorScreen detail;
    IncidentEvidenceView(Context context, IncidentLogs logs, Runnable back) { this(context, logs, null, null, back); }
    IncidentEvidenceView(Context context, IncidentHTTP http, Runnable back) { this(context, null, http, null, back); }
    IncidentEvidenceView(Context context, IncidentWebSockets sockets, Runnable back) { this(context, null, null, sockets, back); }
    private IncidentEvidenceView(Context context, IncidentLogs logs, IncidentHTTP http, IncidentWebSockets sockets, Runnable back) {
        super(context); this.logs = logs; this.http = http; this.sockets = sockets; setOrientation(VERTICAL);
        Button goBack = InspectorWidgets.button(context, R.string.osservio_ui_back, back); addView(goBack, new LayoutParams(-1, -2));
        LinearLayout header = new LinearLayout(context); header.setOrientation(VERTICAL); header.setPadding(dp(16), 0, dp(16), 0);
        search = InspectorWidgets.search(context, R.string.osservio_incident_evidence_search, "", value -> { query.text = value; filter(); });
        header.addView(search, new LayoutParams(-1, -2));
        filters = InspectorWidgets.button(context, R.string.osservio_ui_filters, () -> {
            detail = InspectorFilterSheet.show(context, query, groups(), value -> { query = value; filter(); });
            detail.setOnDismissListener(ignored -> detail = null);
        });
        header.addView(filters, new LayoutParams(-2, -2)); criteria = caption(""); header.addView(criteria, new LayoutParams(-1, -2));
        ListView list = new ListView(context); list.setItemsCanFocus(true);
        list.setDivider(new android.graphics.drawable.ColorDrawable(InspectorTheme.color(context, R.color.osservio_separator))); list.setDividerHeight(dp(1));
        String title = logs != null ? context.getString(R.string.osservio_logs) : http != null ? "HTTP" : context.getString(R.string.osservio_ws_title);
        String info = logs != null ? IncidentPresentation.window(context, logs.observedAtMillis, logs.events.size(), logs.omittedEventCount, logs.sourceEvictedCount, null)
            : http != null ? IncidentPresentation.window(context, http.observedAtMillis, http.events.size(), http.omittedEventCount, http.sourceEvictedEvents, http.sourceEvictedBodies)
            : IncidentPresentation.window(context, sockets.observedAtMillis, sockets.events.size(), sockets.omittedEventCount, sockets.sourceEvictedEvents, sockets.sourceEvictedPayloads);
        header.addView(caption(title + "\n" + info), new LayoutParams(-1, -2)); list.addHeaderView(header, null, false); footer = caption(""); list.addFooterView(footer, null, false);
        list.setAdapter(rows); list.setOnItemClickListener((parent, view, position, id) -> {
            int index = position - list.getHeaderViewsCount(); if (index >= 0 && index < indices.size()) show(indices.get(index));
        });
        addView(list, new LayoutParams(-1, 0, 1));
        filter();
    }
    private int count() { return logs != null ? logs.events.size() : http != null ? http.events.size() : sockets.events.size(); }
    private List<InspectorFilterSheet.Group> groups() {
        if (logs != null) return InspectorFilterOptions.logs(getContext(), logs.events);
        if (http != null) return InspectorFilterOptions.network(getContext(), http.events, java.util.Collections.emptyList(), 1, query);
        return InspectorFilterOptions.timeline(getContext());
    }
    private void filter() {
        if (footer == null) return;
        indices.clear();
        for (int i = count() - 1; i >= 0; i--) {
            boolean matches = logs != null ? query.matches(logs.events.get(i), logs.observedAtMillis)
                : http != null ? query.matches(http.events.get(i), http.observedAtMillis) : query.matches(sockets.events.get(i), sockets.observedAtMillis);
            if (matches) indices.add(i);
        }
        filters.setText(query.activeCount() == 0 ? getContext().getString(R.string.osservio_ui_filters)
            : getContext().getString(R.string.osservio_ui_filter_count, query.activeCount())); InspectorWidgets.chip(filters, query.activeCount() > 0);
        criteria.setText(InspectorFilterOptions.summary(getContext(), query, groups())); criteria.setVisibility(query.activeCount() > 0 ? VISIBLE : GONE);
        footer.setText(indices.isEmpty() ? getContext().getString(R.string.osservio_incident_empty_detail,
            getContext().getString(query.text.isEmpty() && query.activeCount() == 0 ? R.string.osservio_incident_evidence_empty : R.string.osservio_ui_filtered_empty),
            getContext().getString(R.string.osservio_incident_frozen_detail)) : getContext().getString(R.string.osservio_incident_frozen_detail));
        rows.notifyDataSetChanged();
    }
    private void show(int index) {
        if (detail != null) return;
        InspectorWidgets.hideKeyboard(this);
        if (logs != null) detail = LogDetailDialog.create(getContext(), logs.events.get(index));
        else if (http != null) detail = new HTTPDetailDialog(getContext(), http.events.get(index), http.payloads).dialog;
        else detail = new WebSocketDetailDialog(getContext(), sockets.events.get(index), sockets.payloads).dialog;
        detail.setOnDismissListener(ignored -> detail = null); detail.show();
    }
    void dismissDialogs() { if (InspectorNavigation.owns(getContext())) return; if (detail != null) detail.dismiss(); }
    @Override protected void onDetachedFromWindow() { dismissDialogs(); super.onDetachedFromWindow(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView caption(String value) {
        TextView text = InspectorWidgets.caption(getContext());
        text.setText(value); text.setPadding(0, dp(8), 0, dp(8)); return text;
    }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return indices.size(); }
        @Override public Object getItem(int position) { return indices.get(position); }
        @Override public long getItemId(int position) { return indices.get(position); }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            InspectorWidgets.EventRow row = recycled instanceof InspectorWidgets.EventRow ? (InspectorWidgets.EventRow) recycled : new InspectorWidgets.EventRow(getContext());
            int index = indices.get(position); Context context = getContext();
            if (logs != null) {
                LogEvent event = logs.events.get(index);
                row.bind(event.level.name(), event.message, event.category, event.timestampMillis, InspectorTheme.accent(context));
            } else if (http != null) {
                HTTPEvent event = http.events.get(index);
                row.bind(event.method + (event.statusCode == null ? "" : " · " + event.statusCode), InspectorQuery.path(event.url),
                    InspectorQuery.host(event.url) + " · " + HTTPPresentation.summary(context, event), event.timestampMillis, InspectorTheme.accent(context));
            } else {
                WebSocketEvent event = sockets.events.get(index);
                row.bind("WebSocket", WebSocketPresentation.summary(context, event), event.url + " · " + event.connectionId, event.timestampMillis, InspectorTheme.accent(context));
            }
            return row;
        }
    }
}
