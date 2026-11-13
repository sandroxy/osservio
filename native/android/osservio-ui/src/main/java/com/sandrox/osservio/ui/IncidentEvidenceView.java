package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.sandrox.osservio.core.HTTPEvent;
import com.sandrox.osservio.core.IncidentPayload;
import com.sandrox.osservio.core.IncidentHTTP;
import com.sandrox.osservio.core.IncidentLogs;
import com.sandrox.osservio.core.IncidentWebSockets;
import com.sandrox.osservio.core.LogEvent;
import com.sandrox.osservio.core.WebSocketEvent;
import java.util.ArrayList;
import java.util.List;

/** One frozen group. Searching and opening details cannot query a live history or change capture. */
@SuppressLint("ViewConstructor")
final class IncidentEvidenceView extends LinearLayout implements InspectorScreen.Content {
    private final IncidentLogs logs;
    private final IncidentHTTP http;
    private final IncidentWebSockets sockets;
    private InspectorQuery query = new InspectorQuery();
    private final InspectorFeedHeader header;
    private final android.widget.ImageButton goBack;
    private final TextView footer;
    private final List<Integer> indices = new ArrayList<>();
    private final Rows rows = new Rows();
    private InspectorScreen detail;
    private InspectorRecordSearchTask task;
    private InspectorRecordSearch.Result result;
    private boolean searchBusy;
    private long generation;
    private String connectionID;
    void scopeConnection(String id) { connectionID = id; filter(); }
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final List<InspectorRecordSearch.Record> records = new ArrayList<>();
    IncidentEvidenceView(Context context, IncidentLogs logs, Runnable back) { this(context, logs, null, null, back); }
    IncidentEvidenceView(Context context, IncidentHTTP http, Runnable back) { this(context, null, http, null, back); }
    IncidentEvidenceView(Context context, IncidentWebSockets sockets, Runnable back) { this(context, null, null, sockets, back); }
    private IncidentEvidenceView(Context context, IncidentLogs logs, IncidentHTTP http, IncidentWebSockets sockets, Runnable back) {
        super(context); this.logs = logs; this.http = http; this.sockets = sockets; setOrientation(VERTICAL);
        if (logs != null) for (LogEvent event : logs.events) records.add(new InspectorRecordSearch.Record(event));
        else if (http != null) for (HTTPEvent event : http.events) records.add(new InspectorRecordSearch.Record(event));
        else for (WebSocketEvent event : sockets.events) records.add(new InspectorRecordSearch.Record(event));
        goBack = InspectorWidgets.icon(context, R.drawable.osservio_back, R.string.osservio_ui_back, back); addView(goBack);
        header = new InspectorFeedHeader(context, R.string.osservio_ui_search_saved, "", value -> { query.text = value; filter(); }, () -> { }, () -> {
            detail = InspectorFilterSheet.show(context, query, groups(), true, value -> { query = value; filter(); });
            detail.setOnDismissListener(ignored -> detail = null);
        }, () -> { }, true); header.hideActions();
        header.onQueryChange = value -> { query = value; filter(); };
        ListView list = new InspectorScroll.List(context); list.setItemsCanFocus(true);
        list.setDivider(null); list.setDividerHeight(0);
        InspectorAppearance.style(header.status, () -> header.status.setTextColor(InspectorTheme.secondary(context)));
        list.addHeaderView(header, null, false); footer = caption(""); footer.setPadding(dp(16), dp(8), dp(16), dp(16)); list.addFooterView(footer, null, false);
        list.setAdapter(rows); list.setOnItemClickListener((parent, view, position, id) -> {
            int index = position - list.getHeaderViewsCount(); if (index >= 0 && index < indices.size()) show(indices.get(index));
        });
        addView(list, new LayoutParams(-1, 0, 1));
        InspectorAppearance.content(this, () -> {
            list.setDivider(null);
            filter();
        });
        filter();
    }
    @Override public void bindScreen(InspectorScreen screen) {
        goBack.setVisibility(GONE); screen.title(logs != null ? R.string.osservio_logs : http != null ? R.string.osservio_ui_http : connectionID == null ? R.string.osservio_ws_title : R.string.osservio_ui_connection_records);
    }
    private int count() { return logs != null ? logs.events.size() : http != null ? http.events.size() : sockets.events.size(); }
    private boolean inScope(int index) { return connectionID == null || sockets == null || connectionID.equals(sockets.events.get(index).connectionId); }
    private int scopeCount() { int count = 0; for (int i = 0; i < count(); i++) if (inScope(i)) count++; return count; }
    private List<InspectorFilterSheet.Group> groups() {
        if (logs != null) return InspectorFilterOptions.logs(getContext(), logs.events);
        if (http != null) {
            List<InspectorFilterSheet.Group> groups = InspectorFilterOptions.network(getContext(), http.events, java.util.Collections.emptyList(), query);
            java.util.Iterator<InspectorFilterSheet.Group> iterator = groups.iterator();
            while (iterator.hasNext()) { InspectorQuery.Field field = iterator.next().field;
                if (field == InspectorQuery.Field.TRANSPORT || java.util.Arrays.asList(InspectorQuery.SOCKET_FIELDS).contains(field)) iterator.remove();
            }
            return groups;
        }
        List<WebSocketEvent> events = new ArrayList<>();
        for (int i = 0; i < sockets.events.size(); i++) if (inScope(i)) events.add(sockets.events.get(i));
        List<InspectorFilterSheet.Group> groups = InspectorFilterOptions.network(getContext(), java.util.Collections.emptyList(), events, query, false, true);
        if (connectionID != null) for (int i = groups.size() - 1; i >= 0; i--) if (groups.get(i).field == InspectorQuery.Field.CONNECTION) groups.remove(i);
        return groups;
    }
    private void filter() {
        if (footer == null) return;
        cancelSearch(); result = null; searchBusy = false; indices.clear();
        long now = logs != null ? logs.observedAtMillis : http != null ? http.observedAtMillis : sockets.observedAtMillis;
        if (!query.searchesPayload()) for (int i = count() - 1; i >= 0; i--) if (inScope(i) && records.get(i).matchesFacets(query, now)) indices.add(i);
        if (query.searchesPayload()) {
            indices.clear(); long current = generation; InspectorQuery selected = query.copy();
            java.util.Map<String, IncidentPayload> saved = http != null ? http.payloads : sockets != null ? sockets.payloads : logs.payloads;
            List<InspectorRecordSearch.Record> searchRecords = new ArrayList<>();
            List<Integer> scopedIndices = new ArrayList<>();
            for (int i = count() - 1; i >= 0; i--) if (inScope(i)) { scopedIndices.add(i); searchRecords.add(records.get(i)); }
            InspectorRecordSearch.Preparation preparation = InspectorRecordPresentation.searchPreparation(getContext(), selected);
            handler.postDelayed(() -> {
                if (current != generation) return;
                task = InspectorRecordSearchTask.submitProgressive((cancelled, progress) -> InspectorRecordSearch.run(searchRecords, selected, now, null, null, saved, null, InspectorRecordSearch.MAX_UNITS, cancelled, preparation, progress), found -> {
                    if (current != generation) return;
                    if (found.complete) task = null;
                    result = found; indices.clear();
                    for (int position : found.positions) indices.add(scopedIndices.get(position));
                    display();
                });
                if (task == null) {
                    searchBusy = true; display();
                    handler.postDelayed(() -> { if (current == generation && isAttachedToWindow()) filter(); }, 500);
                }
            }, 200);
        }
        display();
    }
    private void cancelSearch() { generation++; handler.removeCallbacksAndMessages(null); if (task != null) task.cancel(); task = null; }
    private void display() {
        header.setQuery(query, this::groups, indices.size(), scopeCount()); header.empty.setVisibility(GONE);
        footer.setText(query.searchesPayload() && result == null ? searchBusy ? R.string.osservio_busy : R.string.osservio_ui_searching : count() == 0 ? R.string.osservio_incident_evidence_empty : R.string.osservio_ui_filtered_empty);
        footer.setVisibility(indices.isEmpty() ? VISIBLE : GONE);
        long time = logs != null ? logs.observedAtMillis : http != null ? http.observedAtMillis : sockets.observedAtMillis;
        String note = getContext().getString(R.string.osservio_ui_evidence_saved) + " · " + InspectorValuePresentation.eventTime(time, true);
        boolean loss = logs != null ? logs.omittedEventCount > 0 || logs.sourceEvictedCount > 0 || logs.sourceEvictedDetails > 0
            : http != null ? http.omittedEventCount > 0 || http.sourceEvictedEvents > 0 || http.sourceEvictedBodies > 0
            : sockets.omittedEventCount > 0 || sockets.sourceEvictedEvents > 0 || sockets.sourceEvictedPayloads > 0;
        String activity = InspectorSearchFeedback.activity(getContext(), result, query.searchesPayload() && (result == null || !result.complete), false);
        if (activity != null) note += "\n" + (searchBusy ? getContext().getString(R.string.osservio_busy) : activity);
        if (loss) note += "\n" + getContext().getString(R.string.osservio_ui_retention_loss);
        if (result != null && (result.unavailable > 0 || result.truncated > 0 || result.limited)) note += "\n" + getContext().getString(R.string.osservio_ui_search_partial);
        header.status.setText(note); header.setPaused(false); rows.notifyDataSetChanged();
    }
    private void show(int index) {
        if (detail != null) return;
        InspectorWidgets.hideKeyboard(this);
        if (logs != null) detail = LogDetailDialog.create(getContext(), logs.events.get(index), null, logs.payloads);
        else if (http != null) detail = new HTTPDetailDialog(getContext(), http.events.get(index), http.payloads, result == null ? null : result.previewSources.get(records.get(index).key())).dialog;
        else {
            WebSocketDetailDialog message = new WebSocketDetailDialog(getContext(), sockets.events.get(index), sockets.payloads);
            message.showConnection(() -> {
                IncidentEvidenceView connection = new IncidentEvidenceView(getContext(), sockets, () -> { }); connection.scopeConnection(sockets.events.get(index).connectionId);
                InspectorDialogs.screen(getContext(), R.string.osservio_ui_connection_records, connection).show();
            }); detail = message.dialog;
        }
        detail.setOnDismissListener(ignored -> detail = null); detail.show();
    }
    void dismissDialogs() { if (InspectorNavigation.owns(getContext())) return; if (detail != null) detail.dismiss(); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (query.searchesPayload() && (result == null || !result.complete)) filter(); }
    @Override protected void onDetachedFromWindow() { cancelSearch(); dismissDialogs(); super.onDetachedFromWindow(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView caption(String value) { return caption(() -> value); }
    private TextView caption(InspectorAppearance.Value<String> value) {
        TextView text = InspectorWidgets.caption(getContext());
        InspectorAppearance.text(text, value); text.setPadding(0, dp(8), 0, dp(8)); return text;
    }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return indices.size(); }
        @Override public Object getItem(int position) { return indices.get(position); }
        @Override public long getItemId(int position) { return indices.get(position); }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            InspectorWidgets.EventRow row = recycled instanceof InspectorWidgets.EventRow ? (InspectorWidgets.EventRow) recycled : new InspectorWidgets.EventRow(getContext());
            int index = indices.get(position); Context context = getContext();
            InspectorRecordSearch.Record record = records.get(index);
            String preview = result == null ? null : result.previews.get(record.key());
            if (logs != null) InspectorRecordPresentation.log(context, logs.events.get(index), preview, result == null ? null : result.previewSources.get(record.key())).bind(row, context);
            else if (http != null) InspectorRecordPresentation.http(context, http.events.get(index), InspectorRecordPresentation.matchPreview(context, result, record.key())).bind(row, context);
            else {
                WebSocketEvent event = sockets.events.get(index);
                if (preview == null && event.kind == WebSocketEvent.Kind.MESSAGE && event.payload != null && event.payload.blobId != null) {
                    IncidentPayload payload = sockets.payloads.get(event.payload.blobId);
                    preview = payload != null && payload.state == IncidentPayload.State.AVAILABLE && payload.text != null
                        ? payload.text.isEmpty() ? context.getString(R.string.osservio_ui_content_empty) : InspectorRecordSearch.preview(payload.text, "")
                        : IncidentPayloadPresentation.content(context, payload);
                }
                InspectorRecordPresentation.socket(context, event, preview, false, result == null ? null : result.previewSources.get(record.key())).bind(row, context);
            }
            row.grouped(position == 0, position == getCount() - 1); return row;
        }
    }
}
