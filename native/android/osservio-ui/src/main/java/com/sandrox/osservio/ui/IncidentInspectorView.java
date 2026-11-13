package com.sandrox.osservio.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.sandrox.osservio.core.BreadcrumbHistory;
import com.sandrox.osservio.core.EnvironmentProvider;
import com.sandrox.osservio.core.IncidentArchive;
import com.sandrox.osservio.core.IncidentSnapshot;
import com.sandrox.osservio.core.IncidentSummary;
import com.sandrox.osservio.core.PersistenceStatus;
import com.sandrox.osservio.core.LogClock;
import com.sandrox.osservio.core.LogHistory;
import com.sandrox.osservio.core.LogSnapshot;
import com.sandrox.osservio.core.HTTPHistory;
import com.sandrox.osservio.core.HTTPSnapshot;
import com.sandrox.osservio.core.WebSocketHistory;
import com.sandrox.osservio.core.WebSocketSnapshot;
import com.sandrox.osservio.core.IncidentLogs;
import com.sandrox.osservio.core.IncidentHTTP;
import com.sandrox.osservio.core.IncidentWebSockets;
import com.sandrox.osservio.core.IncidentExporter;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Saved incident browsing and manual marking. The host owns persistence, histories, and presentation. */
@SuppressLint("ViewConstructor")
public final class IncidentInspectorView extends FrameLayout implements InspectorToolbarActions.Provider {
    @Override public View navigationAction() { return header.navigationAction(); }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        InspectorKeyboardDismissal.outside(this, event); return super.dispatchTouchEvent(event);
    }

    static final class State {
        InspectorQuery query = new InspectorQuery();
        List<IncidentSummary> snapshots = Collections.emptyList();
        IncidentSnapshot selected;
        Long updatedAt;
        int position;
        int offset;
    }
    private final State state;
    private final List<IncidentSummary> visible = new ArrayList<>();
    private final InspectorFeedHeader header;
    private final IncidentArchive archive;
    private final EnvironmentProvider environment;
    private final BreadcrumbHistory breadcrumbs;
    private final LogHistory logs;
    private final HTTPHistory http;
    private final WebSocketHistory webSockets;
    private final IncidentExporter exporter;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout listing;
    private final TextView notice;
    private final TextView empty;
    private final Button mark;
    private final Button retry;
    private final android.widget.ImageButton actions;
    private boolean retryNeeded;
    private final ListView list;
    private final Rows rows = new Rows();
    private List<IncidentSummary> snapshots = Collections.emptyList();
    private IncidentSnapshotView selected;
    private InspectorScreen snapshotScreen;
    private IncidentComposer composer;
    private InspectorScreen confirmation;
    private boolean reading;
    private boolean attempted;
    private boolean justSaved;
    private int generation;
    private Long updatedAt;

    public IncidentInspectorView(Context context, IncidentArchive archive, EnvironmentProvider environment, BreadcrumbHistory breadcrumbs) {
        this(context, archive, environment, breadcrumbs, null, null, null);
    }
    public IncidentInspectorView(Context context, IncidentArchive archive, EnvironmentProvider environment, BreadcrumbHistory breadcrumbs,
                                 LogHistory logs, HTTPHistory http, WebSocketHistory webSockets) {
        this(context, archive, environment, breadcrumbs, logs, http, webSockets, null);
    }
    public IncidentInspectorView(Context context, IncidentArchive archive, EnvironmentProvider environment, BreadcrumbHistory breadcrumbs,
                                 LogHistory logs, HTTPHistory http, WebSocketHistory webSockets, IncidentExporter exporter) {
        this(context, archive, environment, breadcrumbs, logs, http, webSockets, exporter, new State());
    }
    IncidentInspectorView(Context host, IncidentArchive archive, EnvironmentProvider environment, BreadcrumbHistory breadcrumbs,
                          LogHistory logs, HTTPHistory http, WebSocketHistory webSockets, IncidentExporter exporter, State state) {
        super(InspectorTheme.context(host)); Context context = getContext();
        this.archive = Objects.requireNonNull(archive, "archive");
        this.environment = Objects.requireNonNull(environment, "environment"); this.breadcrumbs = Objects.requireNonNull(breadcrumbs, "breadcrumbs");
        this.logs = logs; this.http = http; this.webSockets = webSockets; this.exporter = exporter; this.state = state;
        snapshots = state.snapshots; updatedAt = state.updatedAt;
        setBackgroundColor(InspectorTheme.canvas(context));
        listing = new LinearLayout(context); listing.setOrientation(LinearLayout.VERTICAL); addView(listing, new LayoutParams(-1, -1));
        header = new InspectorFeedHeader(context, R.string.osservio_ui_problem_search, state.query.text,
            value -> { state.query.text = value; render(); }, () -> { }, this::filters, () -> confirmDeletion(null), true, R.string.osservio_incident_delete_all);
        header.onQueryChange = query -> { state.query = query; render(); };
        mark = InspectorWidgets.button(context, R.string.osservio_ui_record_problem, this::mark); InspectorWidgets.primaryAction(mark);
        Runnable markLabel = () -> {
            String label = context.getString(R.string.osservio_ui_record_problem);
            mark.setText(InspectorWidgets.actionTitle(context, R.string.osservio_ui_record_problem, R.drawable.osservio_add)); mark.setContentDescription(label);
        };
        InspectorAppearance.content(mark, markLabel); markLabel.run();
        retry = InspectorWidgets.button(context, R.string.osservio_ui_retry, this::read);
        LinearLayout.LayoutParams markLayout = new LinearLayout.LayoutParams(-1, -2); markLayout.bottomMargin = dp(12);
        header.addView(mark, 0, markLayout); header.addView(retry, 1, new LinearLayout.LayoutParams(-1, -2));
        notice = header.status; notice.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        empty = header.empty; actions = header.actions;
        list = new InspectorScroll.List(context); list.addHeaderView(header, null, false); list.setItemsCanFocus(true);
        list.setDivider(null); list.setDividerHeight(0);
        TextView hint = caption(); hint.setText(R.string.osservio_incident_archive_hint); hint.setPadding(dp(16), dp(16), dp(16), dp(16));
        list.addFooterView(hint, null, false); list.setAdapter(rows);
        list.setOnItemClickListener((parent, view, position, id) -> {
            int index = position - list.getHeaderViewsCount(); if (!reading && index >= 0 && index < visible.size()) open(visible.get(index).id);
        });
        listing.addView(list, new LinearLayout.LayoutParams(-1, -1)); render();
        list.setSelectionFromTop(state.position, state.offset);
        if (state.selected != null) showSnapshot(state.selected);
    }
    private List<InspectorFilterSheet.Group> filterGroups() {
        List<InspectorFilterSheet.Group> groups = new ArrayList<>();
        InspectorFilterSheet.Group types = InspectorFilterOptions.group(getContext(), InspectorQuery.Field.RECORD, R.string.osservio_ui_record);
        for (IncidentSummary snapshot : snapshots) {
            if (snapshot.diagnosticKind == null) types.option("manual", getContext().getString(R.string.osservio_ui_manual));
            else types.option(snapshot.diagnosticKind.name(), SystemDiagnosticPresentation.kind(getContext(), snapshot.diagnosticKind));
        }
        groups.add(types);
        return groups;
    }
    private void filters() {
        confirmation = InspectorFilterSheet.show(getContext(), state.query, filterGroups(), R.string.osservio_ui_record_time,
            query -> { state.query = query; render(); });
        confirmation.setOnDismissListener(ignored -> confirmation = null);
    }
    void saveReadingPosition() {
        state.position = list.getFirstVisiblePosition(); View first = list.getChildAt(0); state.offset = first == null ? 0 : first.getTop();
    }
    boolean navigateBack() {
        if (selected == null) return false;
        if (reading) return true;
        showList();
        return true;
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); visibility(); }
    @Override protected void onDetachedFromWindow() { saveReadingPosition(); invalidateQueries(); dismissDialogs(); super.onDetachedFromWindow(); }
    @Override protected void onVisibilityChanged(View changed, int visibility) { super.onVisibilityChanged(changed, visibility); visibility(); }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); visibility(); }
    private void visibility() {
        if (listing == null) return;
        if (isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE) {
            if (!attempted && selected == null) read();
        } else {
            invalidateQueries();
            // Keep a draft through app backgrounding, but do not leave dialogs above a hidden or detached SDK view.
            if (!isShown() || !isAttachedToWindow()) dismissDialogs();
        }
    }
    private void invalidateQueries() { generation++; reading = false; attempted = false; }
    private void dismissDialogs() {
        if (InspectorNavigation.owns(getContext())) return;
        if (composer != null) composer.dismiss();
        if (confirmation != null) confirmation.dismiss();
        if (selected != null) selected.dismissDialogs();
    }
    private int begin() {
        reading = true; retryNeeded = false; attempted = true; generation++;
        updateReadingState(); return generation;
    }
    private void read() {
        if (reading) return;
        int request = begin(); WeakReference<IncidentInspectorView> reference = new WeakReference<>(this); Handler handler = main;
        archive.readIncidents((value, error) -> handler.post(() -> {
            IncidentInspectorView view = reference.get(); if (view == null || view.generation != request) return;
            view.reading = false;
            if (error != null || value == null) view.failed(error == null ? PersistenceStatus.Error.UNAVAILABLE : error);
            else {
                view.snapshots = value; view.updatedAt = System.currentTimeMillis();
                view.state.snapshots = value; view.state.updatedAt = view.updatedAt;
                view.notice.setText(view.justSaved ? view.getContext().getString(R.string.osservio_incident_saved_read_at, RuntimePresentation.date(view.getContext(), view.updatedAt)) : "");
                view.justSaved = false;
            }
            view.render();
        }));
    }
    private void open(String id) {
        int request = begin(); WeakReference<IncidentInspectorView> reference = new WeakReference<>(this); Handler handler = main;
        archive.readIncident(id, (snapshot, error) -> handler.post(() -> {
            IncidentInspectorView view = reference.get(); if (view == null || view.generation != request) return;
            view.reading = false;
            if (error != null) view.failed(error);
            else if (snapshot == null) view.notice.setText(R.string.osservio_incident_missing);
            else {
                view.showSnapshot(snapshot);
            }
            view.render();
        }));
    }
    private void showSnapshot(IncidentSnapshot snapshot) {
        state.selected = snapshot;
        selected = new IncidentSnapshotView(getContext(), snapshot, this::showList, () -> confirmDeletion(snapshot.id), exporter);
        if (InspectorNavigation.owns(getContext())) {
            state.selected = null; // The tab's page stack owns this snapshot across root-view rebuilding.
            snapshotScreen = InspectorDialogs.screen(getContext(), R.string.osservio_incident_title, selected);
            snapshotScreen.setBackInterceptor(() -> reading);
            snapshotScreen.setOnDismissListener(ignored -> { snapshotScreen = null; selected = null; attempted = false; visibility(); });
            snapshotScreen.show();
        } else { listing.setVisibility(GONE); addView(selected, new LayoutParams(-1, -1)); }
    }
    private void showList() {
        state.selected = null;
        if (snapshotScreen != null) { snapshotScreen.dismiss(); return; }
        hideKeyboard();
        if (selected != null) { removeView(selected); selected = null; }
        listing.setVisibility(VISIBLE); read();
    }
    private void mark() {
        if (reading || composer != null) return;
        final IncidentSnapshot snapshot;
        try {
            IncidentSnapshot mark = IncidentSnapshot.capture(null, null, breadcrumbs.snapshot(), 200, 32768, LogClock.SYSTEM);
            LogSnapshot logSource = logs == null ? null : logs.snapshot();
            IncidentLogs logWindow = logSource == null ? null : IncidentLogs.capture(logSource, System.currentTimeMillis(), 200, 16384);
            HTTPSnapshot httpSource = http == null ? null : http.snapshot();
            IncidentHTTP httpWindow = httpSource == null ? null : IncidentHTTP.capture(httpSource, System.currentTimeMillis(), http, false, 50, 16384, 12288);
            WebSocketSnapshot socketSource = webSockets == null ? null : webSockets.snapshot();
            IncidentWebSockets socketWindow = socketSource == null ? null : IncidentWebSockets.capture(socketSource, System.currentTimeMillis(), webSockets, false, 100, 16384, 12288);
            snapshot = IncidentSnapshot.restore(mark.id, mark.timestampMillis, null, null, mark.breadcrumbs, logWindow, httpWindow, socketWindow);
        }
        catch (IllegalArgumentException failure) { notice.setText(R.string.osservio_incident_mark_failed); return; }
        composer = new IncidentComposer(getContext(), snapshot, archive, environment, http, webSockets,
            logs instanceof com.sandrox.osservio.core.LogDetailsHistory ? (com.sandrox.osservio.core.LogDetailsHistory) logs : null, () -> {
            justSaved = true;
            if (isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE) read();
            else attempted = false;
        }, () -> composer = null);
        composer.show();
    }
    private void confirmDeletion(String id) {
        if (reading || confirmation != null) return;
        confirmation = InspectorDialogs.confirm(getContext(),
            id == null ? R.string.osservio_incident_delete_all : R.string.osservio_incident_delete,
            getContext().getString(R.string.osservio_incident_delete_confirmation),
            R.string.osservio_archive_delete_confirm, (dialog, which) -> delete(id));
        confirmation.setOnDismissListener(ignored -> confirmation = null); confirmation.show();
    }
    private void delete(String id) {
        int request = begin();
        WeakReference<IncidentInspectorView> reference = new WeakReference<>(this); Handler handler = main;
        IncidentArchive.Completion completion = error -> handler.post(() -> {
            IncidentInspectorView view = reference.get(); if (view == null || view.generation != request) return;
            view.reading = false; view.render();
            if (error == null) view.showList(); else view.failed(error);
        });
        if (id == null) archive.deleteAllIncidents(completion); else archive.deleteIncident(id, completion);
    }
    private void failed(PersistenceStatus.Error error) {
        retryNeeded = true;
        String message = PersistencePresentation.error(getContext(), error);
        if (updatedAt != null) message += "\n" + getContext().getString(R.string.osservio_runtime_previous) + " · " + RuntimePresentation.date(getContext(), updatedAt);
        notice.setText(message); if (selected != null) selected.error(message); render();
    }
    private void render() {
        if (list == null) return;
        updateReadingState(); retry.setVisibility(retryNeeded ? VISIBLE : GONE);
        visible.clear();
        long now = updatedAt == null ? System.currentTimeMillis() : updatedAt;
        for (IncidentSummary snapshot : snapshots) if (state.query.matches(snapshot, now, IncidentPresentation.title(getContext(), snapshot.diagnosticKind))) visible.add(snapshot);
        header.setQuery(state.query, filterGroups(), visible.size(), snapshots.size()); header.setPaused(false);
        list.setEnabled(!reading);
        empty.setVisibility(visible.isEmpty() ? VISIBLE : GONE);
        empty.setText(reading ? R.string.osservio_archive_reading : snapshots.isEmpty() ? R.string.osservio_incident_empty : R.string.osservio_ui_filtered_empty);
        if (selected != null) selected.busy(reading); rows.notifyDataSetChanged();
    }
    private void updateReadingState() {
        mark.setEnabled(!reading); retry.setEnabled(!reading); actions.setEnabled(!reading && !snapshots.isEmpty());
        list.setEnabled(!reading); header.setReading(reading);
        if (snapshots.isEmpty()) empty.setText(reading ? R.string.osservio_archive_reading : R.string.osservio_incident_empty);
    }
    private void hideKeyboard() {
        View focused = findFocus(); if (focused != null) focused.clearFocus();
        InputMethodManager keyboard = getContext().getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
    }
    private TextView caption() { return InspectorWidgets.caption(getContext()); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            InspectorWidgets.EventRow row = recycled instanceof InspectorWidgets.EventRow
                ? (InspectorWidgets.EventRow) recycled : new InspectorWidgets.EventRow(getContext());
            IncidentSummary snapshot = visible.get(position);
            row.bind(getContext().getString(snapshot.diagnosticKind == null ? R.string.osservio_ui_manual : R.string.osservio_ui_system),
                IncidentPresentation.listTitle(getContext(), snapshot), IncidentPresentation.listSubtitle(getContext(), snapshot),
                snapshot.timestampMillis, InspectorTheme.accent(getContext()));
            row.timestamp(IncidentPresentation.listTime(getContext(), snapshot));
            row.grouped(position == 0, position == getCount() - 1); return row;
        }
    }
}
