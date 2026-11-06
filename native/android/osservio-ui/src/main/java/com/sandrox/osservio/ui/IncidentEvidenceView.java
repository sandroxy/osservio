package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
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
    private final EditText search;
    private final TextView footer;
    private final List<Integer> indices = new ArrayList<>();
    private final Rows rows = new Rows();
    private AlertDialog detail;
    IncidentEvidenceView(Context context, IncidentLogs logs, Runnable back) { this(context, logs, null, null, back); }
    IncidentEvidenceView(Context context, IncidentHTTP http, Runnable back) { this(context, null, http, null, back); }
    IncidentEvidenceView(Context context, IncidentWebSockets sockets, Runnable back) { this(context, null, null, sockets, back); }
    private IncidentEvidenceView(Context context, IncidentLogs logs, IncidentHTTP http, IncidentWebSockets sockets, Runnable back) {
        super(context); this.logs = logs; this.http = http; this.sockets = sockets; setOrientation(VERTICAL);
        Button goBack = new Button(context); goBack.setText(R.string.osservio_incident_manual); goBack.setAllCaps(false); goBack.setMinHeight(dp(48));
        goBack.setOnClickListener(view -> back.run()); addView(goBack, new LayoutParams(-1, -2));
        search = new EditText(context); search.setSingleLine(true); search.setHint(R.string.osservio_incident_evidence_search); search.setMinHeight(dp(48));
        addView(search, new LayoutParams(-1, -2));
        ListView list = new ListView(context);
        String title = logs != null ? context.getString(R.string.osservio_logs) : http != null ? "HTTP" : context.getString(R.string.osservio_ws_title);
        String info = logs != null ? IncidentPresentation.window(context, logs.observedAtMillis, logs.events.size(), logs.omittedEventCount, logs.sourceEvictedCount, null)
            : http != null ? IncidentPresentation.window(context, http.observedAtMillis, http.events.size(), http.omittedEventCount, http.sourceEvictedEvents, http.sourceEvictedBodies)
            : IncidentPresentation.window(context, sockets.observedAtMillis, sockets.events.size(), sockets.omittedEventCount, sockets.sourceEvictedEvents, sockets.sourceEvictedPayloads);
        list.addHeaderView(caption(title + "\n" + info), null, false); footer = caption(""); list.addFooterView(footer, null, false);
        list.setAdapter(rows); list.setOnItemClickListener((parent, view, position, id) -> {
            int index = position - list.getHeaderViewsCount(); if (index >= 0 && index < indices.size()) show(indices.get(index));
        });
        addView(list, new LayoutParams(-1, 0, 1));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { filter(); }
            @Override public void afterTextChanged(Editable text) { }
        });
        filter();
    }
    private int count() { return logs != null ? logs.events.size() : http != null ? http.events.size() : sockets.events.size(); }
    private void filter() {
        indices.clear(); String query = search.getText().toString().toLowerCase(Locale.ROOT);
        for (int index = count() - 1; index >= 0; index--) if (query.isEmpty() || searchText(index).toLowerCase(Locale.ROOT).contains(query)) indices.add(index);
        footer.setText(indices.isEmpty() ? getContext().getString(R.string.osservio_incident_empty_detail,
            getContext().getString(query.isEmpty() ? R.string.osservio_incident_evidence_empty : R.string.osservio_ws_no_matches),
            getContext().getString(R.string.osservio_incident_frozen_detail)) : getContext().getString(R.string.osservio_incident_frozen_detail));
        rows.notifyDataSetChanged();
    }
    private String title(int index) {
        if (logs != null) { LogEvent event = logs.events.get(index); return event.level.name() + " · " + event.category; }
        if (http != null) { HTTPEvent event = http.events.get(index); return event.method + " " + event.url; }
        return WebSocketPresentation.summary(getContext(), sockets.events.get(index));
    }
    private String subtitle(int index) {
        if (logs != null) { LogEvent event = logs.events.get(index); return RuntimePresentation.date(event.timestampMillis) + " · #" + event.sequence + "\n" + event.message; }
        if (http != null) { HTTPEvent event = http.events.get(index); return HTTPPresentation.summary(getContext(), event) + "\n" + RuntimePresentation.date(event.timestampMillis); }
        WebSocketEvent event = sockets.events.get(index); return event.url + "\n" + RuntimePresentation.date(event.timestampMillis) + "\n" + event.connectionId;
    }
    private String searchText(int index) {
        if (logs != null) return title(index) + " " + logs.events.get(index).message;
        if (http != null) return title(index);
        WebSocketEvent event = sockets.events.get(index); return title(index) + " " + event.url + " " + event.connectionId;
    }
    private void show(int index) {
        if (detail != null) return;
        if (logs != null) detail = LogDetailDialog.create(getContext(), logs.events.get(index));
        else if (http != null) detail = new HTTPDetailDialog(getContext(), http.events.get(index), http.payloads).dialog;
        else detail = new WebSocketDetailDialog(getContext(), sockets.events.get(index), sockets.payloads).dialog;
        detail.setOnDismissListener(ignored -> detail = null); detail.show();
    }
    void dismissDialogs() { if (detail != null) detail.dismiss(); }
    @Override protected void onDetachedFromWindow() { dismissDialogs(); super.onDetachedFromWindow(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView caption(String value) {
        TextView text = new TextView(getContext()); text.setTextAppearance(android.R.style.TextAppearance_Material_Caption);
        text.setText(value); text.setPadding(0, dp(8), 0, dp(8)); return text;
    }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return indices.size(); }
        @Override public Object getItem(int position) { return indices.get(position); }
        @Override public long getItemId(int position) { return indices.get(position); }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row;
            if (recycled instanceof LinearLayout) row = (LinearLayout) recycled;
            else {
                row = new LinearLayout(getContext()); row.setOrientation(VERTICAL); row.setPadding(dp(4), dp(12), dp(4), dp(12));
                TextView title = new TextView(getContext()); title.setTextAppearance(android.R.style.TextAppearance_Material_Body1); title.setMaxLines(2);
                TextView subtitle = caption(""); subtitle.setMaxLines(3);
                row.addView(title, new LayoutParams(-1, -2)); row.addView(subtitle, new LayoutParams(-1, -2));
            }
            int index = indices.get(position); ((TextView) row.getChildAt(0)).setText(title(index)); ((TextView) row.getChildAt(1)).setText(subtitle(index)); return row;
        }
    }
}
